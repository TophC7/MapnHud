package dev.mapnhud.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mapnhud.client.map.ChunkColorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Owns the {@link DynamicTexture} / {@link NativeImage} lifecycle for the minimap.
 * Delegates terrain assembly to {@link MinimapAssembler}.
 *
 * <p>The texture is recreated when the configured map size changes, since
 * NativeImage dimensions are fixed at allocation time.
 */
public class MinimapRenderer {

  private DynamicTexture texture;
  private ResourceLocation textureId;
  private NativeImage image;
  private final MinimapAssembler assembler = new MinimapAssembler();

  private int lastBlockX = Integer.MIN_VALUE;
  private int lastBlockZ = Integer.MIN_VALUE;
  private boolean needsRefresh = true;

  private int lastSeaLevel = Integer.MIN_VALUE;
  private RenderConfig lastRenderConfig;

  /**
   * Update the minimap texture for the current frame.
   * Re-assembles when the player moves to a new block, cache data changes,
   * rendering config changes, or the map size changes.
   *
   * @param mapSize    texture resolution in world-block pixels (1 pixel per world block).
   *                   The MinimapLayer computes this as quadSide/zoom and upscales on draw.
   * @param seaLevel   world sea level for height-relative shading
   * @param config     rendering config snapshot from this tick
   */
  public ResourceLocation update(
      double playerX, double playerZ, ChunkColorCache cache,
      boolean cacheUpdated, int mapSize,
      int seaLevel, RenderConfig config) {

    if (image == null || mapSize != image.getWidth()) {
      recreateTexture(mapSize);
    }

    int blockX = (int) Math.floor(playerX);
    int blockZ = (int) Math.floor(playerZ);

    boolean posChanged = blockX != lastBlockX || blockZ != lastBlockZ;
    boolean shadingChanged = seaLevel != lastSeaLevel || !config.equals(lastRenderConfig);
    if (posChanged || cacheUpdated || shadingChanged || needsRefresh) {
      assembler.assemble(image, cache, blockX, blockZ, mapSize, seaLevel, config);
      lastBlockX = blockX;
      lastBlockZ = blockZ;
      lastSeaLevel = seaLevel;
      lastRenderConfig = config;
      needsRefresh = false;
      texture.upload();
    }

    return textureId;
  }

  private void recreateTexture(int size) {
    close();
    image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
    texture = new DynamicTexture(image);
    textureId = Minecraft.getInstance()
        .getTextureManager()
        .register("mapnhud_minimap", texture);
    needsRefresh = true;
  }

  public void close() {
    if (texture != null) {
      // Release from TextureManager to avoid leaked ResourceLocation entries
      Minecraft.getInstance().getTextureManager().release(textureId);
      texture = null;
      image = null;
      textureId = null;
    }
  }
}
