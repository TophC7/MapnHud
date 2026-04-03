package dev.foxmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.foxmap.FoxMapMod;
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
@EventBusSubscriber(modid = FoxMapMod.MOD_ID, value = Dist.CLIENT)
public class MinimapKeybinds {

  private static final int[] ZOOM_SCALES = FoxMapConfig.ZOOM_SCALES;
  private static int runtimeZoomIndex = -1;
  private static int lastConfigZoom = -1;

  // Per-tick config cache (read by MinimapLayer on the render path)
  private static int cachedDisplaySize = 160;
  private static float cachedAspectRatio = 1.0f;
  private static float cachedOpacity = 1.0f;
  private static boolean cachedNorthLock = false;
  private static FoxMapConfig.ScreenCorner cachedPosition = FoxMapConfig.ScreenCorner.TOP_RIGHT;

  private static final KeyMapping ZOOM_KEY = new KeyMapping(
      "key." + FoxMapMod.MOD_ID + ".zoom",
      InputConstants.Type.KEYSYM,
      GLFW.GLFW_KEY_Z,
      "key.categories." + FoxMapMod.MOD_ID
  );

  public static void register(RegisterKeyMappingsEvent event) {
    event.register(ZOOM_KEY);
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    // Sync zoom from config when it changes
    int configZoom = FoxMapConfig.MAP_ZOOM.get();
    if (configZoom != lastConfigZoom) {
      lastConfigZoom = configZoom;
      runtimeZoomIndex = indexForScale(configZoom);
    }

    // Cache all config values as primitives for the render path
    cachedDisplaySize = FoxMapConfig.MAP_SIZE.get();
    cachedAspectRatio = FoxMapConfig.MAP_SHAPE.get().floatValue();
    cachedOpacity = FoxMapConfig.MAP_OPACITY.get().floatValue();
    cachedNorthLock = FoxMapConfig.MAP_NORTH_LOCK.get();
    cachedPosition = FoxMapConfig.MAP_POSITION.get();

    while (ZOOM_KEY.consumeClick()) {
      runtimeZoomIndex = (runtimeZoomIndex + 1) % ZOOM_SCALES.length;
    }
  }

  public static int getDisplaySize() { return cachedDisplaySize; }
  public static float getAspectRatio() { return cachedAspectRatio; }
  public static float getOpacity() { return cachedOpacity; }
  public static boolean isNorthLocked() { return cachedNorthLock; }
  public static FoxMapConfig.ScreenCorner getPosition() { return cachedPosition; }

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
