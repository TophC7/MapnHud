package dev.mapnhud.client.map;

/**
 * Pre-computed terrain colors and heights for a single 16×16 chunk column.
 *
 * <p>Colors are stored in ABGR format (NativeImage-native) so the assembler
 * can write them directly without per-pixel conversion. Heights are the
 * rendered block Y (after skip-block resolution), used by neighboring chunks
 * for cross-chunk edge shading during their scan pass.
 *
 * <p>Indexed as {@code [localX * 16 + localZ]}. X is the outer axis to
 * match the north-to-south (Z) scanning order used by elevation shading.
 */
public final class ChunkColorData {

  public static final int SIZE = 16;
  public static final int PIXELS = SIZE * SIZE;

  private final int[] colors;  // ABGR, length 256
  private final int[] heights; // WORLD_SURFACE heights, length 256

  public ChunkColorData(int[] colors, int[] heights) {
    this.colors = colors;
    this.heights = heights;
  }

  public int getColor(int localX, int localZ) {
    return colors[localX * SIZE + localZ];
  }

  public int getHeight(int localX, int localZ) {
    return heights[localX * SIZE + localZ];
  }
}
