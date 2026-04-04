package dev.mapnhud.client;

import dev.mapnhud.client.MapnHudConfig.OverlayAlign;
import dev.mapnhud.client.MapnHudConfig.OverlayPosition;
import dev.mapnhud.client.MapnHudConfig.ScreenCorner;
import dev.mapnhud.client.MapnHudConfig.TooltipPosition;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import xyz.kwahson.core.config.ConfigTab;
import xyz.kwahson.core.config.KwahsConfigScreen;
import xyz.kwahson.core.config.SafeConfig;

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
        ShapePreset.closest((double) SafeConfig.getFloat(MapnHudConfig.MAP_SHAPE, 1.0f)),
        ShapePreset::label, List.of(ShapePreset.ALL),
        (btn, val) -> MapnHudConfig.MAP_SHAPE.set(val.ratio())));
    tab.nextRow();

    tab.left(tab.enumButton("Position", MapnHudConfig.MAP_POSITION,
        ScreenCorner::label, ScreenCorner.values()));
    tab.right(tab.percentSlider("Opacity", 0.3, 1.0, MapnHudConfig.MAP_OPACITY));
    tab.nextRow();

    tab.spacer(6);
    tab.sections("Overlay", "Overlay Style");

    tab.left(tab.toggle("Overlay Enabled", MapnHudConfig.OVERLAY_MASTER_TOGGLE));
    tab.right(tab.enumButton("Position", MapnHudConfig.OVERLAY_POSITION,
        OverlayPosition::label, OverlayPosition.values()));
    tab.nextRow();

    tab.left(tab.enumButton("Alignment", MapnHudConfig.OVERLAY_ALIGNMENT,
        OverlayAlign::label, OverlayAlign.values()));
    tab.right(tab.toggle("Background", MapnHudConfig.OVERLAY_BACKGROUND));
    tab.nextRow();

    tab.left(tab.cycle("Text Color",
        TextColorPreset.closest(SafeConfig.getInt(MapnHudConfig.OVERLAY_TEXT_COLOR, 0xFFFFFF)),
        TextColorPreset::label, List.of(TextColorPreset.ALL),
        (btn, val) -> MapnHudConfig.OVERLAY_TEXT_COLOR.set(val.rgb())));
    tab.right(tab.percentSlider("Text Scale", 0.5, 1.5, MapnHudConfig.OVERLAY_TEXT_SCALE));
    tab.nextRow();

    tab.left(tab.button("Configure Lines...", btn -> {
      var mc = net.minecraft.client.Minecraft.getInstance();
      mc.setScreen(dev.mapnhud.client.overlay.InfoOverlayScreen.create(mc.screen));
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

  private record TextColorPreset(String label, int rgb) {
    static final TextColorPreset[] ALL = {
        new TextColorPreset("White", 0xFFFFFF),
        new TextColorPreset("Light Gray", 0xBBBBBB),
        new TextColorPreset("Yellow", 0xFFFF55),
        new TextColorPreset("Aqua", 0x55FFFF),
        new TextColorPreset("Green", 0x55FF55),
        new TextColorPreset("Gold", 0xFFAA00),
    };

    static TextColorPreset closest(int val) {
      TextColorPreset best = ALL[0];
      for (TextColorPreset p : ALL) {
        if (p.rgb == val) return p;
      }
      return best;
    }
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
