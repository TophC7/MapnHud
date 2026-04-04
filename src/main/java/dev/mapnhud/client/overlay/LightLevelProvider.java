package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * Shows the block light level at the player's position.
 */
public final class LightLevelProvider implements InfoProvider {

  @Override public String id() { return "light"; }
  @Override public String displayName() { return "Light Level"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null || mc.level == null) return null;
    BlockPos pos = mc.player.blockPosition();
    int block = mc.level.getBrightness(LightLayer.BLOCK, pos);
    int sky = mc.level.getBrightness(LightLayer.SKY, pos);
    return "Light: " + Math.max(block, sky);
  }
}
