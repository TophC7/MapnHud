package dev.foxmap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Owns the {@link DynamicTexture} / {@link NativeImage} lifecycle for the minimap.
 * Delegates terrain assembly to {@link MinimapAssembler}.
 */
public class MinimapRenderer {

  private DynamicTexture texture;
  private ResourceLocation textureId;
  private NativeImage image;
  private final MinimapAssembler assembler = new MinimapAssembler();

  private int lastBlockX = Integer.MIN_VALUE;
  private int lastBlockZ = Integer.MIN_VALUE;
  private boolean needsUpload = true;

  private int lastScale = -1;

  /**
   * Update the minimap texture for the current frame.
   * Re-assembles when the player moves to a new block, cache data changes,
   * or the zoom scale changes.
   */
  public ResourceLocation update(
      double playerX, double playerZ, ChunkColorCache cache,
      boolean cacheUpdated, int scale) {

    if (texture == null) {
      init();
    }

    int blockX = (int) Math.floor(playerX);
    int blockZ = (int) Math.floor(playerZ);

    boolean posChanged = blockX != lastBlockX || blockZ != lastBlockZ;
    boolean scaleChanged = scale != lastScale;
    if (posChanged || cacheUpdated || scaleChanged || needsUpload) {
      assembler.assemble(image, cache, blockX, blockZ, scale);
      lastBlockX = blockX;
      lastBlockZ = blockZ;
      lastScale = scale;
      needsUpload = true;
    }

    if (needsUpload) {
      texture.upload();
      needsUpload = false;
    }

    return textureId;
  }

  private void init() {
    int size = MinimapAssembler.MAP_SIZE;
    image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
    texture = new DynamicTexture(image);
    textureId = Minecraft.getInstance()
        .getTextureManager()
        .register("fox_map_minimap", texture);
  }

  public void close() {
    if (texture != null) {
      texture.close();
      texture = null;
      image = null;
      textureId = null;
    }
  }
}
