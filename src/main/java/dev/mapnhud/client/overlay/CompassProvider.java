package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Shows the player's facing direction as a cardinal heading.
 */
public class CompassProvider implements InfoProvider {

  private static final String[] CARDINALS = {
      "S", "SW", "W", "NW", "N", "NE", "E", "SE"
  };

  @Override public String id() { return "compass"; }
  @Override public String displayName() { return "Compass"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null) return null;
    float yaw = Mth.wrapDegrees(mc.player.getYRot());
    // Yaw 0 = south, 90 = west, -90/270 = east, 180 = north
    int index = Math.round(yaw / 45.0f) & 7;
    return CARDINALS[index] + " (" + Math.round(yaw) + "\u00B0)";
  }
}
