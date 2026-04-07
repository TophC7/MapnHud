package dev.mapnhud.client.map.cave;

import dev.mapnhud.client.map.CaveFloodFill;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Owns the cave flood fill lifecycle: when to start a fresh flood, how to
 * advance an in-progress one, cooldown gating between rebuilds, and the
 * "rebuild requested" signal raised by chunk loads / unloads.
 */
public final class CaveFloodController {

  /** Time budget per flood advance. First advance of a new flood uses 2x. */
  private static final long FLOOD_BUDGET_NANOS = 3_000_000L;

  /** How far the player must move (XZ) before cave flood is recomputed. */
  private static final int CAVE_REFLOOD_DISTANCE = 3;

  /** Y distance that triggers cave reflood (handles vertical movement). */
  private static final int CAVE_REFLOOD_Y_THRESHOLD = 4;

  /** Minimum ticks between refloods to prevent flood storms. */
  private static final int FLOOD_COOLDOWN_TICKS = 10;

  /** Faster cooldown for chunk-change-driven reflood requests. */
  private static final int CHUNK_CHANGE_FLOOD_COOLDOWN_TICKS = 2;

  /** Reasons that may cause a flood rebuild. EnumSet replaces the old string concat. */
  public enum RebuildReason {
    INITIAL("initial"),
    MOVE_XZ("moveXZ"),
    MOVE_Y("moveY"),
    CHUNK_LOAD("chunk-load"),
    CHUNK_UNLOAD("chunk-unload"),
    FORCED("forced");

    private final String label;
    RebuildReason(String label) { this.label = label; }
    public String label() { return label; }
  }

  private final CaveFloodFill caveFlood = new CaveFloodFill();
  private final CaveCacheDiagnostics diagnostics;

  private CaveFloodFill.Result floodResult = CaveFloodFill.EMPTY;
  private int lastFloodX = Integer.MIN_VALUE;
  private int lastFloodY = Integer.MIN_VALUE;
  private int lastFloodZ = Integer.MIN_VALUE;
  private int lastFloodTick = -FLOOD_COOLDOWN_TICKS;

  private final EnumSet<RebuildReason> pendingReasons = EnumSet.noneOf(RebuildReason.class);

  public CaveFloodController(CaveCacheDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  // -- Public API --

  public CaveFloodFill.Result currentResult() {
    return floodResult;
  }

  public boolean isComplete() {
    return caveFlood.isComplete();
  }

  public boolean isActive() {
    return caveFlood.isActive();
  }

  /** Resets the controller for a mode switch or dimension change. */
  public void reset() {
    floodResult = CaveFloodFill.EMPTY;
    lastFloodX = Integer.MIN_VALUE;
    lastFloodY = Integer.MIN_VALUE;
    lastFloodZ = Integer.MIN_VALUE;
    lastFloodTick = -FLOOD_COOLDOWN_TICKS;
    pendingReasons.clear();
    caveFlood.reset();
  }

  /** Marks a rebuild as needed; takes effect on next {@link #tick}. */
  public void requestRebuild(RebuildReason reason) {
    pendingReasons.add(reason);
  }

  /**
   * Returns true if the chunk overlaps the active flood radius. Used by the
   * cache to decide whether a chunk load/unload should request a rebuild.
   */
  public boolean chunkIntersectsActiveRadius(int chunkX, int chunkZ, int radiusBlocks) {
    if (lastFloodX == Integer.MIN_VALUE || lastFloodZ == Integer.MIN_VALUE) return false;

    int minX = chunkX << 4;
    int maxX = minX + 15;
    int minZ = chunkZ << 4;
    int maxZ = minZ + 15;

    long dx = 0;
    if (lastFloodX < minX) dx = (long) minX - lastFloodX;
    else if (lastFloodX > maxX) dx = (long) lastFloodX - maxX;

    long dz = 0;
    if (lastFloodZ < minZ) dz = (long) minZ - lastFloodZ;
    else if (lastFloodZ > maxZ) dz = (long) lastFloodZ - maxZ;

    long radiusSq = (long) radiusBlocks * radiusBlocks;
    return dx * dx + dz * dz <= radiusSq;
  }

  /**
   * Advances the flood for one game tick.
   *
   * @return information about what happened this tick
   */
  public TickResult tick(Level level, BlockPos playerPos, int floodRadiusBlocks,
                         int tickCounter, int priQ, int scanQ, int refloodQ) {
    boolean started = maybeStartFlood(level, playerPos, floodRadiusBlocks,
        tickCounter, priQ, scanQ, refloodQ);

    if (!caveFlood.isActive()) return TickResult.IDLE;

    if (caveFlood.isComplete()) {
      if (started) {
        floodResult = caveFlood.currentResult();
        diagnostics.recordFloodCompletion();
        return new TickResult(true, true, true);
      }
      return TickResult.IDLE;
    }

    long budget = started ? FLOOD_BUDGET_NANOS * 2 : FLOOD_BUDGET_NANOS;
    floodResult = caveFlood.advance(level, budget);

    if (caveFlood.isComplete()) {
      diagnostics.recordFloodCompletion();
      return new TickResult(true, started, true);
    }
    return new TickResult(true, started, false);
  }

  /** Information returned from {@link #tick}. */
  public record TickResult(boolean dataChanged, boolean started, boolean justCompleted) {
    public static final TickResult IDLE = new TickResult(false, false, false);
  }

  // -- Internal --

  private boolean maybeStartFlood(Level level, BlockPos playerPos, int floodRadiusBlocks,
                                  int tickCounter, int priQ, int scanQ, int refloodQ) {
    if (!caveFlood.isComplete()) return false;

    int px = playerPos.getX();
    int py = playerPos.getY();
    int pz = playerPos.getZ();

    int dx = px - lastFloodX;
    int dy = py - lastFloodY;
    int dz = pz - lastFloodZ;
    int distXZSq = dx * dx + dz * dz;

    boolean firstFlood = lastFloodX == Integer.MIN_VALUE;
    boolean movedXZ = distXZSq > CAVE_REFLOOD_DISTANCE * CAVE_REFLOOD_DISTANCE;
    boolean movedY = Math.abs(dy) > CAVE_REFLOOD_Y_THRESHOLD;

    if (movedXZ) pendingReasons.add(RebuildReason.MOVE_XZ);
    if (movedY) pendingReasons.add(RebuildReason.MOVE_Y);

    boolean needsFlood = firstFlood || !pendingReasons.isEmpty();
    if (!needsFlood) return false;

    boolean externalRequest = pendingReasons.contains(RebuildReason.CHUNK_LOAD)
        || pendingReasons.contains(RebuildReason.CHUNK_UNLOAD);
    int cooldown = externalRequest ? CHUNK_CHANGE_FLOOD_COOLDOWN_TICKS : FLOOD_COOLDOWN_TICKS;

    if (!firstFlood && tickCounter - lastFloodTick < cooldown) {
      diagnostics.recordFloodCooldownSkip();
      return false;
    }

    String reason = formatReason(firstFlood);
    diagnostics.recordFloodStart(reason, px, py, pz, floodRadiusBlocks,
        priQ, scanQ, refloodQ);

    caveFlood.start(level, playerPos, floodRadiusBlocks);
    lastFloodX = px;
    lastFloodY = py;
    lastFloodZ = pz;
    lastFloodTick = tickCounter;
    pendingReasons.clear();
    return true;
  }

  private String formatReason(boolean firstFlood) {
    if (firstFlood) return RebuildReason.INITIAL.label();
    if (pendingReasons.isEmpty()) return RebuildReason.FORCED.label();

    StringBuilder sb = new StringBuilder();
    for (RebuildReason r : pendingReasons) {
      if (!sb.isEmpty()) sb.append('+');
      sb.append(r.label());
    }
    return sb.toString();
  }
}
