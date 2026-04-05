package dev.mapnhud.client.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Event-driven cache of raw per-chunk column data for the minimap.
 *
 * <p>Stores unshaded base colors, heights, and water metadata. All shading
 * is deferred to the viewport assembler where the full visible heightfield
 * eliminates chunk boundary seams by design.
 *
 * <p>Supports two scan modes: surface (top-down from sky) and cave (flood-fill
 * reachability from player position). Switching modes triggers a full cache
 * clear, flood fill, and immediate rescan.
 *
 * <p>In cave mode, a {@link CaveFloodFill} determines which columns are
 * reachable from the player. The flood re-runs when the player moves
 * significantly (3+ blocks) or changes Y band. The flood result serves as
 * a "cave heightmap" that the scanner uses instead of WORLD_SURFACE.
 *
 * <p>Chunks are enqueued on {@code ChunkEvent.Load} and scanned at a rate
 * of {@link #CHUNKS_PER_TICK} per client tick to avoid frame drops when many
 * chunks arrive at once. The 3x3 area around the player is periodically
 * re-scanned (bypassing the queue) to pick up block changes from
 * building, mining, fire, and other real-time events.
 *
 * <p>Uses {@code Long2ObjectOpenHashMap} (fastutil, shipped with Minecraft) to
 * avoid ChunkPos allocation on every cache lookup.
 */
public final class ChunkColorCache {

  private static final int CHUNKS_PER_TICK = 4;
  private static final int RESCAN_PLAYER_CHUNK_INTERVAL = 4;
  private static final int RESCAN_ADJACENT_INTERVAL = 20;

  /** How far the player must move before cave flood is recomputed. */
  private static final int CAVE_REFLOOD_DISTANCE = 3;

  /** Y distance that triggers cave reflood (handles vertical movement in caves). */
  private static final int CAVE_REFLOOD_Y_THRESHOLD = 4;

  /** Flood fill radius in blocks (covers minimap visible area at 1x zoom). */
  private static final int CAVE_FLOOD_RADIUS = 100;

  private final Long2ObjectOpenHashMap<ChunkColorData> cache = new Long2ObjectOpenHashMap<>();
  private final ArrayDeque<ChunkAccess> scanQueue = new ArrayDeque<>();
  private int tickCounter = 0;
  private boolean dirty = false;

  // Cave mode state
  private boolean caveMode = false;
  private CaveFloodFill.Result floodResult = CaveFloodFill.EMPTY;
  private int lastFloodX = Integer.MIN_VALUE;
  private int lastFloodY = Integer.MIN_VALUE;
  private int lastFloodZ = Integer.MIN_VALUE;

  public void enqueueChunk(ChunkAccess chunk) {
    scanQueue.addLast(chunk);
  }

  public void evict(ChunkPos pos) {
    cache.remove(ChunkPos.asLong(pos.x, pos.z));
    dirty = true;
  }

  public void clearAll() {
    cache.clear();
    scanQueue.clear();
    tickCounter = 0;
    dirty = true;
  }

  /** Returns the last flood fill result (for debug display). */
  public CaveFloodFill.Result getFloodResult() {
    return floodResult;
  }

  /**
   * Process the scan queue and handle periodic re-scans.
   *
   * @param caveMode true to use flood-fill-based cave scanning
   * @return true if any chunk data was updated this tick
   */
  public boolean tick(Level level, BlockPos playerPos, boolean caveMode) {
    boolean modeChanged = caveMode != this.caveMode;
    this.caveMode = caveMode;

    if (modeChanged) {
      tickCounter = 0;
      floodResult = CaveFloodFill.EMPTY;
      lastFloodX = Integer.MIN_VALUE;
      cache.clear();
      scanQueue.clear();

      if (!caveMode) {
        // Leaving cave mode: rescan surface immediately
        rescanNearby(level, playerPos);
      }
      // Entering cave mode: maybeReflood below will flood + rescan
    }

    // In cave mode, re-flood when the player moves significantly
    boolean reflooded = false;
    if (caveMode) {
      reflooded = maybeReflood(level, playerPos);
    }

    dirty = modeChanged || reflooded;
    processQueue(level);
    if (!reflooded) {
      handlePeriodicRescan(level, playerPos);
    }
    tickCounter++;
    return dirty;
  }

  /** Lookup by long key; this avoids ChunkPos allocation in the assembler hot path. */
  public ChunkColorData get(int chunkX, int chunkZ) {
    return cache.get(ChunkPos.asLong(chunkX, chunkZ));
  }

  /**
   * Re-runs the flood fill if the player has moved far enough from the last
   * flood origin. Rescans the visible area with the new flood result.
   *
   * @return true if a reflood was triggered
   */
  private boolean maybeReflood(Level level, BlockPos playerPos) {
    int px = playerPos.getX();
    int py = playerPos.getY();
    int pz = playerPos.getZ();

    int dx = px - lastFloodX;
    int dy = py - lastFloodY;
    int dz = pz - lastFloodZ;
    int distXZSq = dx * dx + dz * dz;

    if (lastFloodX == Integer.MIN_VALUE
        || distXZSq > CAVE_REFLOOD_DISTANCE * CAVE_REFLOOD_DISTANCE
        || Math.abs(dy) > CAVE_REFLOOD_Y_THRESHOLD) {

      floodResult = CaveFloodFill.flood(level, playerPos, CAVE_FLOOD_RADIUS);
      lastFloodX = px;
      lastFloodY = py;
      lastFloodZ = pz;

      // Rescan visible area with the new flood result.
      // Old cache entries get overwritten, no clear needed (avoids map flash).
      scanQueue.clear();
      rescanNearby(level, playerPos);
      return true;
    }

    return false;
  }

  private void processQueue(Level level) {
    int processed = 0;

    while (processed < CHUNKS_PER_TICK && !scanQueue.isEmpty()) {
      ChunkAccess chunk = scanQueue.pollFirst();
      if (chunk == null) break;

      if (!(chunk instanceof LevelChunk)) {
        ChunkPos pos = chunk.getPos();
        if (level.hasChunk(pos.x, pos.z)) {
          chunk = level.getChunk(pos.x, pos.z);
          if (!(chunk instanceof LevelChunk)) {
            scanQueue.addLast(chunk);
            processed++;
            continue;
          }
        } else {
          processed++;
          continue;
        }
      }

      int cx = chunk.getPos().x;
      int cz = chunk.getPos().z;

      ChunkColorData data = scanChunk(chunk, level);
      cache.put(ChunkPos.asLong(cx, cz), data);
      dirty = true;
      processed++;
    }
  }

  private void handlePeriodicRescan(Level level, BlockPos playerPos) {
    int chunkX = playerPos.getX() >> 4;
    int chunkZ = playerPos.getZ() >> 4;

    // Player chunk scans immediately, bypassing the queue so block
    // changes are always visible within one rescan interval.
    if (tickCounter % RESCAN_PLAYER_CHUNK_INTERVAL == 0
        && level.hasChunk(chunkX, chunkZ)) {
      ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
      ChunkColorData data = scanChunk(chunk, level);
      cache.put(ChunkPos.asLong(chunkX, chunkZ), data);
      dirty = true;
    }

    // Adjacent chunks scan immediately (bypass queue) since scanning
    // raw column data is cheap. Keeps the queue free for new chunk loads.
    if (tickCounter % RESCAN_ADJACENT_INTERVAL == 0) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) continue;
          int nx = chunkX + dx;
          int nz = chunkZ + dz;
          if (level.hasChunk(nx, nz)) {
            ChunkColorData data = scanChunk(level.getChunk(nx, nz), level);
            cache.put(ChunkPos.asLong(nx, nz), data);
            dirty = true;
          }
        }
      }
    }
  }

  /** Scans a chunk using the current mode (surface or cave). */
  private ChunkColorData scanChunk(ChunkAccess chunk, Level level) {
    return caveMode
        ? ChunkScanner.scanCave(chunk, level, floodResult)
        : ChunkScanner.scan(chunk, level);
  }

  /**
   * Immediately scans a wide area around the player so the minimap isn't
   * blank while waiting for periodic rescans.
   */
  private void rescanNearby(Level level, BlockPos playerPos) {
    int cx = playerPos.getX() >> 4;
    int cz = playerPos.getZ() >> 4;
    int radius = CAVE_FLOOD_RADIUS / 16 + 1;

    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        int nx = cx + dx;
        int nz = cz + dz;
        if (level.hasChunk(nx, nz)) {
          cache.put(ChunkPos.asLong(nx, nz), scanChunk(level.getChunk(nx, nz), level));
        }
      }
    }
  }
}
