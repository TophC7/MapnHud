package dev.mapnhud;

import dev.mapnhud.client.MapnHudConfig;
import dev.mapnhud.client.MapnHudConfigScreen;
import dev.mapnhud.client.MinimapKeybinds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapnHudMod.MOD_ID)
public class MapnHudMod {
  public static final String MOD_ID = "mapnhud";
  public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  public MapnHudMod(IEventBus modBus, ModContainer container) {
    LOG.info("Fox Map loading");

    if (FMLEnvironment.dist.isClient()) {
      container.registerConfig(ModConfig.Type.CLIENT, MapnHudConfig.SPEC);
      container.registerExtensionPoint(
          IConfigScreenFactory.class,
          (mc, parent) -> new MapnHudConfigScreen(parent));
      modBus.addListener(MapnHudMod::onRegisterKeys);
    }
  }

  private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
    MinimapKeybinds.register(event);
  }
}
