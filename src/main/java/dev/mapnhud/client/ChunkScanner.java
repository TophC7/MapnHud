package dev.mapnhud.client;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Stateless scanner that reads a chunk's terrain and produces a {@link ChunkColorData}.
 *
 * <p>Uses {@link BlockColors} for per-block tint accuracy and
 * {@link BlockColorExtractor} for texture-based colors. Ground-cover noise
 * (short grass, ferns) is skipped in favor of the block underneath.
 *
 * <p>Water columns are rendered as the floor block blended with a biome-tinted
 * water overlay, with opacity scaling by depth.
 */
public final class ChunkScanner {

  private ChunkScanner() {}

  // -- Tuning constants --

  /** Leaves are darkened relative to surrounding terrain so trees are visible. */
  private static final float LEAF_SHADE = 0.75f;

  /** Per-block height factor for sea-level-relative terrain shading. */
  private static final float HEIGHT_FACTOR = 0.012f;
  private static final float HEIGHT_MOD_MIN = 0.78f;
  private static final float HEIGHT_MOD_MAX = 1.15f;

  /** Water overlay alpha ramp: alpha = min(MAX, BASE + depth * PER_DEPTH). */
  private static final float WATER_ALPHA_BASE = 0.55f;
  private static final float WATER_ALPHA_PER_DEPTH = 0.04f;
  private static final float WATER_ALPHA_MAX = 0.82f;

  /** Edge shading dither amplitude and thresholds. */
  private static final double DITHER_AMPLITUDE = 0.4;
  private static final double EDGE_THRESH_HIGH = 0.6;
  private static final double EDGE_THRESH_LOW = -0.3;
  private static final double EDGE_THRESH_LOWEST = -0.6;

  private static final Set<Block> SKIP_BLOCKS = Set.of(
      Blocks.SHORT_GRASS,
      Blocks.TALL_GRASS,
      Blocks.FERN,
      Blocks.LARGE_FERN,
      Blocks.DEAD_BUSH
  );

  /**
   * Result of scanning a single column. Captures the floor block and water info.
   * When {@code waterDepth > 0}, the block is underwater and {@code waterSurfaceY}
   * holds the Y of the topmost water block for biome tint lookup.
   */
  private record ColumnResult(BlockState block, int blockY, int waterDepth, int waterSurfaceY) {
    boolean isWater() { return waterDepth > 0; }
    boolean isEmpty() { return block.isAir(); }
  }

  /**
   * Scans a chunk and produces shaded terrain colors. If the north neighbor's
   * cached data is available, row 0 gets correct cross-chunk edge shading
   * immediately. Otherwise row 0 uses NORMAL brightness and will be corrected
   * when this chunk is re-scanned after the neighbor is cached.
   *
   * @param northNeighbor cached data for the chunk at (cx, cz-1), or null
   */
  public static ChunkColorData scan(
      ChunkAccess chunk, Level level,
      ChunkColorData northNeighbor) {

    int[] colors = new int[ChunkColorData.PIXELS];
    int[] heights = new int[ChunkColorData.PIXELS];

    int chunkWorldX = chunk.getPos().getMinBlockX();
    int chunkWorldZ = chunk.getPos().getMinBlockZ();

    // Global baseline eliminates brightness seams at chunk boundaries
    int seaLevel = level.getSeaLevel();

    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = chunkWorldX + localX;
      // Seed prevHeight from the north neighbor's row 15 if available
      int prevHeight = (northNeighbor != null)
          ? northNeighbor.getHeight(localX, 15)
          : 0;

      for (int localZ = 0; localZ < 16; localZ++) {
        int worldZ = chunkWorldZ + localZ;
        int idx = localX * 16 + localZ;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);

        mutable.set(worldX, surfaceY, worldZ);
        ColumnResult col = scanColumn(level, mutable, surfaceY);

        int renderedY = col.blockY;
        heights[idx] = renderedY;

        if (col.isEmpty()) {
          colors[idx] = abgrFromArgb(0xFF000000);
          prevHeight = renderedY;
          continue;
        }

        mutable.set(worldX, renderedY, worldZ);
        int baseRgb = getBlockColor(blockColors, level, mutable, col.block);
        if (baseRgb == 0) {
          colors[idx] = abgrFromArgb(0xFF000000);
          prevHeight = renderedY;
          continue;
        }

        // Without north neighbor data, prevHeight defaults to 0 which
        // creates a large delta against renderedY (~64), triggering HIGH.
        // Force NORMAL to avoid a bright seam on row 0.
        MapColor.Brightness edgeBrightness;
        if (localZ == 0 && northNeighbor == null) {
          edgeBrightness = MapColor.Brightness.NORMAL;
        } else {
          edgeBrightness = computeElevationBrightness(renderedY, prevHeight, worldX, worldZ);
        }

        colors[idx] = abgrFromArgb(
            shadeColumn(baseRgb, col, renderedY, seaLevel, edgeBrightness,
                level, mutable, worldX));
        prevHeight = renderedY;
      }
    }

    return new ChunkColorData(colors, heights);
  }

  /**
   * Applies the full shading pipeline to a scanned column: height-relative
   * brightness, leaf darkening, edge shading, and water overlay.
   */
  private static int shadeColumn(
      int baseRgb, ColumnResult col, int renderedY, int seaLevel,
      MapColor.Brightness edgeBrightness, Level level,
      BlockPos.MutableBlockPos mutable, int worldX) {

    if (col.isWater()) {
      // Underwater: skip height modifier (water overlay communicates depth
      // instead). Apply edge shading to the floor, then blend water on top.
      int shadedFloor = applyShading(baseRgb, 1.0f, edgeBrightness.modifier);
      mutable.set(worldX, col.waterSurfaceY, mutable.getZ());
      int waterTint = level.getBlockTint(mutable, BiomeColors.WATER_COLOR_RESOLVER);
      return blendWaterColor(shadedFloor, waterTint, col.waterDepth);
    }

    float heightMod = computeHeightModifier(renderedY, seaLevel);
    if (col.block.getBlock() instanceof LeavesBlock) {
      heightMod *= LEAF_SHADE;
    }

    return applyShading(baseRgb, heightMod, edgeBrightness.modifier);
  }

  /**
   * Scans a column from the surface downward, tracking water depth and finding
   * the first meaningful solid block. Pure water blocks increment depth and are
   * scanned through. Waterlogged blocks like seagrass count as the floor but
   * are still considered "under water" for tinting purposes.
   */
  private static ColumnResult scanColumn(
      Level level, BlockPos.MutableBlockPos mutable, int startY) {

    int waterDepth = 0;
    int waterSurfaceY = -1;

    for (int y = startY; y >= level.getMinBuildHeight(); y--) {
      mutable.setY(y);
      BlockState state = level.getBlockState(mutable);

      if (state.isAir()) continue;
      if (state.getMapColor(level, mutable) == MapColor.NONE) continue;

      if (state.is(Blocks.WATER)) {
        if (waterDepth == 0) waterSurfaceY = y;
        waterDepth++;
        continue;
      }

      if (SKIP_BLOCKS.contains(state.getBlock())) continue;

      // Waterlogged blocks (seagrass, kelp) are the visible floor under water.
      // Only water fluid triggers this, not lava or other fluids.
      if (waterDepth == 0 && state.getFluidState().is(FluidTags.WATER)) {
        waterSurfaceY = y;
        waterDepth = 1;
      }

      return new ColumnResult(state, y, waterDepth, waterSurfaceY);
    }

    return new ColumnResult(Blocks.AIR.defaultBlockState(), startY, waterDepth, waterSurfaceY);
  }

  /**
   * Gets the display color for a block using the renderer's {@link BlockColors}
   * registry for tint accuracy. Tinted blocks get texture x tint multiplication.
   */
  private static int getBlockColor(
      BlockColors blockColors, Level level, BlockPos pos, BlockState state) {

    Block block = state.getBlock();
    int tint = blockColors.getColor(state, level, pos, 0);

    if (tint != -1) {
      return multiplyTintWithTexture(block, tint);
    }

    int texColor = BlockColorExtractor.getColor(block);
    if (texColor != -1) {
      return texColor;
    }

    MapColor mapColor = state.getMapColor(level, pos);
    if (mapColor != MapColor.NONE) {
      return 0xFF000000 | mapColor.col;
    }

    return 0;
  }

  /**
   * Multiplies a tint color with the block's average texture color, replicating
   * what the GPU shader does at render time.
   */
  private static int multiplyTintWithTexture(Block block, int tint) {
    int tintR = (tint >> 16) & 0xFF;
    int tintG = (tint >> 8) & 0xFF;
    int tintB = tint & 0xFF;

    int texColor = BlockColorExtractor.getColor(block);
    if (texColor != -1) {
      int texR = (texColor >> 16) & 0xFF;
      int texG = (texColor >> 8) & 0xFF;
      int texB = texColor & 0xFF;

      int r = tintR * texR / 255;
      int g = tintG * texG / 255;
      int b = tintB * texB / 255;
      return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Approximate grayscale texture at ~60% brightness
    return 0xFF000000
        | ((tintR * 153 / 255) << 16)
        | ((tintG * 153 / 255) << 8)
        | (tintB * 153 / 255);
  }

  /**
   * Alpha-blends the floor block color with a biome water tint. Deeper water
   * shows more blue, shallow water lets the floor show through.
   */
  private static int blendWaterColor(int floorArgb, int waterTint, int depth) {
    float alpha = Math.min(WATER_ALPHA_MAX, WATER_ALPHA_BASE + depth * WATER_ALPHA_PER_DEPTH);

    int fR = (floorArgb >> 16) & 0xFF;
    int fG = (floorArgb >> 8) & 0xFF;
    int fB = floorArgb & 0xFF;

    int wR = (waterTint >> 16) & 0xFF;
    int wG = (waterTint >> 8) & 0xFF;
    int wB = waterTint & 0xFF;

    int r = (int) (fR * (1 - alpha) + wR * alpha);
    int g = (int) (fG * (1 - alpha) + wG * alpha);
    int b = (int) (fB * (1 - alpha) + wB * alpha);

    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static float computeHeightModifier(int height, int seaLevel) {
    int delta = height - seaLevel;
    float mod = 1.0f + delta * HEIGHT_FACTOR;
    return Math.max(HEIGHT_MOD_MIN, Math.min(HEIGHT_MOD_MAX, mod));
  }

  /**
   * Applies height modifier and edge brightness in a single channel pass,
   * avoiding redundant unpack/repack cycles.
   */
  private static int applyShading(int argb, float heightMod, int edgeMod) {
    float combined = heightMod * edgeMod / 255.0f;
    int a = (argb >> 24) & 0xFF;
    int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * combined));
    int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * combined));
    int b = Math.min(255, (int) ((argb & 0xFF) * combined));
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private static MapColor.Brightness computeElevationBrightness(
      int currentHeight, int prevHeight, int worldX, int worldZ) {

    double d3 = (double) (currentHeight - prevHeight)
        + (((worldX + worldZ) & 1) - 0.5) * DITHER_AMPLITUDE;

    if (d3 > EDGE_THRESH_HIGH) return MapColor.Brightness.HIGH;
    if (d3 < EDGE_THRESH_LOWEST) return MapColor.Brightness.LOWEST;
    if (d3 < EDGE_THRESH_LOW) return MapColor.Brightness.LOW;
    return MapColor.Brightness.NORMAL;
  }

  static int abgrFromArgb(int argb) {
    int a = (argb >> 24) & 0xFF;
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
  }
}
