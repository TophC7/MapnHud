package dev.mapnhud.client.map.cave;

import dev.mapnhud.client.map.ChunkColorData;
import java.util.Arrays;

/**
 * Multi-layer storage for one chunk's terrain data: a single surface layer
 * plus zero or more cave layers keyed by deterministic 16-block Y buckets.
 *
 * <p>Cave bucket keys come from {@link #caveBucketKey(int)} so the same
 * world Y always maps to the same layer regardless of approach direction.
 * The surface layer uses the sentinel {@link #SURFACE_LAYER}.
 *
 * <p>Each entry holds at most {@link #MAX_CAVE_LAYERS} cave layers plus
 * one surface layer. New cave inserts evict the layer farthest from the
 * player's current Y when the cap is reached.
 *
 * <p>The composite cave view fills unknown columns from adjacent layers
 * (nearest first) so known terrain from any layer is never hidden behind
 * black walls. The composite is cached and only recomputed when the entry
 * version changes or the player crosses a bucket boundary.
 */
public final class CaveLayeredEntry {

  /** Sentinel layer Y for the single surface layer. */
  public static final int SURFACE_LAYER = Integer.MAX_VALUE;

  /** Maximum number of cave Y-buckets per chunk. */
  public static final int MAX_CAVE_LAYERS = 4;

  /** Bucket size in blocks. Cave layers snap to multiples of this. */
  private static final int CAVE_BUCKET = 16;

  private final ChunkColorData[] layers = new ChunkColorData[MAX_CAVE_LAYERS + 1];
  private final int[] layerYs = new int[MAX_CAVE_LAYERS + 1];
  private int count = 0;
  private int chunkVersion = 0;

  // Composite view caching to avoid per-frame allocations
  private ChunkColorData cachedComposite;
  private int cachedBucketY = Integer.MIN_VALUE;
  private int cachedVersion = -1;

  public CaveLayeredEntry() {
    Arrays.fill(layerYs, Integer.MIN_VALUE);
  }

  /** Returns the deterministic cave bucket key for a world Y. */
  public static int caveBucketKey(int y) {
    return Math.floorDiv(y, CAVE_BUCKET) * CAVE_BUCKET;
  }

  public ChunkColorData getSurface() {
    return getAtBucket(SURFACE_LAYER);
  }

  /**
   * Returns a composite cave view that fills unknown columns from adjacent
   * layers. Uses the target bucket as primary, then fills gaps from nearby
   * cave layers nearest-first. If the active bucket has never been scanned,
   * returns null rather than showing a different Y slice.
   */
  public ChunkColorData getCaveComposite(int playerY) {
    int bucketY = caveBucketKey(playerY);

    if (cachedComposite != null
        && cachedBucketY == bucketY
        && cachedVersion == chunkVersion) {
      return cachedComposite;
    }

    ChunkColorData primary = getAtBucket(bucketY);
    if (primary == null) return null;

    // Don't borrow an entire chunk from another bucket. Cross-bucket fill
    // is only useful for partial overlap around vertical transitions.
    if (primary.knownCount() == 0
        || primary.knownCount() == ChunkColorData.PIXELS) {
      cachedComposite = primary;
    } else {
      // Max 4 cave layers, so inline nearest selection beats List+sort.
      ChunkColorData result = primary;
      boolean[] used = null;

      for (int pass = 0; pass < MAX_CAVE_LAYERS; pass++) {
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
          if (layerYs[i] == SURFACE_LAYER || layerYs[i] == bucketY) continue;
          if (Math.abs(layerYs[i] - bucketY) > CAVE_BUCKET) continue;
          if (used != null && used[i]) continue;
          int dist = Math.abs(layerYs[i] - playerY);
          if (dist < bestDist) {
            bestDist = dist;
            bestIdx = i;
          }
        }
        if (bestIdx < 0) break;

        if (used == null) used = new boolean[count];
        used[bestIdx] = true;
        result = ChunkColorData.fillGaps(result, layers[bestIdx]);
        if (result.knownCount() == ChunkColorData.PIXELS) break;
      }
      cachedComposite = result;
    }

    cachedBucketY = bucketY;
    cachedVersion = chunkVersion;
    return cachedComposite;
  }

  public ChunkColorData getAtBucket(int bucketY) {
    for (int i = 0; i < count; i++) {
      if (layerYs[i] == bucketY) return layers[i];
    }
    return null;
  }

  /**
   * Stores data at the given bucket. Replaces any existing layer at that
   * bucket. Only bumps the version when the stored reference changes, so
   * identity re-puts don't invalidate the composite cache.
   *
   * <p>When at capacity and inserting a new bucket, evicts the cave layer
   * farthest from the incoming Y. This guarantees {@code put} never silently
   * drops data, which matters at load time when persisted layers may exceed
   * the runtime cap.
   */
  public void put(int bucketY, ChunkColorData data) {
    for (int i = 0; i < count; i++) {
      if (layerYs[i] == bucketY) {
        if (layers[i] != data) {
          layers[i] = data;
          chunkVersion++;
        }
        return;
      }
    }

    if (count >= layers.length) {
      // Surface layer always survives. Evict the cave layer farthest from
      // the incoming bucket so the entry stays clustered around recent work.
      int reference = bucketY == SURFACE_LAYER ? 0 : bucketY;
      evictFarthestCave(reference);
    }

    layerYs[count] = bucketY;
    layers[count] = data;
    count++;
    chunkVersion++;
  }

  /** Returns the number of cave layers (excluding the surface layer). */
  public int caveLayerCount() {
    int n = 0;
    for (int i = 0; i < count; i++) {
      if (layerYs[i] != SURFACE_LAYER) n++;
    }
    return n;
  }

  /**
   * Evicts the cave layer (never the surface layer) farthest from the given
   * reference Y. No-op if there are no cave layers. Layer order is irrelevant
   * because lookup is linear, so eviction uses swap-with-last.
   */
  public void evictFarthestCave(int referenceY) {
    int farthestIdx = -1;
    int maxDist = -1;

    for (int i = 0; i < count; i++) {
      if (layerYs[i] == SURFACE_LAYER) continue;
      int dist = Math.abs(layerYs[i] - referenceY);
      if (dist > maxDist) {
        maxDist = dist;
        farthestIdx = i;
      }
    }

    if (farthestIdx == -1) return;

    chunkVersion++;
    int last = count - 1;
    layers[farthestIdx] = layers[last];
    layerYs[farthestIdx] = layerYs[last];
    layers[last] = null;
    layerYs[last] = Integer.MIN_VALUE;
    count = last;
  }

  /** Iterates each present (layerY, data) pair. Used by persistence. */
  public void forEachLayer(LayerVisitor visitor) {
    for (int i = 0; i < count; i++) {
      visitor.visit(layerYs[i], layers[i]);
    }
  }

  @FunctionalInterface
  public interface LayerVisitor {
    void visit(int layerY, ChunkColorData data);
  }
}
