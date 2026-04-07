package dev.mapnhud.client.map;

import dev.mapnhud.client.map.ChunkScanQueueSet.QueuedChunk;
import dev.mapnhud.client.map.ChunkScanQueueSet.QueuedRefloodChunk;
import dev.mapnhud.client.map.ChunkScanQueueSet.RequeueOutcome;
import dev.mapnhud.client.map.cave.CaveCacheDiagnostics;
import dev.mapnhud.client.map.cave.CaveCacheDiagnostics.Anomaly;
import dev.mapnhud.client.map.cave.CaveCacheDiagnostics.DebugSnapshot;
import dev.mapnhud.client.map.cave.CaveCacheDiagnostics.ScanSource;
import dev.mapnhud.client.map.cave.CaveFloodController;
import dev.mapnhud.client.map.cave.CaveFloodController.RebuildReason;
import dev.mapnhud.client.map.cave.CaveLayeredEntry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Top-level cache of per-chunk column data for the minimap.
 *
 * <p>This class coordinates four collaborators and otherwise stays thin:
 * <ul>
 *   <li>{@link ChunkScanQueueSet} schedules scan / reflood work.</li>
 *   <li>{@link CaveFloodController} owns the cave flood lifecycle.</li>
 *   <li>{@link CaveCacheDiagnostics} tracks counters and emits log lines.</li>
 *   <li>{@link ChunkCachePersistence} writes layered chunks to disk.</li>
 * </ul>
 *
 * <p>The cache itself owns the {@code (chunkX, chunkZ) -> CaveLayeredEntry}
 * map, the per-tick scan loops, and the surface/cave layer dispatch in
 * {@link #get}.
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
  private static final long SCAN_BUDGET_NANOS = 2_000_000L;

  /** Time budget for reflood queue processing per tick. */
  private static final long REFLOOD_BUDGET_NANOS = 4_000_000L;

  private static final int RESCAN_PLAYER_CHUNK_INTERVAL = 4;
  private static final int RESCAN_ADJACENT_INTERVAL = 20;

  /** Save dirty chunks to disk every 60 seconds. */
  private static final int SAVE_INTERVAL = 1200;
  private static final int MAX_SCAN_RESOLVE_RETRIES = 8;
  private static final int MAX_SCAN_RETRY_DELAY_TICKS = 16;

  /** How often the cached debug snapshot is rebuilt for the HUD (ticks). */
  private static final int SNAPSHOT_REBUILD_INTERVAL = 10;

  private final Long2ObjectOpenHashMap<CaveLayeredEntry> cache = new Long2ObjectOpenHashMap<>();
  private final ChunkCachePersistence persistence = new ChunkCachePersistence();
  private final ChunkScanQueueSet queues = new ChunkScanQueueSet();
  private final CaveCacheDiagnostics diagnostics = new CaveCacheDiagnostics();
  private final CaveFloodController flood = new CaveFloodController(diagnostics);

  private DebugSnapshot cachedSnapshot = DebugSnapshot.empty();
  private int tickCounter = 0;
  private boolean dirty = false;

  // Cave mode state
  private BlockPos playerPos = BlockPos.ZERO;
  private boolean caveMode = false;

  // Per-tick scan parameters (set at top of tick from config)
  private int caveScanRadiusChunks = 12;
  private int caveFloodRadiusBlocks = 100;

  // -- Public API --

  public void enqueueChunk(ChunkAccess chunk) {
    int chunkX = chunk.getPos().x;
    int chunkZ = chunk.getPos().z;
    boolean priority = caveMode && isNearPlayerChunk(chunkX, chunkZ, caveScanRadiusChunks + 1);

    if (!queues.enqueueScan(chunk, tickCounter, priority)) {
      if (CaveCacheDiagnostics.ENABLED
          && isNearPlayerChunk(chunkX, chunkZ, CaveCacheDiagnostics.NEAR_RADIUS_CHUNKS)) {
        diagnostics.noteAnomaly(Anomaly.ENQUEUE_DEDUP_NEAR);
      }
      return;
    }

    if (caveMode && isNearPlayerChunk(chunkX, chunkZ, floodRadiusChunks())) {
      flood.requestRebuild(RebuildReason.CHUNK_LOAD);
    }
  }

  public void clearAll() {
    cache.clear();
    queues.clear();
    flood.reset();
    diagnostics.clear();
    cachedSnapshot = DebugSnapshot.empty();
    tickCounter = 0;
    dirty = true;
  }

  public void onChunkUnload(int chunkX, int chunkZ) {
    queues.onChunkUnload(chunkX, chunkZ);
    if (caveMode && isNearPlayerChunk(chunkX, chunkZ, floodRadiusChunks())) {
      flood.requestRebuild(RebuildReason.CHUNK_UNLOAD);
      diagnostics.noteNearChunkUnload(chunkX, chunkZ);
    }
  }

  public void loadFromDisk(ClientLevel level) {
    persistence.setDimension(level);
    persistence.loadDimension(cache);
    dirty = true;
  }

  public void saveToDisk() {
    persistence.saveDirty(cache);
  }

  public void saveAllToDisk() {
    persistence.saveAll(cache);
  }

  public void shutdown() {
    persistence.shutdown();
  }

  public CaveFloodFill.Result getFloodResult() {
    return flood.currentResult();
  }

  public boolean isCaveMode() {
    return caveMode;
  }

  /**
   * Returns the most-recent cached debug snapshot. The snapshot is rebuilt
   * during {@link #tick} on a fixed interval, not on every read, so HUD
   * polling doesn't pay the allocation/lookup cost per render frame.
   */
  public DebugSnapshot getDebugSnapshot() {
    return cachedSnapshot;
  }

  /**
   * Lookup by chunk coords. Returns the appropriate layer for the current
   * mode: surface layer in surface mode, composite cave view in cave mode.
   * The composite fills unknown columns from adjacent cave layers so that
   * known terrain from any layer is never hidden behind black walls.
   */
  public ChunkColorData get(int chunkX, int chunkZ) {
    CaveLayeredEntry entry = cache.get(ChunkPos.asLong(chunkX, chunkZ));
    if (entry == null) return null;
    return caveMode
        ? entry.getCaveComposite(playerPos.getY())
        : entry.getSurface();
  }

  // -- Tick --

  public boolean tick(Level level, BlockPos playerPos, boolean caveMode,
                      int caveScanRadiusChunks, int caveFloodRadiusBlocks) {
    diagnostics.resetTick(tickCounter);
    this.playerPos = playerPos;
    this.caveScanRadiusChunks = caveScanRadiusChunks;
    this.caveFloodRadiusBlocks = caveFloodRadiusBlocks;

    boolean modeChanged = caveMode != this.caveMode;
    this.caveMode = caveMode;

    if (modeChanged) {
      tickCounter = 0;
      queues.clear();
      flood.reset();
      diagnostics.noteAnomaly(Anomaly.MODE_SWITCH);
      // No cache clear: layers handle mode separation. get() returns
      // the appropriate layer for the current mode.

      if (!caveMode) {
        // Leaving cave mode: rescan 3x3 with surface mode for immediate view.
        rescanImmediate(level, playerPos);
      }
    }

    boolean floodAdvanced = false;
    if (caveMode) {
      CaveFloodController.TickResult result = flood.tick(level, playerPos, caveFloodRadiusBlocks,
          tickCounter,
          queues.priorityQueueSize(), queues.scanQueueSize(),
          queues.refloodQueueSize(), queues.navQueueSize());
      floodAdvanced = result.dataChanged();
      if (result.started()) {
        rescanImmediate(level, playerPos);
      }
      if (result.justCompleted()) {
        rescanImmediate(level, playerPos);
        enqueueFloodRadius(level, playerPos);
      }
    }

    dirty = modeChanged || floodAdvanced;
    processScanQueue(level);
    processRefloodQueue(level);
    handlePeriodicRescan(level, playerPos);

    if (tickCounter % SAVE_INTERVAL == 0 && tickCounter > 0) {
      persistence.saveDirty(cache);
    }

    // Rebuild snapshot at a fixed interval — HUD reads the cached one.
    // When diagnostics are enabled the periodic logger reuses the same
    // snapshot rather than building a second one.
    if (tickCounter % SNAPSHOT_REBUILD_INTERVAL == 0) {
      CaveCacheDiagnostics.SnapshotContext ctx = snapshotContext(level);
      cachedSnapshot = diagnostics.buildSnapshot(ctx);
      if (CaveCacheDiagnostics.ENABLED) {
        diagnostics.maybeLog(ctx, cachedSnapshot, tickCounter, level, playerPos);
      }
    }

    tickCounter++;
    return dirty;
  }

  // -- Scan dispatch --

  private int currentLayerY() {
    return caveMode
        ? CaveLayeredEntry.caveBucketKey(playerPos.getY())
        : CaveLayeredEntry.SURFACE_LAYER;
  }

  /**
   * Stores data into the layered cache and marks it dirty for persistence.
   * In cave mode, merges with the existing layer at the same bucket so
   * previously explored columns are preserved (additive cave mapping).
   */
  private void putData(int chunkX, int chunkZ, ChunkColorData data) {
    putData(chunkX, chunkZ, data, currentLayerY());
  }

  private void putData(int chunkX, int chunkZ, ChunkColorData data, int layerY) {
    if (data == null) return;
    long key = ChunkPos.asLong(chunkX, chunkZ);
    CaveLayeredEntry entry = cache.get(key);

    if (entry == null) {
      entry = new CaveLayeredEntry();
      cache.put(key, entry);
    }

    ChunkColorData existingAtBucket = entry.getAtBucket(layerY);
    if (caveMode && data.isCaveData()
        && existingAtBucket != null && existingAtBucket.isCaveData()) {
      data = ChunkColorData.mergeCave(existingAtBucket, data);
    }

    if (layerY != CaveLayeredEntry.SURFACE_LAYER
        && existingAtBucket == null
        && entry.caveLayerCount() >= CaveLayeredEntry.MAX_CAVE_LAYERS) {
      entry.evictFarthestCave(playerPos.getY());
    }

    entry.put(layerY, data);
    diagnostics.recordCacheWrite();
    persistence.markDirty(chunkX, chunkZ);
    dirty = true;
  }

  /** Scans a chunk using the current mode (surface or cave). */
  private ChunkColorData scanChunk(ChunkAccess chunk, Level level, ScanSource source) {
    ChunkColorData data = caveMode
        ? ChunkScanner.scanCave(chunk, level, flood.currentResult())
        : ChunkScanner.scan(chunk, level);
    int chunkX = chunk.getPos().x;
    int chunkZ = chunk.getPos().z;
    boolean isNear = isNearPlayerChunk(chunkX, chunkZ, CaveCacheDiagnostics.NEAR_RADIUS_CHUNKS);
    diagnostics.classifyScanResult(chunk, data, source, caveMode, isNear, flood.currentResult());
    return data;
  }

  /** Scans a chunk at a specific position and stores the result. Shared by periodic + immediate. */
  private void scanChunkAt(Level level, int chunkX, int chunkZ, ScanSource source) {
    if (!level.hasChunk(chunkX, chunkZ)) return;
    ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
    ChunkColorData data = scanChunk(chunk, level, source);
    putData(chunkX, chunkZ, data);
  }

  // -- Queue processors (budgeted, all share runBudgeted) --

  /** Processes chunks from ChunkEvent.Load at a steady rate. */
  private void processScanQueue(Level level) {
    runBudgeted(CHUNKS_PER_TICK, SCAN_BUDGET_NANOS, () -> {
      QueuedChunk queued = queues.pollNextScanChunk(tickCounter);
      if (queued == null) return false;

      ChunkAccess chunk = queued.chunk();
      LevelChunk full = asLevelChunk(chunk, level);
      if (full == null) {
        RequeueOutcome outcome = queues.requeueUnresolvedScan(
            queued, tickCounter, MAX_SCAN_RESOLVE_RETRIES, MAX_SCAN_RETRY_DELAY_TICKS);
        if (outcome == RequeueOutcome.DROPPED) {
          int cx = chunk.getPos().x;
          int cz = chunk.getPos().z;
          diagnostics.recordEventDrop(cx, cz, queued.attempts(),
              queues.priorityQueueSize(), queues.scanQueueSize(),
              queues.refloodQueueSize(), queues.navQueueSize());
        } else {
          diagnostics.recordEventRequeue();
        }
        return true;
      }

      queues.markScanResolved(full.getPos().x, full.getPos().z);
      ChunkColorData data = scanChunk(full, level, ScanSource.EVENT);
      putData(full.getPos().x, full.getPos().z, data);
      return true;
    });
  }

  /**
   * Processes chunks from post-reflood cave scans with a higher budget.
   * Most cave chunks outside the flood radius return null (all-unknown),
   * so the effective cost per chunk is very low.
   */
  private void processRefloodQueue(Level level) {
    if (queues.refloodIsEmpty()) return;
    runBudgeted(REFLOOD_CHUNKS_PER_TICK, REFLOOD_BUDGET_NANOS, () -> {
      if (queues.refloodIsEmpty()) return false;
      QueuedRefloodChunk queued = queues.pollNextReflood(tickCounter);
      if (queued == null) return false;
      // A later flood in another Y bucket supersedes this queued scan.
      if (queued.layerY() != currentLayerY()) return true;

      int chunkX = ChunkPos.getX(queued.chunkKey());
      int chunkZ = ChunkPos.getZ(queued.chunkKey());
      LevelChunk full = getLoadedLevelChunk(level, chunkX, chunkZ);
      if (full == null) {
        // Keep loaded-but-not-yet-full chunks in the worklist, with bounded
        // backoff so a stuck proto chunk cannot burn the reflood budget forever.
        if (level.hasChunk(chunkX, chunkZ)) {
          queues.requeueUnresolvedReflood(
              queued, tickCounter, MAX_SCAN_RESOLVE_RETRIES, MAX_SCAN_RETRY_DELAY_TICKS);
        }
        return true;
      }

      ChunkColorData data = scanChunk(full, level, ScanSource.REFLOOD);
      putData(chunkX, chunkZ, data, queued.layerY());
      return true;
    });
  }

  /**
   * Generic budgeted loop. Runs the step until either the count cap or the
   * nanosecond cap is reached, or the step returns false (nothing to do).
   */
  private static void runBudgeted(int countCap, long nanosCap, BudgetedStep step) {
    int processed = 0;
    long startTime = System.nanoTime();
    while (processed < countCap) {
      if (processed > 0 && System.nanoTime() - startTime > nanosCap) break;
      if (!step.step()) break;
      processed++;
    }
  }

  @FunctionalInterface
  private interface BudgetedStep {
    /** Runs one unit of work. Returns true if there is potentially more to do. */
    boolean step();
  }

  // -- Periodic / immediate rescans --

  private void handlePeriodicRescan(Level level, BlockPos playerPos) {
    int chunkX = playerPos.getX() >> 4;
    int chunkZ = playerPos.getZ() >> 4;

    if (tickCounter % RESCAN_PLAYER_CHUNK_INTERVAL == 0) {
      scanChunkAt(level, chunkX, chunkZ, ScanSource.PERIODIC_PLAYER);
    }

    if (tickCounter % RESCAN_ADJACENT_INTERVAL == 0) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) continue;
          scanChunkAt(level, chunkX + dx, chunkZ + dz, ScanSource.PERIODIC_ADJACENT);
        }
      }
    }
  }

  /** Rescans 3x3 around the player synchronously. Used on mode/flood transitions. */
  private void rescanImmediate(Level level, BlockPos playerPos) {
    int cx = playerPos.getX() >> 4;
    int cz = playerPos.getZ() >> 4;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        scanChunkAt(level, cx + dx, cz + dz, ScanSource.IMMEDIATE);
      }
    }
  }

  /**
   * Enqueues chunks beyond the immediate 3x3 that don't yet have data at
   * the current cave bucket. Chunks already scanned at this bucket are
   * skipped: their existing data stays valid because the additive merge
   * and composite view handle stale flood boundaries gracefully.
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

        long key = ChunkPos.asLong(nx, nz);
        CaveLayeredEntry entry = cache.get(key);
        if (entry != null) {
          ChunkColorData existing = entry.getAtBucket(layerY);
          if (existing != null && existing.knownCount() == ChunkColorData.PIXELS) continue;
        }

        queues.enqueueReflood(key, layerY, tickCounter);
      }
    }
  }

  // -- Helpers --

  private static LevelChunk asLevelChunk(ChunkAccess chunk, Level level) {
    if (chunk instanceof LevelChunk lc) return lc;
    ChunkPos pos = chunk.getPos();
    if (level.hasChunk(pos.x, pos.z)) {
      ChunkAccess resolved = level.getChunk(pos.x, pos.z);
      if (resolved instanceof LevelChunk lc) return lc;
    }
    return null;
  }

  private static LevelChunk getLoadedLevelChunk(Level level, int chunkX, int chunkZ) {
    if (!level.hasChunk(chunkX, chunkZ)) return null;
    ChunkAccess resolved = level.getChunk(chunkX, chunkZ);
    return resolved instanceof LevelChunk lc ? lc : null;
  }

  private int floodRadiusChunks() {
    return Math.max(1, (caveFloodRadiusBlocks + 15) / 16);
  }

  private boolean isNearPlayerChunk(int chunkX, int chunkZ, int radiusChunks) {
    int pcx = playerPos.getX() >> 4;
    int pcz = playerPos.getZ() >> 4;
    int dx = chunkX - pcx;
    int dz = chunkZ - pcz;
    return dx * dx + dz * dz <= radiusChunks * radiusChunks;
  }

  // -- Diagnostics context bridge --

  private CaveCacheDiagnostics.SnapshotContext snapshotContext(Level level) {
    return new CaveCacheDiagnostics.SnapshotContext(
        level, caveMode,
        playerPos.getX() >> 4, playerPos.getZ() >> 4,
        queues.priorityQueueSize(), queues.scanQueueSize(),
        queues.refloodQueueSize(), queues.navQueueSize(),
        queues.scanQueuedSize(), queues.navQueuedSize(),
        queues.nextPriorityEligibleTick(), queues.nextScanEligibleTick(),
        flood.isActive(), flood.isComplete(),
        flood.currentResult(), this::get);
  }
}
