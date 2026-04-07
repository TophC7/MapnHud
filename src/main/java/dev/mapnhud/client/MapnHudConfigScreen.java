package dev.mapnhud.client;

import dev.mapnhud.client.overlay.InfoOverlayScreen;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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
        .tab("Rendering", MapnHudConfigScreen::buildRenderingTab)
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
        ShapePreset.closest(SafeConfig.getDouble(MapnHudConfig.MAP_SHAPE, 1.0)),
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
      Minecraft mc = Minecraft.getInstance();
      mc.setScreen(InfoOverlayScreen.create(mc.screen));
    }));
  }

  private static void buildRenderingTab(ConfigTab tab) {
    // Widgets that only apply in heightfield mode
    var hfWidgets = new ArrayList<AbstractWidget>();

    ShadingMode current = SafeConfig.getEnum(
        MapnHudConfig.RENDER_SHADING_MODE, ShadingMode.CLASSIC);

    // -- Mode selector --

    tab.left(tab.cycle("Shading", current, ShadingMode::label,
        List.of(ShadingMode.values()),
        (btn, val) -> {
          MapnHudConfig.RENDER_SHADING_MODE.set(val);
          boolean hf = val == ShadingMode.HEIGHTFIELD;
          for (var w : hfWidgets) w.active = hf;
        }));
    tab.nextRow();

    // -- Shared: terrain + water (both modes) --

    tab.spacer(6);
    tab.sections("Terrain", "Water");

    tab.left(tab.doubleSlider("Height Scale", "", 0.0, 0.030,
        MapnHudConfig.RENDER_HEIGHT_FACTOR));
    tab.right(tab.doubleSlider("Water Base Alpha", "", 0.10, 0.90,
        MapnHudConfig.RENDER_WATER_ALPHA_BASE));
    tab.nextRow();

    tab.left(tab.doubleSlider("Height Min", "", 0.50, 1.0,
        MapnHudConfig.RENDER_HEIGHT_MIN));
    tab.right(tab.doubleSlider("Water Per Depth", "", 0.0, 0.15,
        MapnHudConfig.RENDER_WATER_ALPHA_DEPTH));
    tab.nextRow();

    tab.left(tab.doubleSlider("Height Max", "", 1.0, 1.50,
        MapnHudConfig.RENDER_HEIGHT_MAX));
    tab.right(tab.doubleSlider("Water Max Alpha", "", 0.30, 1.0,
        MapnHudConfig.RENDER_WATER_ALPHA_MAX));
    tab.nextRow();

    tab.left(tab.doubleSlider("Leaf Shade", "", 0.40, 1.0,
        MapnHudConfig.RENDER_LEAF_SHADE));
    tab.nextRow();

    // -- Heightfield only: lighting + occlusion --

    tab.spacer(6);
    tab.sections("Heightfield Lighting", "Heightfield Occlusion");

    var lightAngle = tab.intSlider("Light Angle", "\u00B0", 0, 345, 15,
        MapnHudConfig.RENDER_LIGHT_ANGLE);
    var aoToggle = tab.toggle("Ambient Occlusion", MapnHudConfig.RENDER_AO_ENABLED);
    tab.left(lightAngle);
    tab.right(aoToggle);
    hfWidgets.add(lightAngle);
    hfWidgets.add(aoToggle);
    tab.nextRow();

    var sunHeight = tab.doubleSlider("Sun Height", "", 0.5, 4.0,
        MapnHudConfig.RENDER_LIGHT_ELEVATION);
    var aoStrength = tab.doubleSlider("AO Strength", "", 0.01, 0.20,
        MapnHudConfig.RENDER_AO_STRENGTH);
    tab.left(sunHeight);
    tab.right(aoStrength);
    hfWidgets.add(sunHeight);
    hfWidgets.add(aoStrength);
    tab.nextRow();

    var ambient = tab.doubleSlider("Ambient", "", 0.2, 0.9,
        MapnHudConfig.RENDER_AMBIENT);
    var aoMax = tab.doubleSlider("AO Max Darken", "", 0.05, 0.50,
        MapnHudConfig.RENDER_AO_MAX);
    tab.left(ambient);
    tab.right(aoMax);
    hfWidgets.add(ambient);
    hfWidgets.add(aoMax);
    tab.nextRow();

    var smoothness = tab.doubleSlider("Terrain Smoothness", "", 0.5, 8.0,
        MapnHudConfig.RENDER_TERRAIN_SMOOTHNESS);
    tab.left(smoothness);
    hfWidgets.add(smoothness);
    tab.nextRow();

    // Grey out heightfield widgets when classic mode is active
    boolean hf = current == ShadingMode.HEIGHTFIELD;
    for (var w : hfWidgets) w.active = hf;

    // -- Preview --

    tab.spacer(6);
    tab.center(new MinimapPreviewWidget(412));
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

    /** Nearest preset by Manhattan distance in RGB space. */
    static TextColorPreset closest(int val) {
      int vR = (val >> 16) & 0xFF;
      int vG = (val >> 8) & 0xFF;
      int vB = val & 0xFF;
      TextColorPreset best = ALL[0];
      int bestDist = Integer.MAX_VALUE;
      for (TextColorPreset p : ALL) {
        int pR = (p.rgb >> 16) & 0xFF;
        int pG = (p.rgb >> 8) & 0xFF;
        int pB = p.rgb & 0xFF;
        int dist = Math.abs(pR - vR) + Math.abs(pG - vG) + Math.abs(pB - vB);
        if (dist < bestDist) {
          bestDist = dist;
          best = p;
        }
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
