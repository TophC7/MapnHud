package dev.foxmap.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Composites the visible 128×128 pixel region from cached chunk color grids.
 *
 * <p>Each frame, determines which chunks overlap the viewport centered on the
 * player's integer block position, then copies the relevant pixels from each
 * chunk's pre-computed ABGR color array directly into the {@link NativeImage}.
 *
 * <p>Uncached chunks (not yet scanned) are rendered as dark gray placeholders.
 * This produces a natural "map fills in" effect on world load.
 */
public class MinimapAssembler {

  public static final int MAP_SIZE = 128;
  private static final int HALF = MAP_SIZE / 2;

  /** Dark gray placeholder for chunks not yet scanned (ABGR format). */
  private static final int PLACEHOLDER = ChunkScanner.abgrFromArgb(0xFF1A1A1A);

  /**
   * Assemble the visible area into the given image.
   *
   * @param image     128×128 NativeImage to write into (ABGR format)
   * @param cache     the chunk color cache
   * @param centerX   player's block X (integer, not interpolated since smooth
   *                  scrolling is handled by the renderer via quad offset)
   * @param centerZ   player's block Z
   */
  public void assemble(NativeImage image, ChunkColorCache cache, int centerX, int centerZ) {
    // World-space bounding box of the visible area
    int minWorldX = centerX - HALF;
    int minWorldZ = centerZ - HALF;

    // Which chunks overlap the viewport
    int minChunkX = minWorldX >> 4;
    int maxChunkX = (minWorldX + MAP_SIZE - 1) >> 4;
    int minChunkZ = minWorldZ >> 4;
    int maxChunkZ = (minWorldZ + MAP_SIZE - 1) >> 4;

    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        ChunkColorData data = cache.get(cx, cz);
        int chunkWorldX = cx << 4;
        int chunkWorldZ = cz << 4;

        // Overlap between this chunk's 16×16 area and the viewport
        int overlapStartX = Math.max(chunkWorldX, minWorldX);
        int overlapEndX = Math.min(chunkWorldX + 16, minWorldX + MAP_SIZE);
        int overlapStartZ = Math.max(chunkWorldZ, minWorldZ);
        int overlapEndZ = Math.min(chunkWorldZ + 16, minWorldZ + MAP_SIZE);

        for (int wx = overlapStartX; wx < overlapEndX; wx++) {
          for (int wz = overlapStartZ; wz < overlapEndZ; wz++) {
            int pixelX = wx - minWorldX;
            int pixelZ = wz - minWorldZ;

            int color;
            if (data != null) {
              int localX = wx - chunkWorldX;
              int localZ = wz - chunkWorldZ;
              color = data.getColor(localX, localZ);
            } else {
              color = PLACEHOLDER;
            }

            image.setPixelRGBA(pixelX, pixelZ, color);
          }
        }
      }
    }
  }
}
