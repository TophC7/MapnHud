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
    ShadingMode shadingMode,
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
      ShadingMode.CLASSIC,
      315, 2.0f, 0.55f, 2.5f,
      true, 0.05f, 0.3f,
      0.012f, 0.78f, 1.15f, 0.75f,
      0.55f, 0.04f, 0.82f
  );

  public boolean isClassic() {
    return shadingMode == ShadingMode.CLASSIC;
  }

  /** Reads current config values into a snapshot. Fallbacks reference DEFAULT to avoid drift. */
  public static RenderConfig fromConfig() {
    return new RenderConfig(
        SafeConfig.getEnum(MapnHudConfig.RENDER_SHADING_MODE, DEFAULT.shadingMode),
        SafeConfig.getInt(MapnHudConfig.RENDER_LIGHT_ANGLE, DEFAULT.lightAngle),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_LIGHT_ELEVATION, DEFAULT.lightElevation),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_AMBIENT, DEFAULT.ambient),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_TERRAIN_SMOOTHNESS, DEFAULT.terrainSmoothness),
        SafeConfig.getBool(MapnHudConfig.RENDER_AO_ENABLED, DEFAULT.aoEnabled),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_AO_STRENGTH, DEFAULT.aoStrength),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_AO_MAX, DEFAULT.aoMax),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_HEIGHT_FACTOR, DEFAULT.heightFactor),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_HEIGHT_MIN, DEFAULT.heightModMin),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_HEIGHT_MAX, DEFAULT.heightModMax),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_LEAF_SHADE, DEFAULT.leafShade),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_WATER_ALPHA_BASE, DEFAULT.waterAlphaBase),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_WATER_ALPHA_DEPTH, DEFAULT.waterAlphaPerDepth),
        (float) SafeConfig.getDouble(MapnHudConfig.RENDER_WATER_ALPHA_MAX, DEFAULT.waterAlphaMax)
    );
  }
}
