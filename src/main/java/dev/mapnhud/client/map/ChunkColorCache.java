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

  /** Time budget for event queue processing per tick (~2ms of a 50ms tick). */
  private static final long SCAN_BUDGET_NANOS = 2_000_000L;

  /** Time budget for reflood queue processing per tick (~4ms of a 50ms tick). */
  private static final long REFLOOD_BUDGET_NANOS = 4_000_000L;

  /** Rescan the player's own chunk every N ticks to catch fast block updates. */
  private static final int RESCAN_PLAYER_CHUNK_INTERVAL = 4;
  /** Rescan the 8 surrounding chunks every N ticks. Slower than the player chunk. */
  private static final int RESCAN_ADJACENT_INTERVAL = 20;

  /** Save dirty chunks to disk every 60 seconds (1200 ticks @ 20 TPS). */
  private static final int SAVE_INTERVAL = 1200;
  /**
   * Max times an enqueued ProtoChunk is retried before being dropped, with
   * exponential backoff capped at {@link #MAX_SCAN_RETRY_DELAY_TICKS}.
   * Total retry window: ~80 ticks (~4 seconds) before drop.
   */
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
    int distSq = playerChunkDistanceSq(chunkX, chunkZ);
    boolean priority = caveMode && distSq <= sq(caveScanRadiusChunks + 1);

    if (!queues.enqueueScan(chunk, tickCounter, priority)) {
      if (CaveCacheDiagnostics.ENABLED
          && distSq <= sq(CaveCacheDiagnostics.NEAR_RADIUS_CHUNKS)) {
        diagnostics.noteAnomaly(Anomaly.ENQUEUE_DEDUP_NEAR);
      }
      return;
    }

    if (caveMode && distSq <= sq(floodRadiusChunks())) {
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

  /**
   * Drives one game tick of the cache. Returns true if anything that affects
   * what the renderer would draw has changed since the last tick.
   *
   * <p>The body is split into named phases. Each phase either mutates the
   * shared {@link #dirty} flag (via {@link #putData}) or reports its own
   * dirty contribution back here.
   */
  public boolean tick(Level level, BlockPos playerPos, boolean caveMode,
                      int caveScanRadiusChunks, int caveFloodRadiusBlocks) {
    diagnostics.resetTick(tickCounter);
    dirty = false;
    boolean modeChanged = syncFrameInputs(playerPos, caveMode, caveScanRadiusChunks, caveFloodRadiusBlocks);

    if (modeChanged) handleModeTransition(level);
    if (caveMode) advanceCaveFlood(level);

    drainQueues(level);
    runPeriodicRescans(level);
    maybePersist();
    maybeRefreshSnapshot(level);

    tickCounter++;
    return dirty;
  }

  /** Phase 1: copy this tick's inputs into instance state. */
  private boolean syncFrameInputs(BlockPos playerPos, boolean caveMode,
                                  int caveScanRadiusChunks, int caveFloodRadiusBlocks) {
    this.playerPos = playerPos;
    this.caveScanRadiusChunks = caveScanRadiusChunks;
    this.caveFloodRadiusBlocks = caveFloodRadiusBlocks;
    boolean modeChanged = caveMode != this.caveMode;
    this.caveMode = caveMode;
    return modeChanged;
  }

  /**
   * Phase 2: handle a surface↔cave mode change. Resets per-mode state
   * (queues, flood) without clearing the cache itself, since the layered
   * entries are shared between modes.
   */
  private void handleModeTransition(Level level) {
    tickCounter = 0;
    queues.clear();
    flood.reset();
    diagnostics.noteAnomaly(Anomaly.MODE_SWITCH);
    dirty = true;

    if (!caveMode) {
      // Leaving cave mode: rescan 3x3 with surface scanner for an immediate
      // surface view at the player's location.
      rescanImmediate(level, playerPos);
    }
  }

  /** Phase 3: advance the cave flood and run any flood-triggered scans. */
  private void advanceCaveFlood(Level level) {
    CaveFloodController.TickResult result = flood.tick(level, playerPos, caveFloodRadiusBlocks,
        tickCounter,
        queues.priorityQueueSize(), queues.scanQueueSize(),
        queues.refloodQueueSize());
    if (result.dataChanged()) dirty = true;
    if (result.started()) {
      rescanImmediate(level, playerPos);
    }
    if (result.justCompleted()) {
      rescanImmediate(level, playerPos);
      enqueueFloodRadius(level, playerPos);
    }
  }

  /** Phase 4: drain the budgeted scan and reflood queues. */
  private void drainQueues(Level level) {
    processScanQueue(level);
    processRefloodQueue(level);
  }

  /** Phase 6: kick off a save cycle if we hit the save interval. */
  private void maybePersist() {
    if (tickCounter % SAVE_INTERVAL == 0 && tickCounter > 0) {
      persistence.saveDirty(cache);
    }
  }

  /**
   * Phase 7: rebuild the cached debug snapshot for the HUD on a fixed
   * interval. The periodic logger reuses the same snapshot to avoid
   * allocating twice.
   */
  private void maybeRefreshSnapshot(Level level) {
    if (tickCounter % SNAPSHOT_REBUILD_INTERVAL != 0) return;
    CaveCacheDiagnostics.SnapshotContext ctx = snapshotContext(level);
    cachedSnapshot = diagnostics.buildSnapshot(ctx);
    if (CaveCacheDiagnostics.ENABLED) {
      diagnostics.maybeLog(ctx, cachedSnapshot, tickCounter, level, playerPos);
    }
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

    // CaveLayeredEntry.put handles eviction internally when full.
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
    // Guard against ClientChunkCache returning the singleton EmptyLevelChunk
    // (constructed at (0,0)) when hasChunk and getChunk disagree. Without
    // this, periodic rescans of any chunk that has fallen out of FULL status
    // would scan blocks at (0,0) and store them under (chunkX,chunkZ).
    ChunkPos pos = chunk.getPos();
    if (pos.x != chunkX || pos.z != chunkZ) return;
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
              queues.refloodQueueSize());
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
   *
   * <p>Stale-layer entries (queued for a Y bucket the player has since left)
   * are dropped here. They are not lost data: the next flood completion in
   * the new bucket calls {@link #enqueueFloodRadius} which re-enqueues every
   * chunk that lacks coverage at the new bucket. The stale entries simply
   * drain off the front of the queue.
   */
  private void processRefloodQueue(Level level) {
    if (queues.refloodIsEmpty()) return;
    runBudgeted(REFLOOD_CHUNKS_PER_TICK, REFLOOD_BUDGET_NANOS, () -> {
      if (queues.refloodIsEmpty()) return false;
      QueuedRefloodChunk queued = queues.pollNextReflood(tickCounter);
      if (queued == null) return false;
      // Stale layer (player crossed a Y bucket while this entry waited).
      // The next flood for the new bucket will re-enqueue what's needed.
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

  /** Phase 5: rescan the player chunk and its 8 neighbors at fixed intervals. */
  private void runPeriodicRescans(Level level) {
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
      if (resolved instanceof LevelChunk lc) {
        // Same EmptyLevelChunk(0,0) trap as getLoadedLevelChunk — verify the
        // resolved chunk actually matches the position we asked for.
        ChunkPos rpos = lc.getPos();
        if (rpos.x == pos.x && rpos.z == pos.z) return lc;
      }
    }
    return null;
  }

  private static LevelChunk getLoadedLevelChunk(Level level, int chunkX, int chunkZ) {
    if (!level.hasChunk(chunkX, chunkZ)) return null;
    ChunkAccess resolved = level.getChunk(chunkX, chunkZ);
    if (!(resolved instanceof LevelChunk lc)) return null;
    // ClientChunkCache returns a singleton EmptyLevelChunk (constructed with
    // ChunkPos(0,0)) when the requested chunk isn't in storage at FULL status.
    // hasChunk and getChunk take different paths and can disagree, so we have
    // to verify the returned chunk's position actually matches what we asked
    // for. Without this check, scanCave reads blocks at (0,0) and putData
    // stores them under the original (chunkX,chunkZ) — that's the cave smear bug.
    ChunkPos pos = lc.getPos();
    if (pos.x != chunkX || pos.z != chunkZ) return null;
    return lc;
  }

  private int floodRadiusChunks() {
    return Math.max(1, (caveFloodRadiusBlocks + 15) / 16);
  }

  private boolean isNearPlayerChunk(int chunkX, int chunkZ, int radiusChunks) {
    return playerChunkDistanceSq(chunkX, chunkZ) <= sq(radiusChunks);
  }

  private int playerChunkDistanceSq(int chunkX, int chunkZ) {
    int pcx = playerPos.getX() >> 4;
    int pcz = playerPos.getZ() >> 4;
    int dx = chunkX - pcx;
    int dz = chunkZ - pcz;
    return dx * dx + dz * dz;
  }

  private static int sq(int x) { return x * x; }

  // -- Diagnostics context bridge --

  private CaveCacheDiagnostics.SnapshotContext snapshotContext(Level level) {
    return new CaveCacheDiagnostics.SnapshotContext(
        level, caveMode,
        playerPos.getX() >> 4, playerPos.getZ() >> 4,
        queues.priorityQueueSize(), queues.scanQueueSize(),
        queues.refloodQueueSize(),
        queues.scanQueuedSize(),
        queues.nextPriorityEligibleTick(), queues.nextScanEligibleTick(),
        flood.isActive(), flood.isComplete(),
        flood.currentResult(), this::get);
  }
}
