package dev.mapnhud.client.map;

/**
 * Raw terrain column data for a single 16x16 chunk, collected at scan time.
 *
 * <p>No shading is applied here. Colors are base ARGB values straight from
 * the block color pipeline. Shading happens later in the viewport assembler
 * where the full visible heightfield is available, eliminating chunk boundary
 * seams by design.
 *
 * <p>Indexed as {@code [localX * 16 + localZ]}. X is the outer axis to
 * match the north-to-south (Z) scanning order used by the assembler's
 * elevation shading.
 */
public final class ChunkColorData {

  public static final int SIZE = 16;
  public static final int PIXELS = SIZE * SIZE;

  private final int[] baseColors;   // ARGB, unshaded
  private final int[] heights;      // rendered block Y (after skip-block resolution)
  private final int[] waterDepths;  // 0 = dry, >0 = water block count
  private final int[] waterTints;   // biome water ARGB (0 when dry)
  private final boolean[] isLeaf;   // true when rendered block is LeavesBlock

  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf) {
    this.baseColors = baseColors;
    this.heights = heights;
    this.waterDepths = waterDepths;
    this.waterTints = waterTints;
    this.isLeaf = isLeaf;
  }

  public int getBaseColor(int localX, int localZ) {
    return baseColors[localX * SIZE + localZ];
  }

  public int getHeight(int localX, int localZ) {
    return heights[localX * SIZE + localZ];
  }

  public int getWaterDepth(int localX, int localZ) {
    return waterDepths[localX * SIZE + localZ];
  }

  public int getWaterTint(int localX, int localZ) {
    return waterTints[localX * SIZE + localZ];
  }

  public boolean isLeaf(int localX, int localZ) {
    return isLeaf[localX * SIZE + localZ];
  }
}
