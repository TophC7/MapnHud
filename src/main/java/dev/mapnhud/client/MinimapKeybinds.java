package dev.mapnhud.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mapnhud.MapnHudMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Keybind registration and zoom state for the minimap.
 *
 * <p>The zoom key cycles through preset scale levels at runtime.
 * Config-driven zoom syncs on each tick via {@link #tick()}, called
 * by {@link MinimapConfigCache}.
 */
public class MinimapKeybinds {

  private static final int[] ZOOM_SCALES = MapnHudConfig.ZOOM_SCALES;
  private static int runtimeZoomIndex = -1;
  private static int lastConfigZoom = -1;

  private static final KeyMapping ZOOM_KEY = new KeyMapping(
      "key." + MapnHudMod.MOD_ID + ".zoom",
      InputConstants.Type.KEYSYM,
      GLFW.GLFW_KEY_Z,
      "key.categories." + MapnHudMod.MOD_ID
  );

  public static void register(RegisterKeyMappingsEvent event) {
    event.register(ZOOM_KEY);
  }

  /** Syncs zoom from config and handles key presses. Called once per tick. */
  static void tick() {
    int configZoom = SafeConfig.getInt(MapnHudConfig.MAP_ZOOM, 1);
    if (configZoom != lastConfigZoom) {
      lastConfigZoom = configZoom;
      runtimeZoomIndex = indexForScale(configZoom);
    }

    while (ZOOM_KEY.consumeClick()) {
      runtimeZoomIndex = (runtimeZoomIndex + 1) % ZOOM_SCALES.length;
    }
  }

  public static int getScale() {
    if (runtimeZoomIndex < 0) return 1;
    return ZOOM_SCALES[runtimeZoomIndex];
  }

  private static int indexForScale(int scale) {
    for (int i = 0; i < ZOOM_SCALES.length; i++) {
      if (ZOOM_SCALES[i] == scale) return i;
    }
    return 0;
  }
}
