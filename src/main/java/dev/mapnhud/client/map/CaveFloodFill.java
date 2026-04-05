package dev.mapnhud.client.map;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 2D breadth-first flood fill that determines reachable cave columns from the
 * player's position. Walks the XZ grid following passable 2-block gaps, tracking
 * the walking Y at each step and allowing vertical transitions within the step
 * delta (staircases, slopes).
 *
 * <p>The result is a map of reachable (x, z) columns to their walking Y. This
 * serves as a "cave heightmap" that replaces the WORLD_SURFACE heightmap used
 * in surface mode. Columns not in the result are walls.
 *
 * <p>Performance: the flood runs in a single pass when triggered (every few
 * blocks of player movement). Typical cost is 1-3ms for 5,000-10,000 columns.
 */
public final class CaveFloodFill {

  private CaveFloodFill() {}

  /** Max Y delta per BFS step. 2 handles standard stairs and uneven floors. */
  private static final int STEP_DELTA = 2;

  /** Cardinal neighbor offsets (N, S, E, W). */
  private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  /**
   * Result of a cave flood fill. Contains the reachable column set and
   * performance metrics for debugging.
   *
   * @param reachable  packed (x, z) -> walking Y for each reachable column
   * @param columnsVisited total columns the BFS examined (including walls)
   * @param columnsReachable columns that were passable (subset of visited)
   * @param elapsedNanos   wall-clock time the flood took
   */
  public record Result(
      Long2IntMap reachable,
      int columnsVisited,
      int columnsReachable,
      long elapsedNanos
  ) {
    public boolean isReachable(int blockX, int blockZ) {
      return reachable.containsKey(packXZ(blockX, blockZ));
    }

    public int getWalkingY(int blockX, int blockZ) {
      return reachable.get(packXZ(blockX, blockZ));
    }

    public double elapsedMs() {
      return elapsedNanos / 1_000_000.0;
    }
  }

  /** Empty result for when flood fill hasn't run yet. */
  public static final Result EMPTY = new Result(
      Long2IntMaps.unmodifiable(new Long2IntOpenHashMap()), 0, 0, 0);

  /**
   * Flood-fills reachable cave space from the player's position.
   *
   * @param level     the client level for block state reads
   * @param origin    player's block position (feet level)
   * @param maxRadius maximum XZ distance from origin to explore
   * @return the reachable column set with walking Y values and perf stats
   */
  public static Result flood(Level level, BlockPos origin, int maxRadius) {
    long startTime = System.nanoTime();

    int originX = origin.getX();
    int originZ = origin.getZ();
    int originY = origin.getY();
    int maxRadiusSq = maxRadius * maxRadius;

    Long2IntOpenHashMap reachable = new Long2IntOpenHashMap();
    reachable.defaultReturnValue(Integer.MIN_VALUE);
    LongOpenHashSet visited = new LongOpenHashSet();

    // Flat int arrays as BFS queue to avoid per-entry object allocation.
    // Linear queue (head/tail), grown on demand.
    int capacity = 8192;
    int[] queueX = new int[capacity];
    int[] queueZ = new int[capacity];
    int[] queueY = new int[capacity];
    int head = 0, tail = 0;

    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    if (isPassableAt(level, mutable, originX, originY, originZ)) {
      long originKey = packXZ(originX, originZ);
      visited.add(originKey);
      queueX[tail] = originX;
      queueZ[tail] = originZ;
      queueY[tail] = originY;
      tail++;
    }

    int columnsVisited = 0;
    int columnsReachable = 0;

    while (head < tail) {
      int x = queueX[head];
      int z = queueZ[head];
      int y = queueY[head];
      head++;
      columnsVisited++;

      int dx = x - originX;
      int dz = z - originZ;
      if (dx * dx + dz * dz > maxRadiusSq) continue;

      reachable.put(packXZ(x, z), y);
      columnsReachable++;

      for (int[] dir : DIRS) {
        int nx = x + dir[0];
        int nz = z + dir[1];
        long nKey = packXZ(nx, nz);
        if (visited.contains(nKey)) continue;
        visited.add(nKey);

        int walkY = findWalkableY(level, mutable, nx, nz, y, STEP_DELTA);
        if (walkY != Integer.MIN_VALUE) {
          // Don't escape to surface: skip columns at or above the heightmap
          int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz);
          if (walkY >= surfaceY - 1) continue;
          if (tail >= capacity) {
            capacity *= 2;
            queueX = Arrays.copyOf(queueX, capacity);
            queueZ = Arrays.copyOf(queueZ, capacity);
            queueY = Arrays.copyOf(queueY, capacity);
          }
          queueX[tail] = nx;
          queueZ[tail] = nz;
          queueY[tail] = walkY;
          tail++;
        }
      }
    }

    long elapsed = System.nanoTime() - startTime;
    return new Result(reachable, columnsVisited, columnsReachable, elapsed);
  }

  /**
   * Checks Y offsets from nearY within the step delta for a 2-block passable
   * gap. Checks nearY first (no step), then alternates up/down.
   *
   * @return the Y of the walkable gap, or Integer.MIN_VALUE if none found
   */
  private static int findWalkableY(
      Level level, BlockPos.MutableBlockPos mutable,
      int x, int z, int nearY, int stepDelta) {

    // Check exact Y first (most common: flat ground)
    if (isPassableAt(level, mutable, x, nearY, z)) return nearY;

    // Check offsets: ±1, ±2, etc.
    for (int dy = 1; dy <= stepDelta; dy++) {
      if (isPassableAt(level, mutable, x, nearY + dy, z)) return nearY + dy;
      if (isPassableAt(level, mutable, x, nearY - dy, z)) return nearY - dy;
    }

    return Integer.MIN_VALUE;
  }

  /** Returns true if a player could stand at (x, y, z): both feet and head passable. */
  private static boolean isPassableAt(
      Level level, BlockPos.MutableBlockPos mutable, int x, int y, int z) {
    mutable.set(x, y, z);
    if (!level.hasChunkAt(mutable)) return false;
    if (!isCavePassable(level.getBlockState(mutable))) return false;
    mutable.setY(y + 1);
    return isCavePassable(level.getBlockState(mutable));
  }

  private static boolean isCavePassable(BlockState state) {
    return !state.blocksMotion();
  }

  /** Packs block X and Z into a single long key for hash lookups. */
  static long packXZ(int x, int z) {
    return ((long) x << 32) | (z & 0xFFFFFFFFL);
  }
}
