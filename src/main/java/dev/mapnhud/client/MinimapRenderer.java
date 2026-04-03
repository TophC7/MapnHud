package dev.mapnhud.client;

import com.mojang.blaze3d.platform.NativeImage;
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

  private int lastScale = -1;
  private int currentSize = -1;

  /**
   * Update the minimap texture for the current frame.
   * Re-assembles when the player moves to a new block, cache data changes,
   * the zoom scale changes, or the map size changes.
   *
   * @param mapSize  texture resolution in pixels (matches config frame size)
   */
  public ResourceLocation update(
      double playerX, double playerZ, ChunkColorCache cache,
      boolean cacheUpdated, int scale, int mapSize) {

    if (texture == null || mapSize != currentSize) {
      recreateTexture(mapSize);
    }

    int blockX = (int) Math.floor(playerX);
    int blockZ = (int) Math.floor(playerZ);

    boolean posChanged = blockX != lastBlockX || blockZ != lastBlockZ;
    boolean scaleChanged = scale != lastScale;
    if (posChanged || cacheUpdated || scaleChanged || needsRefresh) {
      assembler.assemble(image, cache, blockX, blockZ, scale, mapSize);
      lastBlockX = blockX;
      lastBlockZ = blockZ;
      lastScale = scale;
      needsRefresh = false;
      texture.upload();
    }

    return textureId;
  }

  private void recreateTexture(int size) {
    close();
    currentSize = size;
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
