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
import java.util.HashMap;
import java.util.Map;
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
 * <p>Dirty chunks are snapshotted on the main thread. The IO thread writes
 * them asynchronously. Dirty entries are only cleared after the IO thread
 * confirms a successful write. On write failure, entries stay dirty for
 * retry on the next save cycle.
 */
public final class ChunkCachePersistence {

  private static final byte[] MAGIC = {'F', 'X', 'M', 'P'};
  private static final byte FORMAT_V4 = 4;

  /** Short value representing surface layer in the file format. */
  private static final short FILE_SURFACE_LAYER = Short.MAX_VALUE;

  /** Chunks per region axis (32x32 = 1024 chunks per region). */
  private static final int REGION_SHIFT = 5;

  // Non-daemon thread: JVM waits for pending writes on shutdown.
  // Lazily created and recreated after shutdown (dimension changes).
  private ExecutorService ioExecutor;

  private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();

  // Completed batch from the IO thread. Volatile for cross-thread visibility.
  // Main thread reads and clears; IO thread sets on successful write.
  private volatile LongOpenHashSet completedBatch = null;

  private Path dimensionDir;

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

  /** Sets the current dimension's cache directory. */
  public void setDimension(ClientLevel level) {
    Path base = resolveCacheBase();
    if (base == null) {
      dimensionDir = null;
      return;
    }
    ResourceLocation dimId = level.dimension().location();
    String dimPath = dimId.getNamespace() + "/" + dimId.getPath();
    dimensionDir = base.resolve(dimPath);
  }

  /** Marks a chunk as dirty so all its layers will be written on the next save. */
  public void markDirty(int chunkX, int chunkZ) {
    dirtyChunks.add(ChunkPos.asLong(chunkX, chunkZ));
  }

  /**
   * Snapshots dirty chunks and submits writes to the IO thread.
   * Only clears dirty entries after the previous batch confirmed success.
   */
  public void saveDirty(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    if (dimensionDir == null) return;

    // Acknowledge completed batch from previous IO cycle
    acknowledgePreviousBatch();

    if (dirtyChunks.isEmpty()) return;

    LongOpenHashSet batch = new LongOpenHashSet(dirtyChunks);
    Map<Long, Map<Long, byte[]>> regionSnapshots = new HashMap<>();

    for (long key : batch) {
      CaveLayeredEntry entry = cache.get(key);
      if (entry == null) continue;
      snapshotEntry(key, entry, regionSnapshots);
    }

    if (regionSnapshots.isEmpty()) {
      dirtyChunks.removeAll(batch);
      return;
    }

    Path dir = dimensionDir;
    executor().submit(() -> {
      boolean ok = writeRegions(dir, regionSnapshots);
      if (ok) {
        completedBatch = batch;
      }
      // On failure: batch stays in dirtyChunks for retry next cycle
    });
  }

  /**
   * Snapshots all cached chunks and submits a full save. Dirty entries are
   * only cleared after write success, matching saveDirty() durability.
   */
  public void saveAll(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    if (dimensionDir == null || cache.isEmpty()) return;

    acknowledgePreviousBatch();

    LongOpenHashSet batch = new LongOpenHashSet(dirtyChunks);
    // Also include all cache keys (saveAll saves everything, not just dirty)
    for (long key : cache.keySet()) {
      batch.add(key);
    }

    Map<Long, Map<Long, byte[]>> regionSnapshots = new HashMap<>();
    for (var entry : cache.long2ObjectEntrySet()) {
      snapshotEntry(entry.getLongKey(), entry.getValue(), regionSnapshots);
    }

    Path dir = dimensionDir;
    executor().submit(() -> {
      boolean ok = writeRegions(dir, regionSnapshots);
      if (ok) {
        completedBatch = batch;
      }
    });
  }

  /** Clears dirty entries that the IO thread confirmed were written. */
  private void acknowledgePreviousBatch() {
    LongOpenHashSet completed = completedBatch;
    if (completed != null) {
      dirtyChunks.removeAll(completed);
      completedBatch = null;
    }
  }

  /** Snapshots all layers of a chunk entry into the region snapshots map. */
  private void snapshotEntry(long chunkKey, CaveLayeredEntry entry,
                             Map<Long, Map<Long, byte[]>> regionSnapshots) {
    int cx = ChunkPos.getX(chunkKey);
    int cz = ChunkPos.getZ(chunkKey);
    int rx = cx >> REGION_SHIFT;
    int rz = cz >> REGION_SHIFT;
    long regionKey = ChunkPos.asLong(rx, rz);
    int localX = cx & 0x1F;
    int localZ = cz & 0x1F;

    entry.forEachLayer((layerY, data) -> {
      short fileLayerY = toFileLayerY(layerY);
      long entryKey = packEntryKey(localX, localZ, fileLayerY);
      regionSnapshots.computeIfAbsent(regionKey, k -> new HashMap<>())
          .put(entryKey, ChunkColorDataCodec.toBytes(data));
    });
  }

  /**
   * Synchronously loads all region files for the current dimension.
   * Populates the given cache directly. Only v4 files are loaded.
   */
  public void loadDimension(Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    if (dimensionDir == null || !Files.isDirectory(dimensionDir)) return;

    int loaded = 0;
    int skipped = 0;
    try (var stream = Files.list(dimensionDir)) {
      var files = stream.filter(p -> p.getFileName().toString().endsWith(".mapdata")).toList();
      for (Path regionFile : files) {
        int result = loadRegionFile(regionFile, cache);
        if (result >= 0) {
          loaded += result;
        } else {
          skipped++;
        }
      }
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to list map cache directory: {}", dimensionDir, e);
    }

    if (skipped > 0) {
      MapnHudMod.LOG.warn("Skipped {} incompatible region files (non-v4 format)", skipped);
    }
    MapnHudMod.LOG.info("Loaded {} cached map chunk layers for {}", loaded, dimensionDir);
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

  // -- Region file I/O --

  /** Writes region snapshots to disk. Returns true on full success. */
  private boolean writeRegions(Path dir, Map<Long, Map<Long, byte[]>> regionSnapshots) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to create map cache directory: {}", dir, e);
      return false;
    }

    boolean allOk = true;
    for (var regionEntry : regionSnapshots.entrySet()) {
      long regionKey = regionEntry.getKey();
      int rx = ChunkPos.getX(regionKey);
      int rz = ChunkPos.getZ(regionKey);
      Path regionPath = dir.resolve("r." + rx + "." + rz + ".mapdata");

      // Read-modify-write: keep existing entries as compressed bytes,
      // compress new entries, then write all as pre-compressed data.
      Map<Long, byte[]> merged = readRegionEntries(regionPath);

      // Compress new raw entries and overwrite into the merged map
      for (var e : regionEntry.getValue().entrySet()) {
        try {
          merged.put(e.getKey(), gzipCompress(e.getValue()));
        } catch (IOException ex) {
          // Skip entries that fail to compress
        }
      }

      if (!writeRegionFile(regionPath, merged)) {
        allOk = false;
      }
    }
    return allOk;
  }

  /** Reads all entries from a v4 region file. Returns entryKey -> compressed payload. */
  private Map<Long, byte[]> readRegionEntries(Path path) {
    Map<Long, byte[]> entries = new HashMap<>();
    if (!Files.exists(path)) return entries;

    try {
      byte[] fileData = Files.readAllBytes(path);
      int pos = 0;

      if (fileData.length < 7) return entries;
      for (byte b : MAGIC) {
        if (fileData[pos++] != b) return entries;
      }

      int version = fileData[pos++] & 0xFF;
      if (version != FORMAT_V4) {
        // Reject non-v4 files
        return entries;
      }

      int count = ((fileData[pos++] & 0xFF) << 8) | (fileData[pos++] & 0xFF);

      for (int i = 0; i < count; i++) {
        // v4 entry: localX(1) + localZ(1) + layerY(2) + flags(1) + payloadLen(4) = 9 bytes header
        if (pos + 9 > fileData.length) break;
        int localX = fileData[pos++] & 0xFF;
        int localZ = fileData[pos++] & 0xFF;
        short fileLayerY = (short) (((fileData[pos++] & 0xFF) << 8) | (fileData[pos++] & 0xFF));
        @SuppressWarnings("unused")
        int flags = fileData[pos++] & 0xFF; // reserved for future use
        int payloadLen = readInt(fileData, pos); pos += 4;
        if (payloadLen < 0 || pos + payloadLen > fileData.length) break;

        byte[] payload = new byte[payloadLen];
        System.arraycopy(fileData, pos, payload, 0, payloadLen);
        pos += payloadLen;

        long entryKey = packEntryKey(localX, localZ, fileLayerY);
        entries.put(entryKey, payload);
      }
    } catch (IOException e) {
      MapnHudMod.LOG.warn("Failed to read region file: {}", path, e);
    }
    return entries;
  }

  /**
   * Writes all entries to a region file atomically. Values are pre-compressed
   * GZIP payloads written directly. Returns true on success.
   *
   * <p>Header count always matches the actual number of entries written,
   * fixing the v2 bug where clamped count diverged from iteration count.
   */
  private boolean writeRegionFile(Path path, Map<Long, byte[]> entries) {
    if (entries.isEmpty()) return true;

    int count = Math.min(entries.size(), 0xFFFF);

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
   * Returns the number of entries loaded, or -1 if the file is incompatible.
   */
  private int loadRegionFile(Path regionFile,
                             Long2ObjectOpenHashMap<CaveLayeredEntry> cache) {
    String name = regionFile.getFileName().toString();
    String[] parts = name.replace(".mapdata", "").split("\\.");
    if (parts.length != 3 || !parts[0].equals("r")) return 0;

    int rx, rz;
    try {
      rx = Integer.parseInt(parts[1]);
      rz = Integer.parseInt(parts[2]);
    } catch (NumberFormatException e) {
      return 0;
    }

    // Check version before full read
    Map<Long, byte[]> entries = readRegionEntries(regionFile);
    if (entries.isEmpty()) {
      // Could be empty file or incompatible version
      if (Files.exists(regionFile)) {
        try {
          byte[] header = Files.readAllBytes(regionFile);
          if (header.length >= 5 && header[4] != FORMAT_V4) {
            return -1; // incompatible version
          }
        } catch (IOException ignored) {}
      }
      return 0;
    }

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
    return loaded;
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
