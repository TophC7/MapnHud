package dev.mapnhud.client;

import xyz.kwahson.core.config.SafeConfig;

/**
 * Per-tick snapshot of every config value the render path needs, stored as
 * primitives so the renderer never traverses the config tree. The tick handler
 * (see {@link MinimapClientTickHandler}) calls {@link #refresh(int)} once per
 * client tick, and getters serve the cached values to the render thread.
 */
public final class MinimapConfigCache {

  private MinimapConfigCache() {}

  // -- frame layout --

  private static int cachedDisplaySize = 160;
  private static double cachedAspectRatio = 1.0;
  private static float cachedOpacity = 1.0f;
  private static boolean cachedNorthLock = false;
  private static ScreenCorner cachedPosition = ScreenCorner.TOP_RIGHT;

  // -- rendering --

  private static RenderConfig cachedRenderConfig = RenderConfig.DEFAULT;
  private static int cachedZoomScale = MapnHudConfig.ZOOM_SCALES[0];

  // -- scan radius (computed from config + render distance each tick) --

  private static int cachedScanRadiusChunks = 12;
  private static int cachedCaveScanRadiusChunks = 12;
  private static int cachedCaveFloodRadiusBlocks = 100;

  /**
   * Re-read every cached value from the config. Call once per client tick,
   * after the keybind handler has run so any in-tick config writes from key
   * presses propagate into the cache the same tick.
   *
   * @param renderDistance current vanilla render distance, used to scale the scan radius
   */
  public static void refresh(int renderDistance) {
    cachedDisplaySize = SafeConfig.getInt(MapnHudConfig.MAP_SIZE, 160);
    cachedAspectRatio = SafeConfig.getDouble(MapnHudConfig.MAP_SHAPE, 1.0);
    cachedOpacity = (float) SafeConfig.getDouble(MapnHudConfig.MAP_OPACITY, 1.0);
    cachedNorthLock = SafeConfig.getBool(MapnHudConfig.MAP_NORTH_LOCK, false);
    cachedPosition = SafeConfig.getEnum(MapnHudConfig.MAP_POSITION, ScreenCorner.TOP_RIGHT);
    cachedRenderConfig = RenderConfig.fromConfig();
    cachedZoomScale = SafeConfig.getInt(MapnHudConfig.MAP_ZOOM, MapnHudConfig.ZOOM_SCALES[0]);

    double multiplier = SafeConfig.getDouble(MapnHudConfig.SCAN_RADIUS_MULTIPLIER, 1.0);
    cachedScanRadiusChunks = Math.max(1, (int) (renderDistance * multiplier));

    int caveScanBlocks = SafeConfig.getInt(MapnHudConfig.CAVE_SCAN_RADIUS, 0);
    cachedCaveScanRadiusChunks = caveScanBlocks == 0
        ? cachedScanRadiusChunks
        : Math.max(1, caveScanBlocks / 16);

    cachedCaveFloodRadiusBlocks = SafeConfig.getInt(MapnHudConfig.CAVE_FLOOD_RADIUS, 100);
  }

  public static int getDisplaySize() { return cachedDisplaySize; }
  public static double getAspectRatio() { return cachedAspectRatio; }
  public static float getOpacity() { return cachedOpacity; }
  public static boolean isNorthLocked() { return cachedNorthLock; }
  public static ScreenCorner getPosition() { return cachedPosition; }
  public static RenderConfig getRenderConfig() { return cachedRenderConfig; }
  public static int getZoomScale() { return cachedZoomScale; }
  public static int getScanRadiusChunks() { return cachedScanRadiusChunks; }
  public static int getCaveScanRadiusChunks() { return cachedCaveScanRadiusChunks; }
  public static int getCaveFloodRadiusBlocks() { return cachedCaveFloodRadiusBlocks; }
}
