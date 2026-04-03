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
 * Keybindings for the minimap.
 *
 * <p>Z cycles through display size presets, scaling the minimap UI larger
 * each press and wrapping back to the smallest.
 */
@EventBusSubscriber(modid = FoxMapMod.MOD_ID, value = Dist.CLIENT)
public class MinimapKeybinds {

  /** Display size presets in GUI-scaled pixels. */
  static final int[] ZOOM_LEVELS = {100, 130, 160, 200, 250};
  private static int currentZoomIndex = 2; // start at 160 (testing size)

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
    while (ZOOM_KEY.consumeClick()) {
      currentZoomIndex = (currentZoomIndex + 1) % ZOOM_LEVELS.length;
    }
  }

  public static int getDisplaySize() {
    return ZOOM_LEVELS[currentZoomIndex];
  }
}
