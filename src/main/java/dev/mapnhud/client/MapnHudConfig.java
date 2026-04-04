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
            "dimension", "compass", "fps", "speed", "chunk"),
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
