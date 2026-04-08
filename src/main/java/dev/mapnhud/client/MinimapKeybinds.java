package dev.mapnhud.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mapnhud.MapnHudMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Zoom keybind for the minimap. The Z key writes the next zoom level directly
 * to {@link MapnHudConfig#MAP_ZOOM}, and render code reads it back through
 * {@link MinimapConfigCache#getZoomScale()}. Config screen and keybind share a
 * single source of truth, so live edits in the screen propagate without any
 * extra wiring.
 */
public final class MinimapKeybinds {

  private MinimapKeybinds() {}

  private static final int[] ZOOM_SCALES = MapnHudConfig.ZOOM_SCALES;

  private static final KeyMapping ZOOM_KEY = new KeyMapping(
      "key." + MapnHudMod.MOD_ID + ".zoom",
      InputConstants.Type.KEYSYM,
      GLFW.GLFW_KEY_Z,
      "key.categories." + MapnHudMod.MOD_ID
  );

  public static void register(RegisterKeyMappingsEvent event) {
    event.register(ZOOM_KEY);
  }

  /** Cycles MAP_ZOOM on each Z press. Called by the tick handler before the cache refresh. */
  static void tick() {
    while (ZOOM_KEY.consumeClick()) {
      int current = SafeConfig.getInt(MapnHudConfig.MAP_ZOOM, ZOOM_SCALES[0]);
      int idx = indexForScale(current);
      MapnHudConfig.MAP_ZOOM.set(ZOOM_SCALES[(idx + 1) % ZOOM_SCALES.length]);
    }
  }

  private static int indexForScale(int scale) {
    for (int i = 0; i < ZOOM_SCALES.length; i++) {
      if (ZOOM_SCALES[i] == scale) return i;
    }
    return 0;
  }
}
