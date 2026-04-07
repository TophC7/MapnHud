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
 * Single client-tick coordinator for the minimap. Validates the config on the
 * first tick after it loads, drives keybinds (which may write to config),
 * refreshes the cache, then ticks the trackers and renderers that need
 * per-tick updates.
 *
 * <p>Order matters: keybinds run BEFORE the cache refresh so config writes from
 * key presses land in the cache the same tick instead of one tick late.
 */
@EventBusSubscriber(modid = MapnHudMod.MOD_ID, value = Dist.CLIENT)
public final class MinimapClientTickHandler {

  private MinimapClientTickHandler() {}

  private static boolean configValidated = false;

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    if (!configValidated && MapnHudConfig.SPEC.isLoaded()) {
      configValidated = true;
      SafeConfig.validateOrReset(MapnHudMod.MOD_ID, MapnHudConfig.SPEC,
          MapnHudConfig.MAP_ZOOM, MapnHudConfig.RENDER_AO_ENABLED,
          MapnHudConfig.OVERLAY_MASTER_TOGGLE, MapnHudConfig.OVERLAY_ORDER,
          MapnHudConfig.BLOCK_TOOLTIP_POSITION);
    }

    MinimapKeybinds.tick();

    Minecraft mc = Minecraft.getInstance();
    MinimapConfigCache.refresh(mc.options.renderDistance().get());

    CaveModeTracker.tick(mc);
    InfoOverlayRenderer.tick(mc);
  }
}
