package dev.foxmap.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Composites the visible 128x128 pixel region from cached chunk color grids.
 *
 * <p>Supports a scale factor for zoom: at scale 1, each pixel maps to one block
 * (128 blocks visible). At scale 2, each pixel samples every 2nd block (256
 * blocks visible), and so on.
 *
 * <p>Uncached chunks are rendered as dark gray placeholders.
 */
public class MinimapAssembler {

  public static final int MAP_SIZE = 128;
  private static final int HALF = MAP_SIZE / 2;

  /** Dark gray placeholder for chunks not yet scanned (ABGR format). */
  private static final int PLACEHOLDER = ChunkScanner.abgrFromArgb(0xFF1A1A1A);

  /**
   * Assemble the visible area into the given image.
   *
   * @param image     128x128 NativeImage to write into (ABGR format)
   * @param cache     the chunk color cache
   * @param centerX   player's block X
   * @param centerZ   player's block Z
   * @param scale     blocks per pixel (1 = normal, 2 = zoomed out 2x, etc.)
   */
  public void assemble(
      NativeImage image, ChunkColorCache cache,
      int centerX, int centerZ, int scale) {

    int halfWorld = HALF * scale;
    int minWorldX = centerX - halfWorld;
    int minWorldZ = centerZ - halfWorld;

    // Track last chunk to avoid redundant cache lookups.
    // The inner Z loop often stays within the same chunk.
    int lastCx = Integer.MIN_VALUE;
    int lastCz = Integer.MIN_VALUE;
    ChunkColorData lastData = null;

    for (int px = 0; px < MAP_SIZE; px++) {
      int wx = minWorldX + px * scale;
      int cx = wx >> 4;

      for (int pz = 0; pz < MAP_SIZE; pz++) {
        int wz = minWorldZ + pz * scale;
        int cz = wz >> 4;

        if (cx != lastCx || cz != lastCz) {
          lastData = cache.get(cx, cz);
          lastCx = cx;
          lastCz = cz;
        }

        int color;
        if (lastData != null) {
          color = lastData.getColor(wx & 15, wz & 15);
        } else {
          color = PLACEHOLDER;
        }

        image.setPixelRGBA(px, pz, color);
      }
    }
  }
}
