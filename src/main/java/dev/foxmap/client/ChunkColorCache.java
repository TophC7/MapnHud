package dev.foxmap.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Event-driven cache of per-chunk terrain colors for the minimap.
 *
 * <p>Chunks are enqueued on {@code ChunkEvent.Load} and scanned at a rate
 * of {@link #CHUNKS_PER_TICK} per client tick to avoid frame drops when many
 * chunks arrive at once. The player's chunk is periodically re-scanned to
 * pick up block changes from building/mining.
 *
 * <p>Uses {@code Long2ObjectOpenHashMap} (fastutil, shipped with Minecraft) to
 * avoid ChunkPos allocation on every cache lookup.
 */
public class ChunkColorCache {

  private static final int CHUNKS_PER_TICK = 2;
  private static final int RESCAN_PLAYER_CHUNK_INTERVAL = 40;
  private static final int RESCAN_ADJACENT_INTERVAL = 100;

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

      ChunkColorData data = ChunkScanner.scan(chunk, level, level.getGameTime());
      int cx = chunk.getPos().x;
      int cz = chunk.getPos().z;
      cache.put(ChunkPos.asLong(cx, cz), data);
      dirty = true;

      // Fix border shading with neighbors
      ChunkColorData northData = cache.get(ChunkPos.asLong(cx, cz - 1));
      if (northData != null) {
        ChunkScanner.fixBorderShading(data, northData, level, chunk);
      }

      ChunkColorData southData = cache.get(ChunkPos.asLong(cx, cz + 1));
      if (southData != null && southData.isBordersDirty()) {
        if (level.hasChunk(cx, cz + 1)) {
          ChunkAccess southChunk = level.getChunk(cx, cz + 1);
          ChunkScanner.fixBorderShading(southData, data, level, southChunk);
        }
      }

      processed++;
    }
  }

  private void handlePeriodicRescan(Level level, BlockPos playerPos) {
    int chunkX = playerPos.getX() >> 4;
    int chunkZ = playerPos.getZ() >> 4;

    if (tickCounter % RESCAN_PLAYER_CHUNK_INTERVAL == 0) {
      enqueueRescan(level, chunkX, chunkZ);
    }

    if (tickCounter % RESCAN_ADJACENT_INTERVAL == 0) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) continue;
          enqueueRescan(level, chunkX + dx, chunkZ + dz);
        }
      }
    }
  }

  private void enqueueRescan(Level level, int chunkX, int chunkZ) {
    if (level.hasChunk(chunkX, chunkZ)) {
      scanQueue.addLast(level.getChunk(chunkX, chunkZ));
    }
  }
}
