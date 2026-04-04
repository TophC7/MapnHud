package dev.mapnhud.client.overlay;

import dev.mapnhud.client.MapnHudConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import xyz.kwahson.core.config.KwahsConfigScreen;
import xyz.kwahson.core.config.ReorderableListScreen;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Opens the info overlay configuration screen using KwahsCore's
 * reorderable list widget. Each overlay line can be toggled, reordered,
 * and some have per-provider settings.
 */
public final class InfoOverlayScreen {

  private InfoOverlayScreen() {}

  public static Screen create(Screen parent) {
    var builder = ReorderableListScreen.builder("Info Overlay", parent);

    for (InfoProvider provider : InfoProviders.all()) {
      if (provider.hasSettings()) {
        builder.entry(provider.id(), provider.displayName(),
            screen -> openProviderSettings(provider, screen));
      } else {
        builder.entry(provider.id(), provider.displayName());
      }
    }

    List<? extends String> savedOrder = SafeConfig.get(MapnHudConfig.OVERLAY_ORDER, List.of());
    List<? extends String> savedEnabled = SafeConfig.get(MapnHudConfig.OVERLAY_ENABLED, List.of());

    builder.savedOrder(new ArrayList<>(savedOrder));
    builder.savedEnabled(new ArrayList<>(savedEnabled));
    builder.onSave((order, enabled) -> {
      MapnHudConfig.OVERLAY_ORDER.set(new ArrayList<>(order));
      MapnHudConfig.OVERLAY_ENABLED.set(new ArrayList<>(enabled));
      MapnHudConfig.SPEC.save();
    });

    return builder.build();
  }

  private static void openProviderSettings(InfoProvider provider, Screen parent) {
    Screen settingsScreen = switch (provider.id()) {
      case "coordinates" -> createCoordsSettings(parent);
      case "time" -> createTimeSettings(parent);
      default -> null;
    };
    if (settingsScreen != null) {
      net.minecraft.client.Minecraft.getInstance().setScreen(settingsScreen);
    }
  }

  private static Screen createCoordsSettings(Screen parent) {
    return KwahsConfigScreen.builder("Coordinates", parent, MapnHudConfig.SPEC)
        .tab("Settings", tab -> {
          tab.section("Coordinates");
          tab.left(tab.toggle("Show Y Coordinate", MapnHudConfig.OVERLAY_COORDS_SHOW_Y));
        })
        .build();
  }

  private static Screen createTimeSettings(Screen parent) {
    return KwahsConfigScreen.builder("Time", parent, MapnHudConfig.SPEC)
        .tab("Settings", tab -> {
          tab.section("Time Display");
          tab.left(tab.toggle("Real Time", MapnHudConfig.OVERLAY_TIME_REAL));
          tab.nextRow();
          tab.left(tab.toggle("24-Hour Format", MapnHudConfig.OVERLAY_TIME_24H));
        })
        .build();
  }
}
