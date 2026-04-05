package dev.mapnhud.client.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Layered cache of raw per-chunk column data for the minimap.
 *
 * <p>Each (chunkX, chunkZ) position can hold multiple layers: one surface
 * layer and zero or more cave layers at different Y depths. The assembler
 * sees only the active layer for the current mode and player Y.
 *
 * <p>Cave layers use deterministic 16-block bucket keys:
 * {@code floorDiv(y, CAVE_BUCKET) * CAVE_BUCKET}. This eliminates the
 * drift and fragmentation that threshold-based merging caused.
 * Each chunk position holds at most {@link LayeredEntry#MAX_CAVE_LAYERS}
 * cave layers plus one surface layer.
 */
public final class ChunkColorCache {

  /** Chunks to process per tick from the event queue (ChunkEvent.Load arrivals). */
  private static final int CHUNKS_PER_TICK = 32;

  /**
   * Chunks to process per tick from the reflood queue. Higher than the
   * event queue because cave scans for wall-only chunks are very cheap
   * (early null return) so the effective cost is much lower than the count.
   */
  private static final int REFLOOD_CHUNKS_PER_TICK = 128;

  /** Time budget for event queue processing per tick. */
  private static final long SCAN_BUDGET_NANOS = 2_000_000L; // 2ms

  /** Time budget for reflood queue processing per tick. */
  private static final long REFLOOD_BUDGET_NANOS = 4_000_000L; // 4ms
  private static final int RESCAN_PLAYER_CHUNK_INTERVAL = 4;
  private static final int RESCAN_ADJACENT_INTERVAL = 20;

  /** How far the player must move (XZ) before cave flood is recomputed. */
  private static final int CAVE_REFLOOD_DISTANCE = 3;

  /** Y distance that triggers cave reflood (handles vertical movement). */
  private static final int CAVE_REFLOOD_Y_THRESHOLD = 4;

  /** Minimum ticks between refloods to prevent flood storms. */
  private static final int FLOOD_COOLDOWN_TICKS = 10;

  /** Save dirty chunks to disk every 60 seconds. */
  private static final int SAVE_INTERVAL = 1200;

  private final Long2ObjectOpenHashMap<LayeredEntry> cache = new Long2ObjectOpenHashMap<>();
  private final ArrayDeque<ChunkAccess> scanQueue = new ArrayDeque<>();      // ChunkEvent.Load arrivals
  private final ArrayDeque<ChunkAccess> refloodQueue = new ArrayDeque<>();   // post-reflood cave scans
  private final LongOpenHashSet refloodQueued = new LongOpenHashSet();       // dedup set for refloodQueue
  private final ChunkCachePersistence persistence = new ChunkCachePersistence();
  private int tickCounter = 0;
  private boolean dirty = false;

  // Cave mode state
  private BlockPos playerPos = BlockPos.ZERO;
  private boolean caveMode = false;
  private CaveFloodFill.Result floodResult = CaveFloodFill.EMPTY;
  private int lastFloodX = Integer.MIN_VALUE;
  private int lastFloodY = Integer.MIN_VALUE;
  private int lastFloodZ = Integer.MIN_VALUE;
  private int lastFloodTick = -FLOOD_COOLDOWN_TICKS;

  // Per-tick scan parameters (set at top of tick from config)
  private int scanRadiusChunks = 12;
  private int caveScanRadiusChunks = 12;
  private int caveFloodRadiusBlocks = 100;

  // -- Layered entry --

  static class LayeredEntry {
    static final int SURFACE_LAYER = Integer.MAX_VALUE;
    static final int MAX_CAVE_LAYERS = 4;

    private final ChunkColorData[] layers = new ChunkColorData[MAX_CAVE_LAYERS + 1];
    private final int[] layerYs = new int[MAX_CAVE_LAYERS + 1];
    private int count = 0;
    private int chunkVersion = 0;

    // Composite view caching to avoid per-frame allocations
    private ChunkColorData cachedComposite;
    private int cachedBucketY = Integer.MIN_VALUE;
    private int cachedVersion = -1;

    LayeredEntry() {
      Arrays.fill(layerYs, Integer.MIN_VALUE);
    }

    static int caveBucketKey(int y) {
      return Math.floorDiv(y, 16) * 16;
    }

    ChunkColorData getSurface() {
      return getAtBucket(SURFACE_LAYER);
    }

    /**
     * Returns a composite cave view that fills unknown columns from adjacent
     * layers. Uses the target bucket as primary, then fills gaps from other
     * cave layers nearest-first.
     */
    ChunkColorData getCaveComposite(int playerY) {
      int bucketY = caveBucketKey(playerY);
      
      // Return cached composite if valid
      if (cachedComposite != null && cachedBucketY == bucketY && cachedVersion == chunkVersion) {
        return cachedComposite;
      }

      ChunkColorData primary = getAtBucket(bucketY);
      if (primary == null) {
        primary = getCaveNearest(playerY);
        if (primary == null) return null;
      }

      // If fully known, return unchanged (no allocation)
      if (primary.knownCount() == ChunkColorData.PIXELS) {
        cachedComposite = primary;
      } else {
        // Fill gaps from other cave layers, nearest-first.
        // Max 4 cave layers so inline nearest selection beats List+sort.
        ChunkColorData result = primary;
        boolean[] used = null;

        for (int pass = 0; pass < MAX_CAVE_LAYERS; pass++) {
          int bestIdx = -1;
          int bestDist = Integer.MAX_VALUE;
          for (int i = 0; i < count; i++) {
            if (layerYs[i] == SURFACE_LAYER || layerYs[i] == bucketY) continue;
            if (used != null && used[i]) continue;
            int dist = Math.abs(layerYs[i] - playerY);
            if (dist < bestDist) {
              bestDist = dist;
              bestIdx = i;
            }
          }
          if (bestIdx < 0) break;

          if (used == null) used = new boolean[count];
          used[bestIdx] = true;
          result = ChunkColorData.fillGaps(result, layers[bestIdx]);
          if (result.knownCount() == ChunkColorData.PIXELS) break;
        }
        cachedComposite = result;
      }
      
      cachedBucketY = bucketY;
      cachedVersion = chunkVersion;
      return cachedComposite;
    }

    ChunkColorData getAtBucket(int bucketY) {
      for (int i = 0; i < count; i++) {
        if (layerYs[i] == bucketY) return layers[i];
      }
      return null;
    }

    void put(int bucketY, ChunkColorData data) {
      chunkVersion++;
      for (int i = 0; i < count; i++) {
        if (layerYs[i] == bucketY) {
          layers[i] = data;
          return;
        }
      }

      if (count < layers.length) {
        layerYs[count] = bucketY;
        layers[count] = data;
        count++;
      }
    }

    void evictFarthestCave(int playerY) {
      int farthestIdx = -1;
      int maxDist = -1;

      for (int i = 0; i < count; i++) {
        if (layerYs[i] == SURFACE_LAYER) continue;
        int dist = Math.abs(layerYs[i] - playerY);
        if (dist > maxDist) {
          maxDist = dist;
          farthestIdx = i;
        }
      }

      if (farthestIdx != -1) {
        chunkVersion++;
        // Shift remaining layers
        for (int i = farthestIdx; i < count - 1; i++) {
          layerYs[i] = layerYs[i + 1];
          layers[i] = layers[i + 1];
        }
        count--;
        layerYs[count] = Integer.MIN_VALUE;
        layers[count] = null;
      }
    }

    ChunkColorData getCaveNearest(int targetY) {
      ChunkColorData best = null;
      int minDist = Integer.MAX_VALUE;

      for (int i = 0; i < count; i++) {
        if (layerYs[i] == SURFACE_LAYER) continue;
        int dist = Math.abs(layerYs[i] - targetY);
        if (dist < minDist) {
          minDist = dist;
          best = layers[i];
        }
      }
      return best;
    }

    int count() { return count; }
    int layerY(int i) { return layerYs[i]; }
    ChunkColorData data(int i) { return layers[i]; }
  }

  // -- Public API --

  public void enqueueChunk(ChunkAccess chunk) {
    scanQueue.addLast(chunk);
  }

  public void clearAll() {
    cache.clear();
    scanQueue.clear();
    refloodQueue.clear();
    refloodQueued.clear();
    tickCounter = 0;
    dirty = true;
  }

  /** Sets the dimension for persistence and loads persisted data into cache. */
  public void loadFromDisk(ClientLevel level) {
    persistence.setDimension(level);
    persistence.loadDimension(cache);
    dirty = true;
  }

  /** Saves dirty chunks to disk in the background. */
  public void saveToDisk() {
    persistence.saveDirty(cache);
  }

  /** Saves all cached chunks to disk in the background. */
  public void saveAllToDisk() {
    persistence.saveAll(cache);
  }

  /** Flushes pending writes and shuts down the IO thread. */
  public void shutdown() {
    persistence.shutdown();
  }

  /** Returns the last flood fill result (for debug/overlay display). */
  public CaveFloodFill.Result getFloodResult() {
    return floodResult;
  }

  /** Returns whether cave mode is currently active. */
  public boolean isCaveMode() {
    return caveMode;
  }

  /**
   * Lookup by chunk coords. Returns the appropriate layer for the current
   * mode: surface layer in surface mode, composite cave view in cave mode.
   * The composite fills unknown columns from adjacent cave layers so that
   * known terrain from any layer is never hidden behind black walls.
   */
  public ChunkColorData get(int chunkX, int chunkZ) {
    LayeredEntry entry = cache.get(ChunkPos.asLong(chunkX, chunkZ));
    if (entry == null) return null;
    return caveMode
        ? entry.getCaveComposite(playerPos.getY())
        : entry.getSurface();
  }


  // -- Tick --

  public boolean tick(Level level, BlockPos playerPos, boolean caveMode,
                      int scanRadiusChunks, int caveScanRadiusChunks, int caveFloodRadiusBlocks) {
    this.playerPos = playerPos;
    this.scanRadiusChunks = scanRadiusChunks;
    this.caveScanRadiusChunks = caveScanRadiusChunks;
    this.caveFloodRadiusBlocks = caveFloodRadiusBlocks;

    boolean modeChanged = caveMode != this.caveMode;
    this.caveMode = caveMode;

    if (modeChanged) {
      tickCounter = 0;
      floodResult = CaveFloodFill.EMPTY;
      lastFloodX = Integer.MIN_VALUE;
      lastFloodTick = -FLOOD_COOLDOWN_TICKS;
      scanQueue.clear();
      refloodQueue.clear();
      refloodQueued.clear();
      // No cache clear: layers handle mode separation.
      // get() returns the appropriate layer for the current mode.

      if (!caveMode) {
        // Leaving cave mode: rescan 3x3 with surface mode for immediate view.
        rescanImmediate(level, playerPos);
      }
      // Entering cave mode: maybeReflood below will flood + enqueue scans
    }

    boolean reflooded = false;
    if (caveMode) {
      reflooded = maybeReflood(level, playerPos);
    }

    dirty = modeChanged || reflooded;
    processQueue(level);
    processRefloodQueue(level);
    if (!reflooded) {
      handlePeriodicRescan(level, playerPos);
    }

    if (tickCounter % SAVE_INTERVAL == 0 && tickCounter > 0) {
      persistence.saveDirty(cache);
    }

    tickCounter++;
    return dirty;
  }

  // -- Internal scan/put --

  /** Returns the deterministic layer Y key for the current mode. */
  private int currentLayerY() {
    return caveMode
        ? LayeredEntry.caveBucketKey(playerPos.getY())
        : LayeredEntry.SURFACE_LAYER;
  }

  /**
   * Puts data into the layered cache and marks it dirty for persistence.
   * Null data is ignored (scanCave returns null for all-unknown chunks).
   */
  private void putData(int chunkX, int chunkZ, ChunkColorData data) {
    if (data == null) return;
    long key = ChunkPos.asLong(chunkX, chunkZ);
    LayeredEntry entry = cache.get(key);

    if (entry == null) {
      entry = new LayeredEntry();
      cache.put(key, entry);
    }

    int layerY = currentLayerY();

    // In cave mode, merge with existing data so previously explored
    // columns are preserved (additive cave mapping)
    ChunkColorData existingAtBucket = entry.getAtBucket(layerY);
    if (caveMode && data.isCaveData()) {
      if (existingAtBucket != null && existingAtBucket.isCaveData()) {
        data = ChunkColorData.mergeCave(existingAtBucket, data);
      } else {
        // New bucket: seed from nearest existing cave layer so exploration
        // history carries across bucket boundaries.
        ChunkColorData nearest = entry.getCaveNearest(playerPos.getY());
        if (nearest != null && nearest.isCaveData()) {
          data = ChunkColorData.mergeCave(nearest, data);
        }
      }
    }

    // Evict farthest cave layer if at cap before inserting a new bucket
    if (layerY != LayeredEntry.SURFACE_LAYER
        && existingAtBucket == null) {
      int caveCount = 0;
      for (int i = 0; i < entry.count(); i++) {
        if (entry.layerY(i) != LayeredEntry.SURFACE_LAYER) caveCount++;
      }
      if (caveCount >= LayeredEntry.MAX_CAVE_LAYERS) {
        entry.evictFarthestCave(playerPos.getY());
      }
    }

    entry.put(layerY, data);
    persistence.markDirty(chunkX, chunkZ);
    dirty = true;
  }

  /** Scans a chunk using the current mode (surface or cave). */
  private ChunkColorData scanChunk(ChunkAccess chunk, Level level) {
    return caveMode
        ? ChunkScanner.scanCave(chunk, level, floodResult)
        : ChunkScanner.scan(chunk, level);
  }

  // -- Flood fill --

  private boolean maybeReflood(Level level, BlockPos playerPos) {
    int px = playerPos.getX();
    int py = playerPos.getY();
    int pz = playerPos.getZ();

    int dx = px - lastFloodX;
    int dy = py - lastFloodY;
    int dz = pz - lastFloodZ;
    int distXZSq = dx * dx + dz * dz;

    boolean needsFlood = lastFloodX == Integer.MIN_VALUE
        || distXZSq > CAVE_REFLOOD_DISTANCE * CAVE_REFLOOD_DISTANCE
        || Math.abs(dy) > CAVE_REFLOOD_Y_THRESHOLD;

    if (!needsFlood) return false;

    // Cooldown: prevent rapid flood storms during fast movement
    if (tickCounter - lastFloodTick < FLOOD_COOLDOWN_TICKS
        && lastFloodX != Integer.MIN_VALUE) {
      return false;
    }

    floodResult = CaveFloodFill.flood(level, playerPos, caveFloodRadiusBlocks);
    lastFloodX = px;
    lastFloodY = py;
    lastFloodZ = pz;
    lastFloodTick = tickCounter;

    // Immediate 3x3 sync scan for instant visible area.
    // Don't clear the reflood queue: let existing items drain naturally.
    // They'll use the updated floodResult automatically at scan time.
    // Only enqueue chunks that don't already have current-bucket data.
    rescanImmediate(level, playerPos);
    enqueueFloodRadius(level, playerPos);
    return true;
  }

  // -- Scan methods --

  /** Processes chunks from ChunkEvent.Load at a steady rate. */
  private void processQueue(Level level) {
    int processed = 0;
    long startTime = System.nanoTime();

    while (processed < CHUNKS_PER_TICK && !scanQueue.isEmpty()) {
      if (processed > 0 && System.nanoTime() - startTime > SCAN_BUDGET_NANOS) break;

      ChunkAccess chunk = scanQueue.pollFirst();
      if (chunk == null) break;
      LevelChunk full = asLevelChunk(chunk, level);
      if (full == null) {
        scanQueue.addLast(chunk);
        processed++;
        continue;
      }

      putData(full.getPos().x, full.getPos().z, scanChunk(full, level));
      processed++;
    }
  }

  /**
   * Processes chunks from post-reflood cave scans with a higher budget.
   * Most cave chunks outside the flood radius return null (all-unknown),
   * so the effective cost per chunk is very low.
   */
  private void processRefloodQueue(Level level) {
    if (refloodQueue.isEmpty()) return;
    int processed = 0;
    long startTime = System.nanoTime();

    while (processed < REFLOOD_CHUNKS_PER_TICK && !refloodQueue.isEmpty()) {
      if (processed > 0 && System.nanoTime() - startTime > REFLOOD_BUDGET_NANOS) break;

      ChunkAccess chunk = refloodQueue.pollFirst();
      if (chunk == null) break;
      refloodQueued.remove(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z));
      LevelChunk full = asLevelChunk(chunk, level);
      if (full == null) { processed++; continue; }

      putData(full.getPos().x, full.getPos().z, scanChunk(full, level));
      processed++;
    }
  }

  /** Resolves a ChunkAccess to a LevelChunk, or null if not yet loaded. */
  private static LevelChunk asLevelChunk(ChunkAccess chunk, Level level) {
    if (chunk instanceof LevelChunk lc) return lc;
    ChunkPos pos = chunk.getPos();
    if (level.hasChunk(pos.x, pos.z)) {
      ChunkAccess resolved = level.getChunk(pos.x, pos.z);
      if (resolved instanceof LevelChunk lc) return lc;
    }
    return null;
  }

  private void handlePeriodicRescan(Level level, BlockPos playerPos) {
    int chunkX = playerPos.getX() >> 4;
    int chunkZ = playerPos.getZ() >> 4;

    if (tickCounter % RESCAN_PLAYER_CHUNK_INTERVAL == 0
        && level.hasChunk(chunkX, chunkZ)) {
      putData(chunkX, chunkZ, scanChunk(level.getChunk(chunkX, chunkZ), level));
    }

    if (tickCounter % RESCAN_ADJACENT_INTERVAL == 0) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) continue;
          int nx = chunkX + dx;
          int nz = chunkZ + dz;
          if (level.hasChunk(nx, nz)) {
            putData(nx, nz, scanChunk(level.getChunk(nx, nz), level));
          }
        }
      }
    }
  }

  /** Rescans 3x3 around the player synchronously. Used on mode transitions. */
  private void rescanImmediate(Level level, BlockPos playerPos) {
    int cx = playerPos.getX() >> 4;
    int cz = playerPos.getZ() >> 4;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        int nx = cx + dx;
        int nz = cz + dz;
        if (level.hasChunk(nx, nz)) {
          putData(nx, nz, scanChunk(level.getChunk(nx, nz), level));
        }
      }
    }
  }

  /**
   * Enqueues chunks beyond the immediate 3x3 that don't yet have data at
   * the current cave bucket. Chunks already scanned at this bucket are
   * skipped: their existing data stays valid because the additive merge
   * and composite view handle stale flood boundaries gracefully.
   *
   * <p>This means the first reflood queues the full radius. Subsequent
   * refloods only add the movement edge (newly in-range chunks). The
   * existing queue drains progressively even during continuous movement.
   */
  private void enqueueFloodRadius(Level level, BlockPos playerPos) {
    int cx = playerPos.getX() >> 4;
    int cz = playerPos.getZ() >> 4;
    int radius = caveScanRadiusChunks;
    int layerY = currentLayerY();

    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
        int nx = cx + dx;
        int nz = cz + dz;
        if (!level.hasChunk(nx, nz)) continue;

        // Skip chunks already queued or fully scanned at this bucket
        long key = ChunkPos.asLong(nx, nz);
        if (refloodQueued.contains(key)) continue;
        LayeredEntry entry = cache.get(key);
        if (entry != null) {
          ChunkColorData existing = entry.getAtBucket(layerY);
          if (existing != null && existing.knownCount() == ChunkColorData.PIXELS) continue;
        }

        refloodQueue.addLast(level.getChunk(nx, nz));
        refloodQueued.add(key);
      }
    }
  }
}
