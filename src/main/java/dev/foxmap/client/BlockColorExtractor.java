package dev.foxmap.client;

import dev.foxmap.FoxMapMod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extracts the average top-face texture color for every registered block.
 *
 * <p>Built once when block models are available (after resource loading), and
 * rebuilt if resource packs change. The result is a {@code Map<Block, Integer>}
 * where each value is an ARGB color representing what the block actually looks
 * like from above, which is much more accurate than the ~64-color MapColor palette.
 *
 * <p>Transparent pixels (alpha < 128) are excluded from the average so that
 * cross-model blocks (flowers, crops, etc.) produce the color of the visible
 * part, not a washed-out blend with the background.
 */
public final class BlockColorExtractor {

  private static final Map<Block, Integer> BLOCK_COLORS = new HashMap<>();
  private static boolean initialized = false;

  private BlockColorExtractor() {}

  /**
   * Build or rebuild the color map from the current block models.
   * Should be called after models are baked and the texture atlas is ready.
   */
  public static void rebuild() {
    BLOCK_COLORS.clear();

    BlockModelShaper shaper = Minecraft.getInstance()
        .getBlockRenderer()
        .getBlockModelShaper();
    RandomSource random = RandomSource.create(42);

    int count = 0;
    for (Block block : BuiltInRegistries.BLOCK) {
      BlockState state = block.defaultBlockState();
      BakedModel model = shaper.getBlockModel(state);

      int color = extractTopFaceColor(model, state, random);
      if (color != 0) {
        BLOCK_COLORS.put(block, color);
        count++;
      }
    }

    initialized = true;
    FoxMapMod.LOG.info("Extracted texture colors for {} blocks", count);
  }

  /**
   * Get the cached average color for a block, or -1 if not available.
   * Returns ARGB format (0xAARRGGBB).
   */
  public static int getColor(Block block) {
    Integer color = BLOCK_COLORS.get(block);
    return color != null ? color : -1;
  }

  public static boolean isInitialized() {
    return initialized;
  }

  /** Mark for re-extraction (e.g., on resource pack change). */
  public static void reset() {
    initialized = false;
    BLOCK_COLORS.clear();
  }

  /**
   * Extract the average color from a block model's top (UP) face.
   * Falls back to particle sprite if no UP quads exist.
   */
  private static int extractTopFaceColor(
      BakedModel model, BlockState state, RandomSource random) {

    // Try UP face quads first (what you see from above)
    List<BakedQuad> quads = model.getQuads(state, Direction.UP, random);
    if (!quads.isEmpty()) {
      TextureAtlasSprite sprite = quads.get(0).getSprite();
      return averageSpriteColor(sprite);
    }

    // For cross-model blocks (flowers, etc.) quads are in the null-direction list
    quads = model.getQuads(state, null, random);
    if (!quads.isEmpty()) {
      TextureAtlasSprite sprite = quads.get(0).getSprite();
      return averageSpriteColor(sprite);
    }

    // Last resort: particle sprite (used for breaking particles, usually representative)
    TextureAtlasSprite particle = model.getParticleIcon();
    if (particle != null) {
      return averageSpriteColor(particle);
    }

    return 0;
  }

  /**
   * Compute the average color of a sprite, excluding transparent pixels.
   *
   * <p>{@code getPixelRGBA} returns ABGR format despite the method name.
   * We convert to ARGB for consistency with the rest of our pipeline.
   */
  private static int averageSpriteColor(TextureAtlasSprite sprite) {
    int width = sprite.contents().width();
    int height = sprite.contents().height();

    long totalR = 0, totalG = 0, totalB = 0;
    int opaqueCount = 0;

    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        int pixel = sprite.getPixelRGBA(0, x, y); // ABGR format

        int alpha = (pixel >> 24) & 0xFF;
        if (alpha < 128) continue; // skip transparent pixels

        // ABGR → extract channels
        int r = pixel & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = (pixel >> 16) & 0xFF;

        totalR += r;
        totalG += g;
        totalB += b;
        opaqueCount++;
      }
    }

    if (opaqueCount == 0) return 0;

    int avgR = (int) (totalR / opaqueCount);
    int avgG = (int) (totalG / opaqueCount);
    int avgB = (int) (totalB / opaqueCount);

    return 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB; // ARGB
  }
}
