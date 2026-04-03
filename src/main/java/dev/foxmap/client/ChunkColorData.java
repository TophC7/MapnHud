package dev.foxmap.client;

/**
 * Pre-computed terrain colors and heights for a single 16×16 chunk column.
 *
 * <p>Colors are stored in ABGR format (NativeImage-native) so the assembler
 * can write them directly without per-pixel conversion. Heights are stored
 * alongside colors because cross-chunk elevation shading needs the neighbor
 * chunk's southern row heights.
 *
 * <p>Indexed as {@code [localX * 16 + localZ]}. X is the outer axis to
 * match the north-to-south (Z) scanning order used by elevation shading.
 */
public final class ChunkColorData {

  public static final int SIZE = 16;
  public static final int PIXELS = SIZE * SIZE;

  private final int[] colors;  // ABGR, length 256
  private final int[] heights; // WORLD_SURFACE heights, length 256
  private final long scannedAtTick;
  private boolean bordersDirty;

  public ChunkColorData(int[] colors, int[] heights, long scannedAtTick) {
    this.colors = colors;
    this.heights = heights;
    this.scannedAtTick = scannedAtTick;
    this.bordersDirty = true;
  }

  public int getColor(int localX, int localZ) {
    return colors[localX * SIZE + localZ];
  }

  public void setColor(int localX, int localZ, int abgr) {
    colors[localX * SIZE + localZ] = abgr;
  }

  public int getHeight(int localX, int localZ) {
    return heights[localX * SIZE + localZ];
  }

  public long scannedAtTick() {
    return scannedAtTick;
  }

  public boolean isBordersDirty() {
    return bordersDirty;
  }

  public void setBordersDirty(boolean dirty) {
    this.bordersDirty = dirty;
  }
}
