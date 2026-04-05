package dev.mapnhud.client;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration for Fox Map.
 *
 * <p>All values are CLIENT type, stored in {@code mapnhud-client.toml}.
 * Changes take effect immediately without restart via {@code .get()}.
 */
public final class MapnHudConfig {

  public static final ModConfigSpec SPEC;

  // -- Map --
  public static final ModConfigSpec.IntValue MAP_ZOOM;
  public static final ModConfigSpec.BooleanValue MAP_NORTH_LOCK;
  public static final ModConfigSpec.EnumValue<ScreenCorner> MAP_POSITION;
  public static final ModConfigSpec.DoubleValue MAP_OPACITY;
  public static final ModConfigSpec.IntValue MAP_SIZE;
  public static final ModConfigSpec.DoubleValue MAP_SHAPE;

  // -- Cave --
  public static final ModConfigSpec.BooleanValue CAVE_MODE_ENABLED;

  // -- Scan --
  public static final ModConfigSpec.DoubleValue SCAN_RADIUS_MULTIPLIER;
  public static final ModConfigSpec.IntValue CAVE_SCAN_RADIUS;
  public static final ModConfigSpec.IntValue CAVE_FLOOD_RADIUS;

  // -- Rendering --
  public static final ModConfigSpec.EnumValue<ShadingMode> RENDER_SHADING_MODE;
  public static final ModConfigSpec.IntValue RENDER_LIGHT_ANGLE;
  public static final ModConfigSpec.DoubleValue RENDER_LIGHT_ELEVATION;
  public static final ModConfigSpec.DoubleValue RENDER_AMBIENT;
  public static final ModConfigSpec.DoubleValue RENDER_TERRAIN_SMOOTHNESS;
  public static final ModConfigSpec.BooleanValue RENDER_AO_ENABLED;
  public static final ModConfigSpec.DoubleValue RENDER_AO_STRENGTH;
  public static final ModConfigSpec.DoubleValue RENDER_AO_MAX;
  public static final ModConfigSpec.DoubleValue RENDER_HEIGHT_FACTOR;
  public static final ModConfigSpec.DoubleValue RENDER_HEIGHT_MIN;
  public static final ModConfigSpec.DoubleValue RENDER_HEIGHT_MAX;
  public static final ModConfigSpec.DoubleValue RENDER_LEAF_SHADE;
  public static final ModConfigSpec.DoubleValue RENDER_WATER_ALPHA_BASE;
  public static final ModConfigSpec.DoubleValue RENDER_WATER_ALPHA_DEPTH;
  public static final ModConfigSpec.DoubleValue RENDER_WATER_ALPHA_MAX;

  // -- Overlay --
  public static final ModConfigSpec.BooleanValue OVERLAY_MASTER_TOGGLE;
  public static final ModConfigSpec.EnumValue<OverlayPosition> OVERLAY_POSITION;
  public static final ModConfigSpec.EnumValue<OverlayAlign> OVERLAY_ALIGNMENT;
  public static final ModConfigSpec.DoubleValue OVERLAY_TEXT_SCALE;
  public static final ModConfigSpec.IntValue OVERLAY_TEXT_COLOR;
  public static final ModConfigSpec.BooleanValue OVERLAY_BACKGROUND;
  public static final ModConfigSpec.ConfigValue<List<? extends String>> OVERLAY_ORDER;
  public static final ModConfigSpec.ConfigValue<List<? extends String>> OVERLAY_ENABLED;
  public static final ModConfigSpec.BooleanValue OVERLAY_COORDS_SHOW_Y;
  public static final ModConfigSpec.BooleanValue OVERLAY_TIME_REAL;
  public static final ModConfigSpec.BooleanValue OVERLAY_TIME_24H;

  // -- HUD --
  public static final ModConfigSpec.BooleanValue BLOCK_TOOLTIP_ENABLED;
  public static final ModConfigSpec.EnumValue<TooltipPosition> BLOCK_TOOLTIP_POSITION;
  public static final ModConfigSpec.BooleanValue TAB_DISTANCES_ENABLED;

  /** Valid zoom scales (blocks per pixel). Used by keybinds and the config screen. */
  public static final int[] ZOOM_SCALES = {1, 2, 3};

  static {
    var builder = new ModConfigSpec.Builder();

    builder.push("map");

    MAP_ZOOM = builder
        .comment("Default zoom level (blocks per pixel). 1 = closest, 3 = farthest.")
        .defineInRange("zoom", ZOOM_SCALES[0], ZOOM_SCALES[0], ZOOM_SCALES[ZOOM_SCALES.length - 1]);

    MAP_NORTH_LOCK = builder
        .comment("Lock the map so north is always up. When false, the map rotates with the player.")
        .define("northLock", false);

    MAP_POSITION = builder
        .comment("Which corner of the screen the map sits in.")
        .defineEnum("position", ScreenCorner.TOP_RIGHT);

    MAP_OPACITY = builder
        .comment("Map opacity. 1.0 = fully opaque, 0.3 = mostly transparent.")
        .defineInRange("opacity", 1.0, 0.3, 1.0);

    MAP_SIZE = builder
        .comment("Map frame width in GUI pixels. Larger shows more terrain at the same zoom.")
        .defineInRange("size", 160, 80, 320);

    MAP_SHAPE = builder
        .comment("Map width-to-height ratio. 1.0 = square, 1.5 = 3:2 wide.")
        .defineInRange("shape", 1.0, 1.0, 2.0);

    builder.pop();

    builder.push("cave");

    CAVE_MODE_ENABLED = builder
        .comment("Auto-switch to cave view when underground. When off, the surface map is always shown.")
        .define("enabled", true);

    builder.pop();

    builder.push("scan");

    SCAN_RADIUS_MULTIPLIER = builder
        .comment("Fraction of render distance to actively rescan. Lower values reduce CPU usage.",
                 "1.0 = scan everything loaded, 0.5 = scan half the render distance.")
        .defineInRange("radiusMultiplier", 1.0, 0.25, 1.0);

    CAVE_SCAN_RADIUS = builder
        .comment("Cave-specific scan radius in blocks. 0 = use the surface scan radius.",
                 "Set a lower value to reduce cave mode CPU usage independently.")
        .defineInRange("caveScanRadius", 0, 0, 256);

    CAVE_FLOOD_RADIUS = builder
        .comment("Maximum distance in blocks for the cave flood fill BFS.",
                 "Higher values reveal more cave space but cost more CPU per flood.")
        .defineInRange("caveFloodRadius", 100, 32, 256);

    builder.pop();

    // -- Rendering engine tuning --
    builder.push("rendering");

    RENDER_SHADING_MODE = builder
        .comment("CLASSIC = sharp pixel-art edges (vanilla map style). HEIGHTFIELD = smooth directional lighting with terrain relief.")
        .defineEnum("shadingMode", ShadingMode.CLASSIC);

    builder.push("lighting");

    RENDER_LIGHT_ANGLE = builder
        .comment("Compass direction of the sun in degrees. 0 = north, 90 = east, 315 = northwest.")
        .defineInRange("lightAngle", 315, 0, 345);

    RENDER_LIGHT_ELEVATION = builder
        .comment("Sun height. Higher = more overhead, lower = more raking shadows.")
        .defineInRange("lightElevation", 2.0, 0.5, 4.0);

    RENDER_AMBIENT = builder
        .comment("Minimum brightness floor. Prevents pure-black shadows.")
        .defineInRange("ambient", 0.55, 0.2, 0.9);

    RENDER_TERRAIN_SMOOTHNESS = builder
        .comment("Higher = smoother terrain relief, lower = more dramatic height differences.")
        .defineInRange("terrainSmoothness", 2.5, 0.5, 8.0);

    builder.pop();

    builder.push("occlusion");

    RENDER_AO_ENABLED = builder
        .comment("Darken valleys and ravines for depth perception.")
        .define("enabled", true);

    RENDER_AO_STRENGTH = builder
        .comment("How aggressively valleys darken.")
        .defineInRange("strength", 0.05, 0.01, 0.20);

    RENDER_AO_MAX = builder
        .comment("Maximum darkening from ambient occlusion.")
        .defineInRange("maxDarkening", 0.30, 0.05, 0.50);

    builder.pop();

    builder.push("terrain");

    RENDER_HEIGHT_FACTOR = builder
        .comment("Per-block brightness scale relative to sea level. Higher = more elevation contrast.")
        .defineInRange("heightFactor", 0.012, 0.0, 0.030);

    RENDER_HEIGHT_MIN = builder
        .comment("Minimum terrain brightness (deep valleys).")
        .defineInRange("heightMin", 0.78, 0.50, 1.0);

    RENDER_HEIGHT_MAX = builder
        .comment("Maximum terrain brightness (mountain peaks).")
        .defineInRange("heightMax", 1.15, 1.0, 1.50);

    RENDER_LEAF_SHADE = builder
        .comment("Leaf block darkening. 1.0 = no darkening, 0.4 = very dark.")
        .defineInRange("leafShade", 0.75, 0.40, 1.0);

    builder.pop();

    builder.push("water");

    RENDER_WATER_ALPHA_BASE = builder
        .comment("Base water overlay opacity (shallow water).")
        .defineInRange("alphaBase", 0.55, 0.10, 0.90);

    RENDER_WATER_ALPHA_DEPTH = builder
        .comment("Additional opacity per block of water depth.")
        .defineInRange("alphaPerDepth", 0.04, 0.0, 0.15);

    RENDER_WATER_ALPHA_MAX = builder
        .comment("Maximum water opacity (deepest water).")
        .defineInRange("alphaMax", 0.82, 0.30, 1.0);

    builder.pop();
    builder.pop();

    builder.push("overlay");

    OVERLAY_MASTER_TOGGLE = builder
        .comment("Master toggle for the entire info overlay.")
        .define("enabled", true);

    OVERLAY_POSITION = builder
        .comment("Where the overlay is placed relative to the map.")
        .defineEnum("position", OverlayPosition.BELOW_MAP);

    OVERLAY_ALIGNMENT = builder
        .comment("Text alignment within the overlay.")
        .defineEnum("alignment", OverlayAlign.LEFT);

    OVERLAY_TEXT_SCALE = builder
        .comment("Text size multiplier. 1.0 = normal, 0.75 = small, 1.5 = large.")
        .defineInRange("textScale", 1.0, 0.5, 1.5);

    OVERLAY_TEXT_COLOR = builder
        .comment("Text color as a packed RGB integer.")
        .defineInRange("textColor", 0xFFFFFF, 0x000000, 0xFFFFFF);

    OVERLAY_BACKGROUND = builder
        .comment("Show a semi-transparent background behind overlay text.")
        .define("background", true);

    OVERLAY_ORDER = builder
        .comment("Display order of all overlay lines (enabled and disabled).")
        .defineList("order", List.of(
            "coordinates", "biome", "time", "weather", "light",
            "dimension", "compass", "fps", "speed", "chunk", "cave_stats"),
            () -> "", o -> o instanceof String);

    OVERLAY_ENABLED = builder
        .comment("Which overlay lines are enabled.")
        .defineList("enabled", List.of("coordinates"),
            () -> "", o -> o instanceof String);

    builder.push("coordinates");
    OVERLAY_COORDS_SHOW_Y = builder
        .comment("Show the Y coordinate in the coordinates line.")
        .define("showY", true);
    builder.pop();

    builder.push("time");
    OVERLAY_TIME_REAL = builder
        .comment("Show real-world time instead of in-game time.")
        .define("realTime", false);
    OVERLAY_TIME_24H = builder
        .comment("Use 24-hour format.")
        .define("use24Hour", false);
    builder.pop();

    builder.pop();

    builder.push("hud");

    BLOCK_TOOLTIP_ENABLED = builder
        .comment("Show a tooltip with the block name near the crosshair.")
        .define("blockTooltip", true);

    BLOCK_TOOLTIP_POSITION = builder
        .comment("Where the block tooltip bar appears on screen.")
        .defineEnum("blockTooltipPosition", TooltipPosition.TOP_CENTER);

    TAB_DISTANCES_ENABLED = builder
        .comment("Show player distances in the tab list.")
        .define("tabDistances", true);

    builder.pop();

    SPEC = builder.build();
  }

  private MapnHudConfig() {}

  // -- Enums --

  public enum ShadingMode {
    CLASSIC("Classic"),
    HEIGHTFIELD("Heightfield");

    private final String label;

    ShadingMode(String label) { this.label = label; }

    public String label() { return label; }
  }

  public enum ScreenCorner {
    TOP_LEFT("Top Left"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right");

    private final String label;

    ScreenCorner(String label) { this.label = label; }

    public String label() { return label; }
  }

  public enum OverlayPosition {
    BELOW_MAP("Below Map"),
    BESIDE_MAP("Beside Map"),
    OPPOSITE_Y("Opposite Side (Vertical)"),
    OPPOSITE_X("Opposite Side (Horizontal)");

    private final String label;

    OverlayPosition(String label) { this.label = label; }

    public String label() { return label; }
  }

  public enum OverlayAlign {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right");

    private final String label;

    OverlayAlign(String label) { this.label = label; }

    public String label() { return label; }
  }

  public enum TooltipPosition {
    CROSSHAIR("Near Crosshair"),
    TOP_CENTER("Top Center"),
    BOTTOM_CENTER("Bottom Center");

    private final String label;

    TooltipPosition(String label) { this.label = label; }

    public String label() { return label; }
  }
}
