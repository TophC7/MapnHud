package dev.foxmap.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration for Fox Map.
 *
 * <p>All values are CLIENT type, stored in {@code fox_map-client.toml}.
 * Changes take effect immediately without restart via {@code .get()}.
 */
public final class FoxMapConfig {

  public static final ModConfigSpec SPEC;

  // -- Map --
  public static final ModConfigSpec.IntValue MAP_ZOOM;
  public static final ModConfigSpec.BooleanValue MAP_NORTH_LOCK;
  public static final ModConfigSpec.EnumValue<ScreenCorner> MAP_POSITION;
  public static final ModConfigSpec.DoubleValue MAP_OPACITY;
  public static final ModConfigSpec.IntValue MAP_SIZE;
  public static final ModConfigSpec.DoubleValue MAP_SHAPE;

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

    builder.push("hud");

    BLOCK_TOOLTIP_ENABLED = builder
        .comment("Show a tooltip with the block name near the crosshair.")
        .define("blockTooltip", true);

    BLOCK_TOOLTIP_POSITION = builder
        .comment("Where the block tooltip bar appears on screen.")
        .defineEnum("blockTooltipPosition", TooltipPosition.CROSSHAIR);

    TAB_DISTANCES_ENABLED = builder
        .comment("Show player distances in the tab list.")
        .define("tabDistances", true);

    builder.pop();

    SPEC = builder.build();
  }

  private FoxMapConfig() {}

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

  public enum TooltipPosition {
    CROSSHAIR("Near Crosshair"),
    TOP_CENTER("Top Center"),
    BOTTOM_CENTER("Bottom Center");

    private final String label;

    TooltipPosition(String label) { this.label = label; }

    public String label() { return label; }
  }
}
