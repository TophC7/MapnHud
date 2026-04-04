package dev.mapnhud.client;

import xyz.kwahson.core.config.SafeConfig;

/**
 * Snapshot of all rendering config values, built once per tick from
 * {@link MapnHudConfig}. Passed through the render pipeline so the
 * assembler never touches the config tree directly.
 *
 * <p>Records auto-generate {@code equals()}, so the renderer can cheaply
 * detect config changes by comparing snapshots.
 */
public record RenderConfig(
    // Mode
    MapnHudConfig.ShadingMode shadingMode,
    // Lighting (heightfield mode)
    int lightAngle,
    float lightElevation,
    float ambient,
    float terrainSmoothness,
    // Ambient occlusion (heightfield mode)
    boolean aoEnabled,
    float aoStrength,
    float aoMax,
    // Terrain
    float heightFactor,
    float heightModMin,
    float heightModMax,
    float leafShade,
    // Water
    float waterAlphaBase,
    float waterAlphaPerDepth,
    float waterAlphaMax
) {

  /** Default values matching the hardcoded constants before config was added. */
  public static final RenderConfig DEFAULT = new RenderConfig(
      MapnHudConfig.ShadingMode.CLASSIC,
      315, 2.0f, 0.55f, 2.5f,
      true, 0.05f, 0.3f,
      0.012f, 0.78f, 1.15f, 0.75f,
      0.55f, 0.04f, 0.82f
  );

  public boolean isClassic() {
    return shadingMode == MapnHudConfig.ShadingMode.CLASSIC;
  }

  /** Reads current config values into a snapshot. Fallbacks reference DEFAULT to avoid drift. */
  public static RenderConfig fromConfig() {
    return new RenderConfig(
        SafeConfig.getEnum(MapnHudConfig.RENDER_SHADING_MODE, DEFAULT.shadingMode),
        SafeConfig.getInt(MapnHudConfig.RENDER_LIGHT_ANGLE, DEFAULT.lightAngle),
        SafeConfig.getFloat(MapnHudConfig.RENDER_LIGHT_ELEVATION, DEFAULT.lightElevation),
        SafeConfig.getFloat(MapnHudConfig.RENDER_AMBIENT, DEFAULT.ambient),
        SafeConfig.getFloat(MapnHudConfig.RENDER_TERRAIN_SMOOTHNESS, DEFAULT.terrainSmoothness),
        SafeConfig.getBool(MapnHudConfig.RENDER_AO_ENABLED, DEFAULT.aoEnabled),
        SafeConfig.getFloat(MapnHudConfig.RENDER_AO_STRENGTH, DEFAULT.aoStrength),
        SafeConfig.getFloat(MapnHudConfig.RENDER_AO_MAX, DEFAULT.aoMax),
        SafeConfig.getFloat(MapnHudConfig.RENDER_HEIGHT_FACTOR, DEFAULT.heightFactor),
        SafeConfig.getFloat(MapnHudConfig.RENDER_HEIGHT_MIN, DEFAULT.heightModMin),
        SafeConfig.getFloat(MapnHudConfig.RENDER_HEIGHT_MAX, DEFAULT.heightModMax),
        SafeConfig.getFloat(MapnHudConfig.RENDER_LEAF_SHADE, DEFAULT.leafShade),
        SafeConfig.getFloat(MapnHudConfig.RENDER_WATER_ALPHA_BASE, DEFAULT.waterAlphaBase),
        SafeConfig.getFloat(MapnHudConfig.RENDER_WATER_ALPHA_DEPTH, DEFAULT.waterAlphaPerDepth),
        SafeConfig.getFloat(MapnHudConfig.RENDER_WATER_ALPHA_MAX, DEFAULT.waterAlphaMax)
    );
  }
}
