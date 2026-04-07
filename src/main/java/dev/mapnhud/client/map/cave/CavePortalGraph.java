package dev.mapnhud.client.map.cave;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;

/**
 * Incremental chunk-portal graph for high-level cave reachability pruning.
 */
public final class CavePortalGraph {

  private final Long2ObjectOpenHashMap<CaveChunkNavSnapshot> snapshots = new Long2ObjectOpenHashMap<>();
  private final Long2ObjectOpenHashMap<LongOpenHashSet> adjacency = new Long2ObjectOpenHashMap<>();
  private final int stepDelta;

  public CavePortalGraph() {
    this(CaveChunkNavBuilder.DEFAULT_STEP_DELTA);
  }

  public CavePortalGraph(int stepDelta) {
    this.stepDelta = stepDelta;
  }

  public void clear() {
    snapshots.clear();
    adjacency.clear();
  }

  public int snapshotCount() {
    return snapshots.size();
  }

  public boolean containsChunk(int chunkX, int chunkZ) {
    return snapshots.containsKey(ChunkPos.asLong(chunkX, chunkZ));
  }

  public void upsert(CaveChunkNavSnapshot snapshot) {
    snapshots.put(snapshot.chunkKey(), snapshot);
    rebuildChunkAndNeighbors(snapshot.chunkX(), snapshot.chunkZ());
  }

  public void remove(int chunkX, int chunkZ) {
    long key = ChunkPos.asLong(chunkX, chunkZ);
    if (snapshots.remove(key) == null) return;
    adjacency.remove(key);

    for (LongOpenHashSet neighbors : adjacency.values()) {
      neighbors.remove(key);
    }
    rebuildChunkAndNeighbors(chunkX, chunkZ);
  }

  public ChunkReachability floodReachableChunks(
      int originChunkX, int originChunkZ, int originY, int maxRadiusChunks) {
    long originKey = ChunkPos.asLong(originChunkX, originChunkZ);
    CaveChunkNavSnapshot origin = snapshots.get(originKey);
    if (origin == null) {
      LongOpenHashSet unknown = new LongOpenHashSet();
      unknown.add(originKey);
      return new ChunkReachability(LongSets.unmodifiable(new LongOpenHashSet()), LongSets.unmodifiable(unknown));
    }

    if (origin.standableCount() == 0 || originY < origin.minY() || originY > origin.maxY()) {
      return new ChunkReachability(
          LongSets.unmodifiable(new LongOpenHashSet()),
          LongSets.unmodifiable(new LongOpenHashSet()));
    }

    LongOpenHashSet reachable = new LongOpenHashSet();
    LongOpenHashSet unknown = new LongOpenHashSet();
    LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    reachable.add(originKey);
    queue.enqueue(originKey);

    while (!queue.isEmpty()) {
      long key = queue.dequeueLong();
      int chunkX = ChunkPos.getX(key);
      int chunkZ = ChunkPos.getZ(key);

      LongOpenHashSet linked = adjacency.get(key);
      if (linked != null) {
        for (long neighborKey : linked) {
          int nx = ChunkPos.getX(neighborKey);
          int nz = ChunkPos.getZ(neighborKey);
          if (!withinRadius(originChunkX, originChunkZ, nx, nz, maxRadiusChunks)) continue;
          if (reachable.add(neighborKey)) {
            queue.enqueue(neighborKey);
          }
        }
      }

      for (CaveDirection direction : CaveDirection.values()) {
        int nx = chunkX + direction.dx();
        int nz = chunkZ + direction.dz();
        if (!withinRadius(originChunkX, originChunkZ, nx, nz, maxRadiusChunks)) continue;
        long nKey = ChunkPos.asLong(nx, nz);
        if (!snapshots.containsKey(nKey)) {
          unknown.add(nKey);
        }
      }
    }

    return new ChunkReachability(LongSets.unmodifiable(reachable), LongSets.unmodifiable(unknown));
  }

  private static boolean withinRadius(int ox, int oz, int x, int z, int radius) {
    int dx = x - ox;
    int dz = z - oz;
    return dx * dx + dz * dz <= radius * radius;
  }

  private void rebuildChunkAndNeighbors(int chunkX, int chunkZ) {
    rebuildChunk(chunkX, chunkZ);
    for (CaveDirection direction : CaveDirection.values()) {
      rebuildChunk(chunkX + direction.dx(), chunkZ + direction.dz());
    }
  }

  private void rebuildChunk(int chunkX, int chunkZ) {
    long key = ChunkPos.asLong(chunkX, chunkZ);
    CaveChunkNavSnapshot snapshot = snapshots.get(key);
    if (snapshot == null) {
      adjacency.remove(key);
      return;
    }

    LongOpenHashSet neighbors = adjacency.computeIfAbsent(key, k -> new LongOpenHashSet());
    neighbors.clear();
    for (CaveDirection direction : CaveDirection.values()) {
      int nx = chunkX + direction.dx();
      int nz = chunkZ + direction.dz();
      long nKey = ChunkPos.asLong(nx, nz);
      CaveChunkNavSnapshot neighbor = snapshots.get(nKey);
      if (neighbor == null) continue;
      if (connected(snapshot, neighbor, direction)) {
        neighbors.add(nKey);
      }
    }
    if (neighbors.isEmpty()) {
      adjacency.remove(key);
    }
  }

  private boolean connected(
      CaveChunkNavSnapshot current, CaveChunkNavSnapshot neighbor, CaveDirection edgeDirection) {
    CaveDirection opposite = edgeDirection.opposite();
    for (int edgeOffset = 0; edgeOffset < 16; edgeOffset++) {
      CavePortalBand[] currentBands = current.portals(edgeDirection, edgeOffset);
      if (currentBands.length == 0) continue;
      CavePortalBand[] neighborBands = neighbor.portals(opposite, edgeOffset);
      if (neighborBands.length == 0) continue;

      for (CavePortalBand currentBand : currentBands) {
        for (CavePortalBand neighborBand : neighborBands) {
          if (currentBand.connectsTo(neighborBand, stepDelta)) return true;
        }
      }
    }
    return false;
  }

  public record ChunkReachability(LongSet reachableChunks, LongSet unknownChunks) {}
}
