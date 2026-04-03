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
 * <p>Z cycles through zoom levels that control how many blocks are visible.
 * Higher scale means more terrain coverage per pixel (zoomed out).
 */
@EventBusSubscriber(modid = FoxMapMod.MOD_ID, value = Dist.CLIENT)
public class MinimapKeybinds {

  /** Scale factors: 1 = 128 blocks visible, 2 = 256 blocks, 3 = 384 blocks. */
  static final int[] ZOOM_SCALES = {1, 2, 3};
  private static int currentZoomIndex = 0;

  /** Fixed display size in GUI-scaled pixels. */
  private static final int DISPLAY_SIZE = 160;

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
      currentZoomIndex = (currentZoomIndex + 1) % ZOOM_SCALES.length;
    }
  }

  public static int getDisplaySize() {
    return DISPLAY_SIZE;
  }

  /** Blocks per pixel. At scale 1 each pixel is one block, at scale 2 each pixel covers 2 blocks. */
  public static int getScale() {
    return ZOOM_SCALES[currentZoomIndex];
  }
}
