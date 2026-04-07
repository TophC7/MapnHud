package dev.mapnhud.client.map;

import dev.mapnhud.MapnHudMod;
import dev.mapnhud.client.map.cave.CaveLayeredEntry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

/**
 * Handles saving and loading minimap chunk data to/from disk.
 *
 * <p>Data is stored in region files (32x32 chunks each) under a per-world,
 * per-dimension directory. Each chunk position can have multiple layers
 * (surface + cave at different Y depths). The layerY is stored per entry
 * in the region file.
 *
 * <h3>Region file format (v4)</h3>
 * <pre>
 * Header:  "FXMP" (4B) | version (1B) | entry count (2B unsigned)
 * Per entry: localX (1B) | localZ (1B) | layerY (2B signed short) | flags (1B) | payload length (4B) | GZIP payload
 * </pre>
 *
 * <p>Surface data uses {@code layerY = Short.MAX_VALUE}. Cave data uses
 * the deterministic bucket key from the cache layer system.
 *
 * <h3>Save durability</h3>
 * <p>The main thread captures snapshots of {@link ChunkColorData} references
 * (immutable, safe to share). The IO thread does all serialization,
 * compression, and writing. Dirty chunks are removed from the dirty set at
 * submit time and re-added on write failure, so modifications made between
 * submit and ack are never coalesced into a stale snapshot.
 *
 * <h3>Region read cache</h3>
 * <p>The IO thread keeps a per-region cache of compressed entries so each
 * region file is read from disk at most once per session. Subsequent saves
 * merge new entries into the in-memory map and rewrite the file without
 * re-reading.
 */
public final class ChunkCachePersistence {

  private static final byte[] MAGIC = {'F', 'X', 'M', 'P'};
  private static final byte FORMAT_V4 = 4;

  /** Short value representing surface layer in the file format. */
  private static final short FILE_SURFACE_LAYER = Short.MAX_VALUE;

  /** Chunks per region axis (32x32 = 1024 chunks per region). */
  private static final int REGION_SHIFT = 5;

  /** Maximum entries per region file (the count field is a u16). */
  private static final int MAX_ENTRIES_PER_REGION = 0xFFFF;

  // Non-daemon thread: JVM waits for pending writes on shutdown.
  // Lazily created and recreated after shutdown (dimension changes).
  private ExecutorService ioExecutor;

  // Chunks queued for the next save. Main-thread-only.
  private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();

  // The batch currently being written by the IO thread, or null if idle.
  // Main-thread-only field; the IO thread reads it indirectly via the lambda
  // capture below.
  private LongOpenHashSet inFlightBatch = null;

  // True while a write is queued/running on the IO thread. Volatile so the
  // main thread sees the IO thread's clear without further synchronization.
  // The single-threaded executor guarantees at most one in-flight write.
  private volatile boolean writeInFlight = false;

  // Set by the IO thread on a failed write. Volatile because the main thread
  // reads it on the next save cycle to re-merge inFlightBatch into dirtyChunks.
  // The volatile read of writeInFlight establishes happens-before for this field.
  private volatile boolean lastWriteFailed = false;

  // The active dimension. Replaced atomically by setDimension so any in-flight
  // write keeps using the dimension it was submitted against, even after a
  // dimension change. Each DimensionState carries its own regionCache, which
  // means old-dimension cache entries can't pollute the new dimension's reads.
  private volatile DimensionState dimension;

  /**
   * Per-dimension write state. Each instance is bound to one dimension's
   * directory and its own region cache. Replaced atomically on dimension
   * change so writes in flight keep using their original target.
   */
  private static final class DimensionState {
    final Path dir;
    final ConcurrentHashMap<Long, Map<Long, byte[]>> regionCache = new ConcurrentHashMap<>();

    DimensionState(Path dir) { this.dir = dir; }
  }

  /** Returns the IO executor, creating it if needed (after shutdown or first use). */
  private synchronized ExecutorService executor() {
    if (ioExecutor == null || ioExecutor.isShutdown()) {
      ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mapnhud-io");
        t.setDaemon(false);
        return t;
      });
    }
    return ioExecutor;
  }

  /** Sets the current dimension's cache directory. Allocates a fresh region cache. */
  public void setDimension(ClientLevel level) {
    Path base = resolveCacheBase();
    if (base == null) {
      dimension = null;
      return;
    }
    ResourceLocation dimId = level.dimension().location();
    String dimPath = dimId.getNamespace() + "/" + dimId.getPath();
    dimension = new DimensionState(base.resolve(dimPath));
  }

  /** Marks a chunk as dirty so all its layers will be written on the next save. */
  public void markDirty(int chunkX, int chunkZ) {
    dirtyChunks.add(ChunkPos.asLong(chunkX, chunkZ));
  }

  // -- Save --

  /**
   * Snapshots dirty chunks and submits writes to the IO thread.
   *
   * <p>Ownership model: chunks are removed from {@link #dirtyChunks} at submit
   * time and parked in {@link #inFlightBatch}. On success, the in-flight batch
   * is discarded. On failure, the next save cycle re-merges it back into
   * dirty so the work isn't lost.
   */
  public void saveDirty(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    DimensionState dim = dimension;
    if (dim == null) return;
    if (writeInFlight) return; // skip cycle; SAVE_INTERVAL >> typical write time
    handleLastWriteResult();
    if (dirtyChunks.isEmpty()) return;

    submitWrite(dim, snapshot(cache, dirtyChunks));
  }

  /** Snapshots all cached chunks and submits a full save. */
  public void saveAll(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    DimensionState dim = dimension;
    if (dim == null || cache.isEmpty()) return;
    if (writeInFlight) return;
    handleLastWriteResult();

    LongOpenHashSet allKeys = new LongOpenHashSet(dirtyChunks);
    for (long key : cache.keySet()) allKeys.add(key);
    if (allKeys.isEmpty()) return;

    submitWrite(dim, snapshot(cache, allKeys));
  }

  /**
   * Builds a write batch from the given key set, capturing immutable
   * {@link ChunkColorData} references on the main thread. The actual
   * serialization happens on the IO thread.
   */
  private PendingWrite snapshot(
      Long2ObjectOpenHashMap<CaveLayeredEntry> cache, LongOpenHashSet keys) {
    LongOpenHashSet batch = new LongOpenHashSet(keys);
    Map<Long, List<LayerSnapshot>> regionLayers = new HashMap<>();

    for (long chunkKey : batch) {
      CaveLayeredEntry entry = cache.get(chunkKey);
      if (entry == null) continue;
      int cx = ChunkPos.getX(chunkKey);
      int cz = ChunkPos.getZ(chunkKey);
      int rx = cx >> REGION_SHIFT;
      int rz = cz >> REGION_SHIFT;
      long regionKey = ChunkPos.asLong(rx, rz);
      int localX = cx & 0x1F;
      int localZ = cz & 0x1F;

      List<LayerSnapshot> layers = regionLayers.computeIfAbsent(regionKey, k -> new ArrayList<>());
      entry.forEachLayer((layerY, data) -> {
        short fileLayerY = toFileLayerY(layerY);
        long entryKey = packEntryKey(localX, localZ, fileLayerY);
        layers.add(new LayerSnapshot(entryKey, data));
      });
    }

    dirtyChunks.removeAll(batch);
    return new PendingWrite(batch, regionLayers);
  }

  private void submitWrite(DimensionState dim, PendingWrite pending) {
    inFlightBatch = pending.batch();
    Map<Long, List<LayerSnapshot>> regionLayers = pending.regionLayers();
    writeInFlight = true;
    executor().submit(() -> {
      boolean ok;
      try {
        ok = regionLayers.isEmpty() || writeRegions(dim, regionLayers);
      } catch (Throwable t) {
        MapnHudMod.LOG.error("Unexpected error during map cache write", t);
        ok = false;
      }
      lastWriteFailed = !ok;
      writeInFlight = false; // happens-after lastWriteFailed, see field comment
    });
  }

  /**
   * On the main thread, examines the result of the last completed write.
   * On failure, re-merges the in-flight batch back into dirty.
   */
  private void handleLastWriteResult() {
    if (inFlightBatch == null) return;
    if (lastWriteFailed) {
      dirtyChunks.addAll(inFlightBatch);
      lastWriteFailed = false;
    }
    inFlightBatch = null;
  }

  /** A single layer captured for serialization. The data ref is immutable. */
  private record LayerSnapshot(long entryKey, ChunkColorData data) {}

  /** Snapshot of a write that's about to be submitted. */
  private record PendingWrite(LongOpenHashSet batch, Map<Long, List<LayerSnapshot>> regionLayers) {}

  // -- Load --

  /**
   * Synchronously loads all region files for the current dimension.
   * Populates the given cache directly. Only v4 files are loaded.
   */
  public void loadDimension(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    DimensionState dim = dimension;
    if (dim == null || !Files.isDirectory(dim.dir)) return;

    int loaded = 0;
    int skipped = 0;
    try (var stream = Files.list(dim.dir)) {
      var files = stream.filter(p -> p.getFileName().toString().endsWith(".mapdata")).toList();
      for (Path regionFile : files) {
        LoadResult result = loadRegionFile(dim, regionFile, cache);
        switch (result.kind()) {
          case OK -> loaded += result.entryCount();
          case INCOMPATIBLE -> skipped++;
          case BAD_NAME, EMPTY -> { /* silently skip */ }
        }
      }
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to list map cache directory: {}", dim.dir, e);
    }

    if (skipped > 0) {
      MapnHudMod.LOG.warn("Skipped {} incompatible region files (non-v4 format)", skipped);
    }
    MapnHudMod.LOG.info("Loaded {} cached map chunk layers for {}", loaded, dim.dir);
  }

  /** Flushes pending writes and shuts down the IO thread with bounded wait. */
  public synchronized void shutdown() {
    if (ioExecutor == null || ioExecutor.isShutdown()) return;
    ioExecutor.shutdown();
    try {
      if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        MapnHudMod.LOG.warn("Map cache IO thread did not terminate in 10s, forcing shutdown");
        ioExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ioExecutor.shutdownNow();
    }
    dimension = null;
  }

  // -- Entry key packing --

  /** Packs localX (5 bits), localZ (5 bits), and layerY (16 bits) into a long. */
  private static long packEntryKey(int localX, int localZ, short fileLayerY) {
    return ((long) (localX & 0x1F) << 21) | ((long) (localZ & 0x1F) << 16) | (fileLayerY & 0xFFFFL);
  }

  private static int entryLocalX(long entryKey) { return (int) ((entryKey >> 21) & 0x1F); }
  private static int entryLocalZ(long entryKey) { return (int) ((entryKey >> 16) & 0x1F); }
  private static short entryFileLayerY(long entryKey) { return (short) (entryKey & 0xFFFF); }

  private static short toFileLayerY(int layerY) {
    return layerY == CaveLayeredEntry.SURFACE_LAYER
        ? FILE_SURFACE_LAYER : (short) layerY;
  }

  private static int fromFileLayerY(short fileLayerY) {
    return fileLayerY == FILE_SURFACE_LAYER
        ? CaveLayeredEntry.SURFACE_LAYER : (int) fileLayerY;
  }

  // -- Region file I/O (IO thread only) --

  /**
   * Writes the given per-region layer batches to disk. Runs on the IO thread.
   * For each touched region, fetches (or first-time-loads) the region's
   * compressed entry map from the dimension's region cache, merges the new
   * entries, then writes the merged map to disk.
   */
  private static boolean writeRegions(DimensionState dim, Map<Long, List<LayerSnapshot>> regionLayers) {
    try {
      Files.createDirectories(dim.dir);
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to create map cache directory: {}", dim.dir, e);
      return false;
    }

    boolean allOk = true;
    for (var regionEntry : regionLayers.entrySet()) {
      long regionKey = regionEntry.getKey();
      int rx = ChunkPos.getX(regionKey);
      int rz = ChunkPos.getZ(regionKey);
      Path regionPath = dim.dir.resolve("r." + rx + "." + rz + ".mapdata");

      Map<Long, byte[]> merged = dim.regionCache.computeIfAbsent(
          regionKey, k -> readRegionEntries(regionPath));

      // Encode + compress new entries on the IO thread, not on the game thread.
      for (LayerSnapshot snap : regionEntry.getValue()) {
        try {
          byte[] raw = ChunkColorDataCodec.toBytes(snap.data());
          merged.put(snap.entryKey(), gzipCompress(raw));
        } catch (IOException ex) {
          MapnHudMod.LOG.warn("Failed to encode chunk layer in region ({}, {})", rx, rz, ex);
        }
      }

      if (!writeRegionFile(regionPath, merged)) {
        allOk = false;
      }
    }
    return allOk;
  }

  /**
   * Reads all entries from a v4 region file. Returns an empty map for any
   * failure (missing file, bad magic, wrong version, IO error).
   */
  private static Map<Long, byte[]> readRegionEntries(Path path) {
    if (!Files.exists(path)) return new HashMap<>();
    try {
      byte[] fileData = Files.readAllBytes(path);
      ParsedHeader header = parseHeader(fileData);
      if (header == null || header.version() != FORMAT_V4) return new HashMap<>();
      return parseEntries(fileData, header);
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to read region file: {}", path, e);
      return new HashMap<>();
    }
  }

  /**
   * Parses the magic+version+count header. Returns null if magic doesn't match
   * or the file is too short. Does NOT validate the version against
   * {@link #FORMAT_V4} — callers decide whether non-v4 is fatal.
   */
  private static ParsedHeader parseHeader(byte[] fileData) {
    if (fileData.length < 7) return null;
    for (int i = 0; i < MAGIC.length; i++) {
      if (fileData[i] != MAGIC[i]) return null;
    }
    int version = fileData[4] & 0xFF;
    int count = ((fileData[5] & 0xFF) << 8) | (fileData[6] & 0xFF);
    return new ParsedHeader(version, count, 7);
  }

  private record ParsedHeader(int version, int entryCount, int bodyOffset) {}

  /**
   * Writes all entries to a region file atomically. Values are pre-compressed
   * GZIP payloads written directly. Returns true on success.
   */
  private static boolean writeRegionFile(Path path, Map<Long, byte[]> entries) {
    if (entries.isEmpty()) return true;

    int totalEntries = entries.size();
    int count = Math.min(totalEntries, MAX_ENTRIES_PER_REGION);
    if (totalEntries > MAX_ENTRIES_PER_REGION) {
      MapnHudMod.LOG.warn(
          "Region file {} has {} entries, exceeding the {}-entry limit. Truncating.",
          path.getFileName(), totalEntries, MAX_ENTRIES_PER_REGION);
    }

    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      out.write(MAGIC);
      out.write(FORMAT_V4);
      out.write((count >> 8) & 0xFF);
      out.write(count & 0xFF);

      int written = 0;
      for (var entry : entries.entrySet()) {
        if (written >= count) break;

        long entryKey = entry.getKey();
        byte[] compressed = entry.getValue();

        int localX = entryLocalX(entryKey);
        int localZ = entryLocalZ(entryKey);
        short fileLayerY = entryFileLayerY(entryKey);

        out.write(localX);
        out.write(localZ);
        out.write((fileLayerY >> 8) & 0xFF);
        out.write(fileLayerY & 0xFF);
        out.write(0); // flags (reserved)
        out.write((compressed.length >> 24) & 0xFF);
        out.write((compressed.length >> 16) & 0xFF);
        out.write((compressed.length >> 8) & 0xFF);
        out.write(compressed.length & 0xFF);
        out.write(compressed);
        written++;
      }

      out.flush();
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return true;
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to write region file: {}", path, e);
      try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
      return false;
    }
  }

  /**
   * Loads a v4 region file and puts entries into the layered cache.
   * Single-pass: parses the header once and dispatches by version, with no
   * second {@code Files.readAllBytes} for the version check.
   */
  private LoadResult loadRegionFile(DimensionState dim, Path regionFile,
                                    Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    String name = regionFile.getFileName().toString();
    String[] parts = name.replace(".mapdata", "").split("\\.");
    if (parts.length != 3 || !parts[0].equals("r")) return LoadResult.badName();

    int rx, rz;
    try {
      rx = Integer.parseInt(parts[1]);
      rz = Integer.parseInt(parts[2]);
    } catch (NumberFormatException e) {
      return LoadResult.badName();
    }

    byte[] fileData;
    try {
      fileData = Files.readAllBytes(regionFile);
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to read region file: {}", regionFile, e);
      return LoadResult.empty();
    }

    ParsedHeader header = parseHeader(fileData);
    if (header == null) return LoadResult.empty();
    if (header.version() != FORMAT_V4) return LoadResult.incompatible();

    Map<Long, byte[]> entries = parseEntries(fileData, header);
    if (entries.isEmpty()) return LoadResult.empty();

    // Cache the parsed entries so the first save in this session doesn't re-read.
    long regionKey = ChunkPos.asLong(rx, rz);
    dim.regionCache.put(regionKey, new HashMap<>(entries));

    int loaded = 0;
    for (var entry : entries.entrySet()) {
      long entryKey = entry.getKey();
      int localX = entryLocalX(entryKey);
      int localZ = entryLocalZ(entryKey);
      short fileLayerY = entryFileLayerY(entryKey);
      int layerY = fromFileLayerY(fileLayerY);

      int cx = (rx << REGION_SHIFT) | localX;
      int cz = (rz << REGION_SHIFT) | localZ;

      try {
        byte[] decompressed = gzipDecompress(entry.getValue());
        ChunkColorData data = ChunkColorDataCodec.fromBytes(decompressed);

        long chunkKey = ChunkPos.asLong(cx, cz);
        CaveLayeredEntry layered = cache.get(chunkKey);
        if (layered == null) {
          layered = new CaveLayeredEntry();
          cache.put(chunkKey, layered);
        }
        layered.put(layerY, data);
        loaded++;
      } catch (Exception e) {
        MapnHudMod.LOG.warn("Corrupted chunk data at ({}, {}) layer {} in {}, skipping",
            cx, cz, layerY, regionFile, e);
      }
    }
    return LoadResult.ok(loaded);
  }

  /** Parses the entry table given an already-validated header. */
  private static Map<Long, byte[]> parseEntries(byte[] fileData, ParsedHeader header) {
    Map<Long, byte[]> entries = new HashMap<>();
    int pos = header.bodyOffset();
    int count = header.entryCount();
    for (int i = 0; i < count; i++) {
      if (pos + 9 > fileData.length) break;
      int localX = fileData[pos++] & 0xFF;
      int localZ = fileData[pos++] & 0xFF;
      short fileLayerY = (short) (((fileData[pos++] & 0xFF) << 8) | (fileData[pos++] & 0xFF));
      pos++; // flags
      int payloadLen = readInt(fileData, pos); pos += 4;
      if (payloadLen < 0 || pos + payloadLen > fileData.length) break;

      byte[] payload = new byte[payloadLen];
      System.arraycopy(fileData, pos, payload, 0, payloadLen);
      pos += payloadLen;

      long entryKey = packEntryKey(localX, localZ, fileLayerY);
      entries.put(entryKey, payload);
    }
    return entries;
  }

  /** Result of trying to load one region file. */
  private record LoadResult(Kind kind, int entryCount) {
    enum Kind { OK, INCOMPATIBLE, BAD_NAME, EMPTY }
    static LoadResult ok(int n) { return new LoadResult(Kind.OK, n); }
    static LoadResult incompatible() { return new LoadResult(Kind.INCOMPATIBLE, 0); }
    static LoadResult badName() { return new LoadResult(Kind.BAD_NAME, 0); }
    static LoadResult empty() { return new LoadResult(Kind.EMPTY, 0); }
  }

  // -- Path resolution --

  private static Path resolveCacheBase() {
    Minecraft mc = Minecraft.getInstance();
    Path gameDir = mc.gameDirectory.toPath();

    ServerData server = mc.getCurrentServer();
    if (server != null) {
      String safeName = server.ip.replaceAll("[^a-zA-Z0-9._-]", "_");
      return gameDir.resolve("mapnhud_cache").resolve("servers").resolve(safeName);
    }

    MinecraftServer singleplayer = mc.getSingleplayerServer();
    if (singleplayer != null) {
      // normalize() strips the "." from LevelResource.ROOT, giving the world folder
      String worldName = singleplayer.getWorldPath(
          net.minecraft.world.level.storage.LevelResource.ROOT)
          .normalize().getFileName().toString();
      return gameDir.resolve("mapnhud_cache").resolve(worldName);
    }

    return null;
  }

  // -- Utilities --

  private static int readInt(byte[] buf, int pos) {
    return ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
        | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
  }

  private static byte[] gzipCompress(byte[] data) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
    try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
      gzip.write(data);
    }
    return baos.toByteArray();
  }

  private static byte[] gzipDecompress(byte[] compressed) throws IOException {
    try (GZIPInputStream gzip = new GZIPInputStream(
        new java.io.ByteArrayInputStream(compressed))) {
      return gzip.readAllBytes();
    }
  }
}
