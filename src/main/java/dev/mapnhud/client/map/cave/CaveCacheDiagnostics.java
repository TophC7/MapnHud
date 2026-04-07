package dev.mapnhud.client.map.cave;

import dev.mapnhud.MapnHudMod;
import dev.mapnhud.client.map.CaveFloodFill;
import dev.mapnhud.client.map.ChunkColorData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Owns every per-tick counter, totals tally, and log line for the cave
 * chunk cache. The cache calls {@code recordX} hooks during normal work
 * and asks the diagnostics object for a {@link DebugSnapshot} on demand.
 *
 * <p>The snapshot is rebuilt only when the consumer asks for it (via
 * {@link #getSnapshot}) instead of every tick, so the HUD doesn't pay
 * 47-field record allocations 20 times per second. Near-chunk health
 * also runs only on snapshot fetch and only when diagnostics are enabled.
 */
public final class CaveCacheDiagnostics {

  public static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("mapnhud.debug.cave", "false"));
  public static final int LOG_INTERVAL_TICKS =
      Math.max(1, Integer.getInteger("mapnhud.debug.cave.intervalTicks", 20));
  public static final int NEAR_RADIUS_CHUNKS =
      Math.max(1, Integer.getInteger("mapnhud.debug.cave.nearRadiusChunks", 2));

  /** Source of a chunk scan request, used to bucket per-tick scan stats. */
  public enum ScanSource {
    EVENT,
    REFLOOD,
    PERIODIC_PLAYER,
    PERIODIC_ADJACENT,
    IMMEDIATE
  }

  /** Bucketed anomaly tag for the most recent unusual event. */
  public enum Anomaly {
    NONE("-"),
    ENQUEUE_DEDUP_NEAR("enqueue-dedup-near"),
    MODE_SWITCH("mode-switch"),
    NEAR_NULL_SCAN("near-null-scan"),
    FLOOD_COOLDOWN_SKIP("flood-cooldown-skip"),
    NAV_GRAPH_REBUILD("nav-graph-rebuild"),
    SCAN_DROP_MAX_RETRIES("scan-drop-max-retries"),
    NEAR_MISSING_OR_DEAD("near-missing-or-dead");

    private final String label;
    Anomaly(String label) { this.label = label; }
    public String label() { return label; }
  }

  // -- Per-tick counters (mutable, reset each tick) --

  private int tick;
  private int eventScans;
  private int eventRequeues;
  private int eventDrops;
  private int eventNullScans;
  private int refloodScans;
  private int refloodNullScans;
  private int periodicPlayerScans;
  private int periodicAdjacentScans;
  private int immediateScans;
  private int navProcessed;
  private int navBuilt;
  private int cacheWrites;
  private int knownFullScans;
  private int knownPartialScans;
  private int unknownOnlyScans;
  private int boundaryOnlyScans;
  private int mixedZeroKnownScans;
  private int floodStarts;
  private int floodCompletions;
  private int floodCooldownSkips;

  // -- Totals across the current dimension session --

  private long totalRequeues;
  private long totalDroppedScans;
  private long totalNullScans;
  private long totalFloodStarts;
  private long totalFloodCompletions;
  private long totalFloodCooldownSkips;

  private String lastFloodReason = "init";
  private Anomaly lastAnomaly = Anomaly.NONE;

  private final LongOpenHashSet warnedDroppedScanChunks = new LongOpenHashSet();
  private final LongOpenHashSet warnedNullNearChunks = new LongOpenHashSet();

  /** Resets per-tick counters at the start of a new tick. */
  public void resetTick(int tick) {
    this.tick = tick;
    eventScans = 0;
    eventRequeues = 0;
    eventDrops = 0;
    eventNullScans = 0;
    refloodScans = 0;
    refloodNullScans = 0;
    periodicPlayerScans = 0;
    periodicAdjacentScans = 0;
    immediateScans = 0;
    navProcessed = 0;
    navBuilt = 0;
    cacheWrites = 0;
    knownFullScans = 0;
    knownPartialScans = 0;
    unknownOnlyScans = 0;
    boundaryOnlyScans = 0;
    mixedZeroKnownScans = 0;
    floodStarts = 0;
    floodCompletions = 0;
    floodCooldownSkips = 0;
  }

  /** Clears totals and warned-set state on dimension change. */
  public void clear() {
    totalRequeues = 0;
    totalDroppedScans = 0;
    totalNullScans = 0;
    totalFloodStarts = 0;
    totalFloodCompletions = 0;
    totalFloodCooldownSkips = 0;
    lastFloodReason = "init";
    lastAnomaly = Anomaly.NONE;
    warnedDroppedScanChunks.clear();
    warnedNullNearChunks.clear();
  }

  // -- Per-tick recording hooks --

  public void recordCacheWrite() { cacheWrites++; }
  public void recordEventRequeue() {
    eventRequeues++;
    totalRequeues++;
  }
  public void recordEventDrop(int chunkX, int chunkZ, int attempts,
                              int priorityQueueSize, int scanQueueSize,
                              int refloodQueueSize, int navQueueSize) {
    eventDrops++;
    totalDroppedScans++;
    long key = ChunkPos.asLong(chunkX, chunkZ);
    if (warnedDroppedScanChunks.add(key)) {
      lastAnomaly = Anomaly.SCAN_DROP_MAX_RETRIES;
      if (ENABLED) {
        MapnHudMod.LOG.warn(
            "[MapDiag] Dropped unresolved chunk after {} retries at ({}, {}) q[pri={},scan={},reflood={},nav={}]",
            attempts, chunkX, chunkZ,
            priorityQueueSize, scanQueueSize, refloodQueueSize, navQueueSize);
      }
    }
  }

  public void recordFloodStart(String reason, int originX, int originY, int originZ,
                               int radiusBlocks, int allowedChunks, int unknownFrontier,
                               int graphSnapshots, int priQ, int scanQ, int refloodQ, int navQ) {
    floodStarts++;
    totalFloodStarts++;
    lastFloodReason = reason;
    if (ENABLED) {
      MapnHudMod.LOG.info(
          "[MapDiag] Flood start reason={} origin=({}, {}, {}) radius={} allowedChunks={} unknownFrontier={} graphSnapshots={} q[pri={},scan={},reflood={},nav={}]",
          reason, originX, originY, originZ, radiusBlocks,
          allowedChunks, unknownFrontier, graphSnapshots,
          priQ, scanQ, refloodQ, navQ);
    }
  }
  public void recordFloodCompletion() {
    floodCompletions++;
    totalFloodCompletions++;
  }
  public void recordFloodCooldownSkip() {
    floodCooldownSkips++;
    totalFloodCooldownSkips++;
    lastAnomaly = Anomaly.FLOOD_COOLDOWN_SKIP;
  }

  public void recordNavProcessed() { navProcessed++; }
  public void recordNavBuilt() { navBuilt++; }

  public void noteAnomaly(Anomaly anomaly) {
    lastAnomaly = anomaly;
  }

  public void noteNearChunkUnload(int chunkX, int chunkZ) {
    if (ENABLED) {
      MapnHudMod.LOG.info(
          "[MapDiag] Near-player chunk unload at ({}, {}), scheduling flood rebuild",
          chunkX, chunkZ);
    }
  }

  public void noteNavGraphRebuild(int chunkX, int chunkZ) {
    lastAnomaly = Anomaly.NAV_GRAPH_REBUILD;
    if (ENABLED) {
      MapnHudMod.LOG.info(
          "[MapDiag] Nav graph update inside active flood at ({}, {}), scheduling flood rebuild",
          chunkX, chunkZ);
    }
  }

  /**
   * Classifies a scan result into per-tick counters and emits a warning
   * the first time a near-player chunk produces a null cave scan.
   */
  public void classifyScanResult(ChunkAccess chunk, ChunkColorData data, ScanSource source,
                                 boolean caveMode, boolean isNearPlayer,
                                 CaveFloodFill.Result floodResult) {
    switch (source) {
      case EVENT -> eventScans++;
      case REFLOOD -> refloodScans++;
      case PERIODIC_PLAYER -> periodicPlayerScans++;
      case PERIODIC_ADJACENT -> periodicAdjacentScans++;
      case IMMEDIATE -> immediateScans++;
    }

    if (data == null) {
      totalNullScans++;
      switch (source) {
        case EVENT -> eventNullScans++;
        case REFLOOD -> refloodNullScans++;
        default -> {}
      }
      if (caveMode && isNearPlayer) {
        long key = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
        if (warnedNullNearChunks.add(key)) {
          lastAnomaly = Anomaly.NEAR_NULL_SCAN;
          if (ENABLED) {
            MapnHudMod.LOG.warn(
                "[MapDiag] Null cave scan near player at chunk ({}, {}) floodComplete={} floodUnknown={} reachableCols={}",
                chunk.getPos().x, chunk.getPos().z,
                floodResult.complete(),
                floodResult.isUnknownChunk(chunk.getPos().x, chunk.getPos().z),
                floodResult.columnsReachable());
          }
        }
      }
      return;
    }

    warnedNullNearChunks.remove(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z));

    if (!data.isCaveData()) return;

    int known = data.knownCount();
    if (known == ChunkColorData.PIXELS) {
      knownFullScans++;
      return;
    }
    if (known > 0) {
      knownPartialScans++;
      return;
    }

    int unknown = data.countFieldFlag(CaveFieldState.UNKNOWN);
    int boundary = data.countFieldFlag(CaveFieldState.BOUNDARY);
    if (unknown == ChunkColorData.PIXELS) {
      unknownOnlyScans++;
    } else if (boundary == ChunkColorData.PIXELS) {
      boundaryOnlyScans++;
    } else {
      mixedZeroKnownScans++;
    }
  }

  // -- Snapshot construction (called from HUD or periodic logger only) --

  /**
   * Builds a fresh debug snapshot from the current counters and the
   * cache state passed in via {@code ctx}. Allocates one record + one
   * NearChunkHealth record per call. Cheap when not called.
   */
  public DebugSnapshot buildSnapshot(SnapshotContext ctx) {
    NearChunkHealth near = computeNearChunkHealth(ctx);
    if (near.missingChunks() > 0 || near.suspectDeadChunks() > 0 || eventDrops > 0) {
      lastAnomaly = Anomaly.NEAR_MISSING_OR_DEAD;
    }
    return new DebugSnapshot(
        tick, ctx.caveMode(),
        ctx.playerChunkX(), ctx.playerChunkZ(),
        ctx.priorityQueueSize(), ctx.scanQueueSize(),
        ctx.refloodQueueSize(), ctx.navQueueSize(),
        ctx.scanQueuedSize(), ctx.navQueuedSize(),
        ctx.nextPriorityEligibleTick(), ctx.nextScanEligibleTick(),
        ctx.floodActive(), ctx.floodComplete(),
        ctx.floodColumnsReachable(), ctx.floodStatesVisited(),
        ctx.floodUnknownChunkFrontier(), ctx.floodElapsedMs(),
        lastFloodReason,
        eventScans, eventRequeues, eventDrops, eventNullScans,
        refloodScans, refloodNullScans,
        periodicPlayerScans, periodicAdjacentScans, immediateScans,
        navProcessed, navBuilt, cacheWrites,
        knownFullScans, knownPartialScans,
        unknownOnlyScans, boundaryOnlyScans, mixedZeroKnownScans,
        near.loadedChunks(), near.cachedChunks(),
        near.missingChunks(), near.suspectDeadChunks(),
        totalRequeues, totalDroppedScans, totalNullScans,
        totalFloodStarts, totalFloodCompletions, totalFloodCooldownSkips,
        lastAnomaly.label());
  }

  /**
   * Periodic log helper. Short-circuits unless diagnostics are enabled,
   * cave mode is active, and the log interval has elapsed. Reuses the
   * snapshot the cache already built so we don't allocate twice.
   */
  public void maybeLog(SnapshotContext ctx, DebugSnapshot s,
                       int tickCounter, Level level, BlockPos playerPos) {
    if (!ENABLED || !ctx.caveMode()) return;
    if (tickCounter % LOG_INTERVAL_TICKS != 0) return;
    MapnHudMod.LOG.info(
        "[MapDiag] tick={} playerChunk=({}, {}) flood[active={},complete={},cols={},states={},unknownChunks={},elapsedMs={}] " +
            "queues[pri={},scan={},reflood={},nav={},scanSet={},navSet={},nextPri={},nextScan={}] " +
            "work[event={} requeue={} drop={} null={} reflood={} refloodNull={} periodicP={} periodicAdj={} immediate={} nav={} navBuilt={} writes={} " +
            "knownFull={} knownPartial={} unknownOnly={} boundaryOnly={} mixed0Known={}] " +
            "near[loaded={} cached={} missing={} suspectDead={}] totals[requeue={} drop={} null={} floodStart={} floodDone={} cooldownSkip={}] anomaly={}",
        s.tick(), s.playerChunkX(), s.playerChunkZ(),
        s.floodActive(), s.floodComplete(), s.floodColumnsReachable(),
        s.floodStatesVisited(), s.floodUnknownChunkFrontier(),
        String.format("%.2f", s.floodElapsedMs()),
        s.priorityQueueSize(), s.scanQueueSize(), s.refloodQueueSize(), s.navQueueSize(),
        s.scanQueuedSize(), s.navQueuedSize(),
        s.nextPriorityEligibleTick(), s.nextScanEligibleTick(),
        s.tickEventScans(), s.tickEventRequeues(), s.tickEventDrops(), s.tickEventNullScans(),
        s.tickRefloodScans(), s.tickRefloodNullScans(),
        s.tickPeriodicPlayerScans(), s.tickPeriodicAdjacentScans(), s.tickImmediateScans(),
        s.tickNavProcessed(), s.tickNavBuilt(), s.tickCacheWrites(),
        s.tickKnownFullScans(), s.tickKnownPartialScans(),
        s.tickUnknownOnlyScans(), s.tickBoundaryOnlyScans(), s.tickMixedZeroKnownScans(),
        s.nearLoadedChunks(), s.nearCachedChunks(), s.nearMissingChunks(), s.nearSuspectDeadChunks(),
        s.totalRequeues(), s.totalDroppedScans(), s.totalNullScans(),
        s.totalFloodStarts(), s.totalFloodCompletions(), s.totalFloodCooldownSkips(),
        s.lastAnomaly());

    if (s.nearMissingChunks() > 0 || s.nearSuspectDeadChunks() > 0 || s.tickEventDrops() > 0) {
      String nearSummary = collectNearIssueSummary(level, playerPos, ctx);
      MapnHudMod.LOG.warn(
          "[MapDiag] Suspicious near-player state loaded={} cached={} missing={} suspectDead={} eventDrops={} floodReason={} details={}",
          s.nearLoadedChunks(), s.nearCachedChunks(),
          s.nearMissingChunks(), s.nearSuspectDeadChunks(),
          s.tickEventDrops(), s.lastFloodReason(), nearSummary);
    }
  }

  // -- Near-chunk health (only walked when called) --

  private NearChunkHealth computeNearChunkHealth(SnapshotContext ctx) {
    int pcx = ctx.playerChunkX();
    int pcz = ctx.playerChunkZ();
    int loaded = 0, cached = 0, missing = 0, suspectDead = 0;
    Level level = ctx.level();
    CaveFloodFill.Result flood = ctx.floodResult();
    boolean caveMode = ctx.caveMode();

    for (int dx = -NEAR_RADIUS_CHUNKS; dx <= NEAR_RADIUS_CHUNKS; dx++) {
      for (int dz = -NEAR_RADIUS_CHUNKS; dz <= NEAR_RADIUS_CHUNKS; dz++) {
        int nx = pcx + dx;
        int nz = pcz + dz;
        if (!level.hasChunk(nx, nz)) continue;

        loaded++;
        ChunkColorData data = ctx.lookup().get(nx, nz);
        if (data == null) {
          missing++;
        } else {
          cached++;
        }

        if (!caveMode || !flood.complete() || flood.isUnknownChunk(nx, nz)) continue;
        int centerX = (nx << 4) + 8;
        int centerZ = (nz << 4) + 8;
        if (flood.isOutsideRadius(centerX, centerZ)) continue;
        if (data == null || data.knownCount() == 0) suspectDead++;
      }
    }
    return new NearChunkHealth(loaded, cached, missing, suspectDead);
  }

  private String collectNearIssueSummary(Level level, BlockPos playerPos, SnapshotContext ctx) {
    int pcx = playerPos.getX() >> 4;
    int pcz = playerPos.getZ() >> 4;
    StringBuilder missing = new StringBuilder();
    StringBuilder dead = new StringBuilder();
    int missingCount = 0;
    int deadCount = 0;
    CaveFloodFill.Result flood = ctx.floodResult();
    boolean caveMode = ctx.caveMode();

    for (int dx = -NEAR_RADIUS_CHUNKS; dx <= NEAR_RADIUS_CHUNKS; dx++) {
      for (int dz = -NEAR_RADIUS_CHUNKS; dz <= NEAR_RADIUS_CHUNKS; dz++) {
        int nx = pcx + dx;
        int nz = pcz + dz;
        if (!level.hasChunk(nx, nz)) continue;

        ChunkColorData data = ctx.lookup().get(nx, nz);
        if (data == null) {
          missingCount++;
          if (missingCount <= 6) appendChunkCoords(missing, nx, nz);
          continue;
        }

        if (!caveMode || !flood.complete() || flood.isUnknownChunk(nx, nz)) continue;
        int centerX = (nx << 4) + 8;
        int centerZ = (nz << 4) + 8;
        if (!flood.isOutsideRadius(centerX, centerZ) && data.knownCount() == 0) {
          deadCount++;
          if (deadCount <= 6) appendChunkCoords(dead, nx, nz);
        }
      }
    }

    return "missing=" + (missingCount == 0 ? "none" : missing)
        + " dead=" + (deadCount == 0 ? "none" : dead);
  }

  private static void appendChunkCoords(StringBuilder sb, int chunkX, int chunkZ) {
    if (!sb.isEmpty()) sb.append(' ');
    sb.append('(').append(chunkX).append(',').append(chunkZ).append(')');
  }

  // -- Inputs and snapshot record --

  /**
   * Per-call inputs the diagnostics need from the cache. The cache builds
   * one of these on demand right before calling buildSnapshot/maybeLog,
   * which keeps the diagnostics class free of cache field references.
   *
   * <p>{@code lookup} is a function reference (typically {@code cache::get})
   * so the diagnostics can resolve cache entries without holding a back
   * reference to the cache class itself.
   */
  public record SnapshotContext(
      Level level,
      boolean caveMode,
      int playerChunkX,
      int playerChunkZ,
      int priorityQueueSize,
      int scanQueueSize,
      int refloodQueueSize,
      int navQueueSize,
      int scanQueuedSize,
      int navQueuedSize,
      int nextPriorityEligibleTick,
      int nextScanEligibleTick,
      boolean floodActive,
      boolean floodComplete,
      CaveFloodFill.Result floodResult,
      ChunkLookup lookup
  ) {
    public int floodColumnsReachable() { return floodResult.columnsReachable(); }
    public int floodStatesVisited() { return floodResult.columnsVisited(); }
    public int floodUnknownChunkFrontier() { return floodResult.unknownChunkFrontier().size(); }
    public double floodElapsedMs() { return floodResult.elapsedMs(); }
  }

  /** Cache lookup callback for the diagnostics class. */
  @FunctionalInterface
  public interface ChunkLookup {
    ChunkColorData get(int chunkX, int chunkZ);
  }

  private record NearChunkHealth(
      int loadedChunks, int cachedChunks, int missingChunks, int suspectDeadChunks) {}

  /** Public snapshot consumed by HUD overlays and the periodic logger. */
  public record DebugSnapshot(
      int tick,
      boolean caveMode,
      int playerChunkX,
      int playerChunkZ,
      int priorityQueueSize,
      int scanQueueSize,
      int refloodQueueSize,
      int navQueueSize,
      int scanQueuedSize,
      int navQueuedSize,
      int nextPriorityEligibleTick,
      int nextScanEligibleTick,
      boolean floodActive,
      boolean floodComplete,
      int floodColumnsReachable,
      int floodStatesVisited,
      int floodUnknownChunkFrontier,
      double floodElapsedMs,
      String lastFloodReason,
      int tickEventScans,
      int tickEventRequeues,
      int tickEventDrops,
      int tickEventNullScans,
      int tickRefloodScans,
      int tickRefloodNullScans,
      int tickPeriodicPlayerScans,
      int tickPeriodicAdjacentScans,
      int tickImmediateScans,
      int tickNavProcessed,
      int tickNavBuilt,
      int tickCacheWrites,
      int tickKnownFullScans,
      int tickKnownPartialScans,
      int tickUnknownOnlyScans,
      int tickBoundaryOnlyScans,
      int tickMixedZeroKnownScans,
      int nearLoadedChunks,
      int nearCachedChunks,
      int nearMissingChunks,
      int nearSuspectDeadChunks,
      long totalRequeues,
      long totalDroppedScans,
      long totalNullScans,
      long totalFloodStarts,
      long totalFloodCompletions,
      long totalFloodCooldownSkips,
      String lastAnomaly
  ) {
    public static DebugSnapshot empty() {
      return new DebugSnapshot(
          0, false, 0, 0,
          0, 0, 0, 0,
          0, 0,
          Integer.MAX_VALUE, Integer.MAX_VALUE,
          false, true, 0, 0, 0, 0.0,
          "init",
          0, 0, 0, 0,
          0, 0, 0, 0, 0,
          0, 0, 0,
          0, 0, 0, 0, 0,
          0, 0, 0, 0,
          0, 0, 0, 0, 0, 0,
          "-");
    }
  }
}
