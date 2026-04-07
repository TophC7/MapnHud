package dev.mapnhud.client.map;

import dev.mapnhud.client.map.cave.CaveFieldState;
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
 * Stateless scanner that reads a chunk's terrain and produces raw column data.
 *
 * <p>Extracts base colors, heights, water metadata, and leaf flags without
 * applying any shading. Shading is deferred to the viewport assembler where
 * the full visible heightfield is available, eliminating chunk boundary seams.
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
   * Scans a chunk from the surface downward. Produces raw column data: base
   * colors, heights, water metadata, and leaf flags. No shading is applied.
   */
  public static ChunkColorData scan(ChunkAccess chunk, Level level) {
    int[] baseColors = new int[ChunkColorData.PIXELS];
    int[] heights = new int[ChunkColorData.PIXELS];
    int[] waterDepths = new int[ChunkColorData.PIXELS];
    int[] waterTints = new int[ChunkColorData.PIXELS];
    boolean[] isLeaf = new boolean[ChunkColorData.PIXELS];

    int chunkWorldX = chunk.getPos().getMinBlockX();
    int chunkWorldZ = chunk.getPos().getMinBlockZ();

    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = chunkWorldX + localX;

      for (int localZ = 0; localZ < 16; localZ++) {
        int worldZ = chunkWorldZ + localZ;
        int idx = localX * 16 + localZ;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);

        mutable.set(worldX, surfaceY, worldZ);
        ColumnResult col = scanColumn(level, mutable, surfaceY);
        processColumn(blockColors, level, mutable, worldX, worldZ, col, idx,
            baseColors, heights, waterDepths, waterTints, isLeaf);
      }
    }

    return new ChunkColorData(baseColors, heights, waterDepths, waterTints, isLeaf);
  }

  /**
   * Scans a chunk for cave mode using a pre-computed flood fill result.
   *
   * <p>The flood fill determines which (x, z) columns are reachable from the
   * player and at what walking Y. This method uses that as a "cave heightmap":
   * reachable columns get scanned downward from their walking Y (same pipeline
   * as surface mode). Non-reachable columns are tagged as boundary or unknown.
   *
   * <p>This produces the same data format as {@link #scan}, so the assembler's
   * shading pipeline (heightfield normals, AO, water blending) works unchanged.
   *
   * @param flood the pre-computed flood fill result with reachable columns
   */
  public static ChunkColorData scanCave(
      ChunkAccess chunk, Level level, CaveFloodFill.Result flood) {

    int chunkX = chunk.getPos().x;
    int chunkZ = chunk.getPos().z;
    int chunkWorldX = chunk.getPos().getMinBlockX();
    int chunkWorldZ = chunk.getPos().getMinBlockZ();
    boolean chunkUnknown = flood.isUnknownChunk(chunkX, chunkZ);
    boolean floodComplete = flood.complete();

    // Skip fully unreachable chunks unless they are currently unknown frontier.
    boolean anyReachable = false;
    boolean anyInsideRadius = false;
    for (int lx = 0; lx < 16; lx++) {
      for (int lz = 0; lz < 16; lz++) {
        int worldX = chunkWorldX + lx;
        int worldZ = chunkWorldZ + lz;
        if (!flood.isOutsideRadius(worldX, worldZ)) {
          anyInsideRadius = true;
        }
        if (flood.isReachable(worldX, worldZ)) {
          anyReachable = true;
        }
      }
    }
    if (!anyReachable) {
      if (!anyInsideRadius && !chunkUnknown) return null;

      boolean[] known = new boolean[ChunkColorData.PIXELS];
      byte[] fieldStates = new byte[ChunkColorData.PIXELS];
      for (int lx = 0; lx < 16; lx++) {
        int worldX = chunkWorldX + lx;
        for (int lz = 0; lz < 16; lz++) {
          int worldZ = chunkWorldZ + lz;
          int idx = lx * 16 + lz;
          boolean outsideRadius = flood.isOutsideRadius(worldX, worldZ);
          fieldStates[idx] = (!floodComplete || chunkUnknown || outsideRadius)
              ? CaveFieldState.UNKNOWN
              : CaveFieldState.BOUNDARY;
        }
      }
      return new ChunkColorData(
          new int[ChunkColorData.PIXELS],
          new int[ChunkColorData.PIXELS],
          new int[ChunkColorData.PIXELS],
          new int[ChunkColorData.PIXELS],
          new boolean[ChunkColorData.PIXELS],
          known,
          fieldStates);
    }

    int[] baseColors = new int[ChunkColorData.PIXELS];
    int[] heights = new int[ChunkColorData.PIXELS];
    int[] waterDepths = new int[ChunkColorData.PIXELS];
    int[] waterTints = new int[ChunkColorData.PIXELS];
    boolean[] isLeaf = new boolean[ChunkColorData.PIXELS];
    boolean[] known = new boolean[ChunkColorData.PIXELS];
    byte[] fieldStates = new byte[ChunkColorData.PIXELS];

    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
    BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = chunkWorldX + localX;

      for (int localZ = 0; localZ < 16; localZ++) {
        int worldZ = chunkWorldZ + localZ;
        int idx = localX * 16 + localZ;

        if (!flood.isReachable(worldX, worldZ)) {
          boolean outsideRadius = flood.isOutsideRadius(worldX, worldZ);
          fieldStates[idx] = (!floodComplete || chunkUnknown || outsideRadius)
              ? CaveFieldState.UNKNOWN
              : CaveFieldState.BOUNDARY;
          continue;
        }

        int walkingY = flood.getWalkingY(worldX, worldZ);
        mutable.set(worldX, walkingY, worldZ);
        ColumnResult col = scanColumn(level, mutable, walkingY);
        processColumn(blockColors, level, mutable, worldX, worldZ, col, idx,
            baseColors, heights, waterDepths, waterTints, isLeaf);
        known[idx] = true;
        fieldStates[idx] = CaveFieldState.REACHABLE;
      }
    }

    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf, known, fieldStates);
  }

  /**
   * Extracts colors, heights, water data, and leaf flags from a scanned column
   * into the output arrays. Shared between surface and cave scan modes.
   */
  private static void processColumn(
      BlockColors blockColors, Level level, BlockPos.MutableBlockPos mutable,
      int worldX, int worldZ, ColumnResult col, int idx,
      int[] baseColors, int[] heights, int[] waterDepths, int[] waterTints, boolean[] isLeaf) {

    heights[idx] = col.blockY;

    if (col.isEmpty()) {
      baseColors[idx] = 0xFF000000;
      return;
    }

    mutable.set(worldX, col.blockY, worldZ);
    int color = getBlockColor(blockColors, level, mutable, col.block);
    if (color == 0) {
      baseColors[idx] = 0xFF000000;
      return;
    }

    baseColors[idx] = color;
    waterDepths[idx] = col.waterDepth;
    isLeaf[idx] = col.block.getBlock() instanceof LeavesBlock;

    if (col.isWater()) {
      mutable.set(worldX, col.waterSurfaceY, worldZ);
      waterTints[idx] = level.getBlockTint(mutable, BiomeColors.WATER_COLOR_RESOLVER);
    }
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

  /** Converts ARGB to ABGR (NativeImage's native format). */
  public static int abgrFromArgb(int argb) {
    int a = (argb >> 24) & 0xFF;
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
  }
}
