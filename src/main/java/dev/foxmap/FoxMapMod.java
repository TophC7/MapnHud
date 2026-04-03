package dev.foxmap;

import dev.foxmap.client.MinimapKeybinds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FoxMapMod.MOD_ID)
public class FoxMapMod {
  public static final String MOD_ID = "fox_map";
  public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  public FoxMapMod(IEventBus modBus) {
    LOG.info("Fox Map loading");

    if (FMLEnvironment.dist.isClient()) {
      modBus.addListener(FoxMapMod::onRegisterKeys);
    }
  }

  private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
    MinimapKeybinds.register(event);
  }
}
