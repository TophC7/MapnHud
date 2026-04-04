package dev.mapnhud.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mapnhud.MapnHudMod;
import dev.mapnhud.client.overlay.InfoOverlayRenderer;
import net.minecraft.client.Minecraft;
import xyz.kwahson.core.config.SafeConfig;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * Keybindings and per-tick config cache for the minimap.
 *
 * <p>All config values are read once per tick and cached as primitives,
 * so the render path never traverses the config tree.
 */
@EventBusSubscriber(modid = MapnHudMod.MOD_ID, value = Dist.CLIENT)
public class MinimapKeybinds {

  private static final int[] ZOOM_SCALES = MapnHudConfig.ZOOM_SCALES;
  private static int runtimeZoomIndex = -1;
  private static int lastConfigZoom = -1;
  private static boolean configValidated = false;

  // Per-tick config cache (read by MinimapLayer on the render path)
  private static int cachedDisplaySize = 160;
  private static float cachedAspectRatio = 1.0f;
  private static float cachedOpacity = 1.0f;
  private static boolean cachedNorthLock = false;
  private static MapnHudConfig.ScreenCorner cachedPosition = MapnHudConfig.ScreenCorner.TOP_RIGHT;

  private static final KeyMapping ZOOM_KEY = new KeyMapping(
      "key." + MapnHudMod.MOD_ID + ".zoom",
      InputConstants.Type.KEYSYM,
      GLFW.GLFW_KEY_Z,
      "key.categories." + MapnHudMod.MOD_ID
  );

  public static void register(RegisterKeyMappingsEvent event) {
    event.register(ZOOM_KEY);
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    // Validate config on first tick (config isn't loaded at mod construction time)
    if (!configValidated && MapnHudConfig.SPEC.isLoaded()) {
      configValidated = true;
      SafeConfig.validateOrReset(MapnHudMod.MOD_ID, MapnHudConfig.SPEC,
          MapnHudConfig.MAP_ZOOM, MapnHudConfig.OVERLAY_MASTER_TOGGLE,
          MapnHudConfig.OVERLAY_ORDER, MapnHudConfig.BLOCK_TOOLTIP_POSITION);
    }

    // Sync zoom from config when it changes
    int configZoom = SafeConfig.getInt(MapnHudConfig.MAP_ZOOM, 1);
    if (configZoom != lastConfigZoom) {
      lastConfigZoom = configZoom;
      runtimeZoomIndex = indexForScale(configZoom);
    }

    // Cache all config values as primitives for the render path
    cachedDisplaySize = SafeConfig.getInt(MapnHudConfig.MAP_SIZE, 160);
    cachedAspectRatio = SafeConfig.getFloat(MapnHudConfig.MAP_SHAPE, 1.0f);
    cachedOpacity = SafeConfig.getFloat(MapnHudConfig.MAP_OPACITY, 1.0f);
    cachedNorthLock = SafeConfig.getBool(MapnHudConfig.MAP_NORTH_LOCK, false);
    cachedPosition = SafeConfig.getEnum(MapnHudConfig.MAP_POSITION, MapnHudConfig.ScreenCorner.TOP_RIGHT);

    // Tick the info overlay (reads config, builds text lines)
    InfoOverlayRenderer.tick(Minecraft.getInstance());

    while (ZOOM_KEY.consumeClick()) {
      runtimeZoomIndex = (runtimeZoomIndex + 1) % ZOOM_SCALES.length;
    }
  }

  public static int getDisplaySize() { return cachedDisplaySize; }
  public static float getAspectRatio() { return cachedAspectRatio; }
  public static float getOpacity() { return cachedOpacity; }
  public static boolean isNorthLocked() { return cachedNorthLock; }
  public static MapnHudConfig.ScreenCorner getPosition() { return cachedPosition; }

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
