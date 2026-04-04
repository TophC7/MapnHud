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

  private final Long2ObjectOpenHashMap<ChunkColorData> cache = new Long2ObjectOpenHashMap<>();
  private final ArrayDeque<ChunkAccess> scanQueue = new ArrayDeque<>();
  private int tickCounter = 0;
  private boolean dirty = false;

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

  /**
   * Process the scan queue and handle periodic re-scans.
   * @return true if any chunk data was updated this tick
   */
  public boolean tick(Level level, BlockPos playerPos) {
    dirty = false;
    processQueue(level);
    handlePeriodicRescan(level, playerPos);
    tickCounter++;
    return dirty;
  }

  /** Lookup by long key; this avoids ChunkPos allocation in the assembler hot path. */
  public ChunkColorData get(int chunkX, int chunkZ) {
    return cache.get(ChunkPos.asLong(chunkX, chunkZ));
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

      ChunkColorData data = ChunkScanner.scan(chunk, level);
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
      ChunkColorData data = ChunkScanner.scan(chunk, level);
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
            ChunkColorData data = ChunkScanner.scan(level.getChunk(nx, nz), level);
            cache.put(ChunkPos.asLong(nx, nz), data);
            dirty = true;
          }
        }
      }
    }
  }
}
