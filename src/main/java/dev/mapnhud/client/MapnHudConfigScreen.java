package dev.mapnhud.client;

import dev.mapnhud.client.MapnHudConfig.ScreenCorner;
import dev.mapnhud.client.MapnHudConfig.TooltipPosition;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import xyz.kwahson.core.config.ConfigTab;
import xyz.kwahson.core.config.KwahsConfigScreen;

/**
 * Configuration screen for Map n Hud. Built on KwahsCore's
 * tabbed config screen builder.
 */
public class MapnHudConfigScreen {

  private MapnHudConfigScreen() {}

  public static Screen create(Screen parent) {
    return KwahsConfigScreen.builder("Kwah's Map n Hud", parent, MapnHudConfig.SPEC)
        .tab("Map", MapnHudConfigScreen::buildMapTab)
        .tab("HUD", MapnHudConfigScreen::buildHudTab)
        .build();
  }

  private static void buildMapTab(ConfigTab tab) {
    tab.sections("Display", "Frame");

    tab.left(tab.intCycle("Zoom", MapnHudConfig.MAP_ZOOM,
        v -> v + "x",
        Arrays.stream(MapnHudConfig.ZOOM_SCALES).boxed().toArray(Integer[]::new)));
    tab.right(tab.intSlider("Size", "px", 80, 320, 10, MapnHudConfig.MAP_SIZE));
    tab.nextRow();

    tab.left(tab.toggle("Lock North Up", MapnHudConfig.MAP_NORTH_LOCK));
    tab.right(tab.cycle("Shape",
        ShapePreset.closest(MapnHudConfig.MAP_SHAPE.get()),
        ShapePreset::label, List.of(ShapePreset.ALL),
        (btn, val) -> MapnHudConfig.MAP_SHAPE.set(val.ratio())));
    tab.nextRow();

    tab.left(tab.enumButton("Position", MapnHudConfig.MAP_POSITION,
        ScreenCorner::label, ScreenCorner.values()));
    tab.right(tab.percentSlider("Opacity", 0.3, 1.0, MapnHudConfig.MAP_OPACITY));
    tab.nextRow();

    tab.spacer(6);
    tab.section("Overlay");

    tab.left(tab.button("Info Overlay...", btn -> {
      // TODO: open info overlay sub-screen
    }));
  }

  private static void buildHudTab(ConfigTab tab) {
    tab.sections("Block Tooltip", "Tab List");

    tab.left(tab.toggle("Block Tooltip", MapnHudConfig.BLOCK_TOOLTIP_ENABLED));
    tab.right(tab.toggle("Tab Distances", MapnHudConfig.TAB_DISTANCES_ENABLED));
    tab.nextRow();

    tab.left(tab.enumButton("Tooltip Position", MapnHudConfig.BLOCK_TOOLTIP_POSITION,
        TooltipPosition::label, TooltipPosition.values()));
  }

  private record ShapePreset(String label, double ratio) {
    static final ShapePreset[] ALL = {
        new ShapePreset("1:1", 1.0),
        new ShapePreset("4:3", 4.0 / 3),
        new ShapePreset("3:2", 3.0 / 2),
        new ShapePreset("16:9", 16.0 / 9),
        new ShapePreset("2:1", 2.0),
    };

    static ShapePreset closest(double val) {
      ShapePreset best = ALL[0];
      for (ShapePreset p : ALL) {
        if (Math.abs(p.ratio - val) < Math.abs(best.ratio - val)) best = p;
      }
      return best;
    }
  }
}
