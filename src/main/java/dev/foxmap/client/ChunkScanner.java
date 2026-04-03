package dev.foxmap.client;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
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
 */
public final class ChunkScanner {

  private ChunkScanner() {}

  private static final Set<Block> SKIP_BLOCKS = Set.of(
      Blocks.SHORT_GRASS,
      Blocks.TALL_GRASS,
      Blocks.FERN,
      Blocks.LARGE_FERN,
      Blocks.DEAD_BUSH
  );

  public static ChunkColorData scan(ChunkAccess chunk, Level level, long gameTick) {
    int[] colors = new int[ChunkColorData.PIXELS];
    int[] heights = new int[ChunkColorData.PIXELS];

    int chunkWorldX = chunk.getPos().getMinBlockX();
    int chunkWorldZ = chunk.getPos().getMinBlockZ();
    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    BlockPos.MutableBlockPos waterProbe = new BlockPos.MutableBlockPos();
    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = chunkWorldX + localX;
      int prevHeight = 0;

      for (int localZ = 0; localZ < 16; localZ++) {
        int worldZ = chunkWorldZ + localZ;
        int idx = localX * 16 + localZ;

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
        heights[idx] = surfaceY;

        mutable.set(worldX, surfaceY, worldZ);
        BlockState state = findTopBlock(level, mutable, surfaceY);

        if (state.isAir()) {
          colors[idx] = abgrFromArgb(0xFF000000);
          prevHeight = surfaceY;
          continue;
        }

        int baseRgb = getBlockColor(blockColors, level, mutable, state);
        if (baseRgb == 0) {
          colors[idx] = abgrFromArgb(0xFF000000);
          prevHeight = surfaceY;
          continue;
        }

        MapColor.Brightness brightness;
        if (!state.getFluidState().isEmpty()) {
          brightness = computeWaterBrightness(level, waterProbe, mutable.getY(), worldX, worldZ);
        } else if (localZ == 0) {
          brightness = MapColor.Brightness.NORMAL;
        } else {
          brightness = computeElevationBrightness(surfaceY, prevHeight, worldX, worldZ);
        }

        colors[idx] = abgrFromArgb(applyBrightness(baseRgb, brightness));
        prevHeight = surfaceY;
      }
    }

    return new ChunkColorData(colors, heights, gameTick);
  }

  public static void fixBorderShading(
      ChunkColorData current, ChunkColorData northNeighbor, Level level,
      ChunkAccess currentChunk) {

    int chunkWorldX = currentChunk.getPos().getMinBlockX();
    int chunkWorldZ = currentChunk.getPos().getMinBlockZ();
    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = chunkWorldX + localX;
      int worldZ = chunkWorldZ;

      int currentHeight = current.getHeight(localX, 0);
      int northHeight = northNeighbor.getHeight(localX, 15);

      mutable.set(worldX, currentHeight, worldZ);
      BlockState state = findTopBlock(level, mutable, currentHeight);
      if (state.isAir()) continue;
      if (!state.getFluidState().isEmpty()) continue;

      int baseRgb = getBlockColor(blockColors, level, mutable, state);
      if (baseRgb == 0) continue;

      MapColor.Brightness brightness = computeElevationBrightness(
          currentHeight, northHeight, worldX, worldZ);

      current.setColor(localX, 0, abgrFromArgb(applyBrightness(baseRgb, brightness)));
    }

    current.setBordersDirty(false);
  }

  private static BlockState findTopBlock(
      Level level, BlockPos.MutableBlockPos mutable, int startY) {

    for (int y = startY; y >= level.getMinBuildHeight(); y--) {
      mutable.setY(y);
      BlockState state = level.getBlockState(mutable);

      if (state.isAir()) continue;
      if (state.getMapColor(level, mutable) == MapColor.NONE) continue;
      if (SKIP_BLOCKS.contains(state.getBlock())) continue;

      if (state.getBlock() instanceof CropBlock crop) {
        if (!crop.isMaxAge(state)) continue;
      }

      return state;
    }

    return Blocks.AIR.defaultBlockState();
  }

  /**
   * Gets the display color for a block using the renderer's {@link BlockColors}
   * registry for tint accuracy. Tinted blocks get texture × tint multiplication.
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

    // Fallback: darken tint by ~60% to approximate grayscale texture
    return 0xFF000000
        | ((tintR * 153 / 255) << 16)
        | ((tintG * 153 / 255) << 8)
        | (tintB * 153 / 255);
  }

  private static MapColor.Brightness computeElevationBrightness(
      int currentHeight, int prevHeight, int worldX, int worldZ) {

    double d3 = (double) (currentHeight - prevHeight)
        + (((worldX + worldZ) & 1) - 0.5) * 0.4;

    if (d3 > 0.6) return MapColor.Brightness.HIGH;
    if (d3 < -0.6) return MapColor.Brightness.LOW;
    return MapColor.Brightness.NORMAL;
  }

  /** Reuses the provided probe to avoid per-column allocation. */
  private static MapColor.Brightness computeWaterBrightness(
      Level level, BlockPos.MutableBlockPos probe,
      int surfaceY, int worldX, int worldZ) {

    int depth = 0;
    probe.set(worldX, surfaceY - 1, worldZ);
    while (depth < 10 && probe.getY() >= level.getMinBuildHeight()) {
      if (level.getBlockState(probe).getFluidState().isEmpty()) break;
      depth++;
      probe.setY(probe.getY() - 1);
    }

    double d2 = depth * 0.1 + ((worldX + worldZ) & 1) * 0.2;

    if (d2 < 0.5) return MapColor.Brightness.HIGH;
    if (d2 > 0.9) return MapColor.Brightness.LOW;
    return MapColor.Brightness.NORMAL;
  }

  private static int applyBrightness(int argb, MapColor.Brightness brightness) {
    int mod = brightness.modifier;
    int a = (argb >> 24) & 0xFF;
    int r = ((argb >> 16) & 0xFF) * mod / 255;
    int g = ((argb >> 8) & 0xFF) * mod / 255;
    int b = (argb & 0xFF) * mod / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  static int abgrFromArgb(int argb) {
    int a = (argb >> 24) & 0xFF;
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
  }
}
