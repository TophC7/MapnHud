package dev.mapnhud.client.overlay;

import dev.mapnhud.client.MapnHudConfig;
import net.minecraft.client.Minecraft;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Shows the player's block coordinates. Optionally includes Y.
 */
public final class CoordinatesProvider implements InfoProvider {

  @Override public String id() { return "coordinates"; }
  @Override public String displayName() { return "Coordinates"; }
  @Override public boolean hasSettings() { return true; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null) return null;
    int x = mc.player.getBlockX();
    int y = mc.player.getBlockY();
    int z = mc.player.getBlockZ();

    if (SafeConfig.getBool(MapnHudConfig.OVERLAY_COORDS_SHOW_Y, true)) {
      return x + " / " + y + " / " + z;
    }
    return x + " / " + z;
  }
}
