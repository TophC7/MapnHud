package dev.mapnhud.client;

import dev.mapnhud.MapnHudMod;
import dev.mapnhud.client.overlay.InfoOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Per-tick config cache and client tick coordinator for the minimap.
 *
 * <p>All config values are read once per tick and cached as primitives,
 * so the render path never traverses the config tree. Also dispatches
 * per-tick updates to {@link CaveModeTracker}, {@link MinimapKeybinds},
 * and {@link InfoOverlayRenderer}.
 */
@EventBusSubscriber(modid = MapnHudMod.MOD_ID, value = Dist.CLIENT)
public final class MinimapConfigCache {

  private static boolean configValidated = false;

  // Per-tick config cache (read by MinimapLayer on the render path)
  private static int cachedDisplaySize = 160;
  private static float cachedAspectRatio = 1.0f;
  private static float cachedOpacity = 1.0f;
  private static boolean cachedNorthLock = false;
  private static MapnHudConfig.ScreenCorner cachedPosition = MapnHudConfig.ScreenCorner.TOP_RIGHT;
  private static RenderConfig cachedRenderConfig = RenderConfig.DEFAULT;

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    // Validate config on first tick (config isn't loaded at mod construction time)
    if (!configValidated && MapnHudConfig.SPEC.isLoaded()) {
      configValidated = true;
      SafeConfig.validateOrReset(MapnHudMod.MOD_ID, MapnHudConfig.SPEC,
          MapnHudConfig.MAP_ZOOM, MapnHudConfig.RENDER_AO_ENABLED,
          MapnHudConfig.OVERLAY_MASTER_TOGGLE, MapnHudConfig.OVERLAY_ORDER,
          MapnHudConfig.BLOCK_TOOLTIP_POSITION);
    }

    // Cache all config values as primitives for the render path
    cachedDisplaySize = SafeConfig.getInt(MapnHudConfig.MAP_SIZE, 160);
    cachedAspectRatio = SafeConfig.getFloat(MapnHudConfig.MAP_SHAPE, 1.0f);
    cachedOpacity = SafeConfig.getFloat(MapnHudConfig.MAP_OPACITY, 1.0f);
    cachedNorthLock = SafeConfig.getBool(MapnHudConfig.MAP_NORTH_LOCK, false);
    cachedPosition = SafeConfig.getEnum(MapnHudConfig.MAP_POSITION, MapnHudConfig.ScreenCorner.TOP_RIGHT);
    cachedRenderConfig = RenderConfig.fromConfig();

    Minecraft mc = Minecraft.getInstance();
    CaveModeTracker.tick(mc);
    MinimapKeybinds.tick();
    InfoOverlayRenderer.tick(mc);
  }

  public static int getDisplaySize() { return cachedDisplaySize; }
  public static float getAspectRatio() { return cachedAspectRatio; }
  public static float getOpacity() { return cachedOpacity; }
  public static boolean isNorthLocked() { return cachedNorthLock; }
  public static MapnHudConfig.ScreenCorner getPosition() { return cachedPosition; }
  public static RenderConfig getRenderConfig() { return cachedRenderConfig; }
}
