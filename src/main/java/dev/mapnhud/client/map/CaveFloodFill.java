package dev.mapnhud.client.map;

import dev.mapnhud.client.map.cave.CaveStandability;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Incremental flood fill over standable cave poses.
 *
 * <p>Traversal runs on (x, y, z) states to avoid poisoning columns when a
 * failed Y path arrives before a valid one. Rendering still consumes the
 * collapsed (x, z) -> y map.
 *
 * <p>The flood is time-budgeted: each {@link #advance} call processes BFS
 * states for a configurable nanosecond budget, then returns the current
 * partial result. Call {@link #start} to begin a new flood (allocates
 * fresh result collections so previous {@link Result} references stay
 * valid).
 */
public final class CaveFloodFill {

  /** Max Y delta per BFS step. Handles stairs and uneven floors. */
  private static final int STEP_DELTA = 2;

  /** Cardinal neighbor offsets (N, S, E, W). */
  private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  /** Check System.nanoTime() every N iterations to amortize the syscall. */
  private static final int BUDGET_CHECK_MASK = 63; // power-of-two minus one

  /**
   * Result of a cave flood fill. Wraps a live reference to the reachable
   * column map — the map grows during {@link #advance} calls but is never
   * cleared while this Result is held (new floods allocate fresh maps).
   *
   * @param reachable packed (x, z) -> walking Y for each reachable column
   * @param columnsVisited total 3D states the BFS examined so far
   * @param columnsReachable unique reachable columns in the collapsed field
   * @param complete true when the flood finished for this origin
   * @param originX flood origin block X
   * @param originZ flood origin block Z
   * @param maxRadiusSq squared flood radius in block units
   * @param elapsedNanos cumulative wall-clock time across all advance calls
   */
  public record Result(
      Long2IntMap reachable,
      int columnsVisited,
      int columnsReachable,
      boolean complete,
      int originX,
      int originZ,
      int maxRadiusSq,
      long elapsedNanos
  ) {
    public boolean isReachable(int blockX, int blockZ) {
      return reachable.containsKey(ChunkPos.asLong(blockX, blockZ));
    }

    public int getWalkingY(int blockX, int blockZ) {
      return reachable.get(ChunkPos.asLong(blockX, blockZ));
    }

    public boolean isOutsideRadius(int blockX, int blockZ) {
      if (maxRadiusSq < 0) return true;
      long dx = (long) blockX - originX;
      long dz = (long) blockZ - originZ;
      return dx * dx + dz * dz > maxRadiusSq;
    }

    public double elapsedMs() {
      return elapsedNanos / 1_000_000.0;
    }
  }

  /** Empty result for when no flood has run. */
  public static final Result EMPTY = new Result(
      Long2IntMaps.unmodifiable(new Long2IntOpenHashMap()),
      0, 0, true, 0, 0, -1, 0);

  // -- BFS queue (arrays reused across starts, grown on demand) --

  private int[] queueX, queueZ, queueY;
  private int head, tail, capacity;

  // -- Per-flood state (fresh allocation per start for result isolation) --

  private Long2IntOpenHashMap reachable;
  private LongOpenHashSet visitedPoses;

  // -- Flood parameters --

  private int originX, originY, originZ;
  private int maxRadiusSq;
  private int minBuildHeight, maxBuildHeight;

  // -- Status --

  private boolean active;
  private boolean complete;
  private int statesVisited;
  private long totalElapsedNanos;

  // -- Scratch (reused across advance calls, avoids allocation) --

  private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

  public CaveFloodFill() {
    capacity = 8192;
    queueX = new int[capacity];
    queueZ = new int[capacity];
    queueY = new int[capacity];
  }

  /**
   * Starts a new flood from the given origin. Allocates fresh result
   * collections so any previously returned {@link Result} objects stay
   * valid with their data intact.
   */
  public void start(Level level, BlockPos origin, int maxRadius) {
    this.originX = origin.getX();
    this.originY = origin.getY();
    this.originZ = origin.getZ();
    this.maxRadiusSq = maxRadius * maxRadius;
    this.minBuildHeight = level.getMinBuildHeight();
    this.maxBuildHeight = level.getMaxBuildHeight() - 1;

    // Fresh collections so old Results keep their data
    this.reachable = new Long2IntOpenHashMap();
    this.reachable.defaultReturnValue(Integer.MIN_VALUE);
    this.visitedPoses = new LongOpenHashSet();

    head = 0;
    tail = 0;
    statesVisited = 0;
    totalElapsedNanos = 0;
    complete = false;
    active = true;

    // Seed BFS from origin
    int startY = findWalkableY(level, originX, originZ, originY);
    if (startY != Integer.MIN_VALUE) {
      visitedPoses.add(packXYZ(originX, startY, originZ));
      enqueue(originX, originZ, startY);
    } else {
      complete = true;
    }
  }

  /**
   * Advances the in-progress flood for up to {@code budgetNanos}
   * nanoseconds, then returns the current result (partial or complete).
   */
  public Result advance(Level level, long budgetNanos) {
    if (!active || complete) return currentResult();

    long startTime = System.nanoTime();
    int batch = 0;

    while (head < tail) {
      if ((batch & BUDGET_CHECK_MASK) == 0 && batch > 0
          && System.nanoTime() - startTime > budgetNanos) {
        break;
      }

      int x = queueX[head];
      int z = queueZ[head];
      int y = queueY[head];
      head++;
      statesVisited++;
      batch++;

      int dx = x - originX;
      int dz = z - originZ;
      if (dx * dx + dz * dz > maxRadiusSq) continue;

      // Update reachable column, preferring the Y closest to origin
      long columnKey = ChunkPos.asLong(x, z);
      int currentY = reachable.get(columnKey);
      if (currentY == Integer.MIN_VALUE
          || Math.abs(y - originY) < Math.abs(currentY - originY)) {
        reachable.put(columnKey, y);
      }

      for (int[] dir : DIRS) {
        int nx = x + dir[0];
        int nz = z + dir[1];
        int ndx = nx - originX;
        int ndz = nz - originZ;
        if (ndx * ndx + ndz * ndz > maxRadiusSq) continue;

        int walkY = findWalkableY(level, nx, nz, y);
        if (walkY == Integer.MIN_VALUE) continue;

        // Don't escape to surface
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz);
        if (walkY >= surfaceY - 1) continue;

        long poseKey = packXYZ(nx, walkY, nz);
        if (!visitedPoses.add(poseKey)) continue;

        enqueue(nx, nz, walkY);
      }
    }

    totalElapsedNanos += System.nanoTime() - startTime;
    if (head >= tail) complete = true;
    return currentResult();
  }

  /** True when no flood is active or the current flood has finished. */
  public boolean isComplete() { return !active || complete; }

  /** True when a flood has been started (may or may not be complete). */
  public boolean isActive() { return active; }

  /** Returns the current result snapshot. */
  public Result currentResult() {
    if (!active) return EMPTY;
    return new Result(reachable,
        statesVisited, reachable.size(), complete,
        originX, originZ, maxRadiusSq, totalElapsedNanos);
  }

  /** Deactivates the flood. Previous Results remain valid. */
  public void reset() {
    active = false;
    complete = true;
    reachable = null;
    visitedPoses = null;
  }

  // -- Queue --

  private void enqueue(int x, int z, int y) {
    if (tail >= capacity) {
      capacity *= 2;
      queueX = Arrays.copyOf(queueX, capacity);
      queueZ = Arrays.copyOf(queueZ, capacity);
      queueY = Arrays.copyOf(queueY, capacity);
    }
    queueX[tail] = x;
    queueZ[tail] = z;
    queueY[tail] = y;
    tail++;
  }

  // -- Block checks --

  /**
   * Finds a walkable Y near the given Y by checking the exact position
   * first, then offsets up to ±STEP_DELTA. A single hasChunkAt check
   * covers the whole column (all reads share the same X/Z).
   */
  private int findWalkableY(Level level, int x, int z, int nearY) {
    if (nearY <= minBuildHeight || nearY >= maxBuildHeight) return Integer.MIN_VALUE;
    mutable.set(x, nearY, z);
    if (!level.hasChunkAt(mutable)) return Integer.MIN_VALUE;

    // Fast path: exact Y (most common on flat ground)
    if (CaveStandability.isStandableAtY(level, mutable, nearY)) return nearY;

    // Check step offsets ±1, ±2
    for (int dy = 1; dy <= STEP_DELTA; dy++) {
      int up = nearY + dy;
      if (up > minBuildHeight && up < maxBuildHeight
          && CaveStandability.isStandableAtY(level, mutable, up)) {
        return up;
      }
      int down = nearY - dy;
      if (down > minBuildHeight && down < maxBuildHeight
          && CaveStandability.isStandableAtY(level, mutable, down)) {
        return down;
      }
    }
    return Integer.MIN_VALUE;
  }

  // -- Key packing --

  /**
   * Packs an (x, y, z) pose into a 64-bit hash key for the visited set.
   *
   * <p>Hash key only — never decoded back. Y is masked to 12 bits, which
   * means negative Ys collide with their positive counterparts modulo 4096.
   * 1.21's vertical range is -64..320, so the 12-bit window covers it
   * without true cross-Y collisions, but the bits are NOT a recoverable Y.
   */
  private static long packXYZ(int x, int y, int z) {
    long px = x & 0x3FFFFFFL;
    long pz = z & 0x3FFFFFFL;
    long py = y & 0xFFFL;
    return (px << 38) | (pz << 12) | py;
  }
}
