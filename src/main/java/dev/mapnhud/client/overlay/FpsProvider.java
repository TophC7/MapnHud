package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;

/**
 * Shows the current FPS.
 */
public class FpsProvider implements InfoProvider {

  @Override public String id() { return "fps"; }
  @Override public String displayName() { return "FPS"; }

  @Override
  public String getText(Minecraft mc) {
    return mc.getFps() + " FPS";
  }
}
