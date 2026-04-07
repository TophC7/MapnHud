package dev.mapnhud.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mapnhud.client.map.CaveFloodFill;
import dev.mapnhud.client.map.ChunkColorCache;
import dev.mapnhud.client.map.ChunkColorData;
import dev.mapnhud.client.map.ChunkScanner;
import dev.mapnhud.client.map.cave.CaveFieldState;

/**
 * Two-pass viewport shader that gathers raw column data from the chunk cache
 * and shades it using the complete visible heightfield.
 *
 * <p><b>Pass 1 (Gather):</b> Reads raw base colors, heights, water metadata,
 * and leaf flags from cached chunk data into viewport-sized arrays.
 *
 * <p><b>Pass 2 (Shade):</b> Computes surface normals from the heightfield
 * using central-difference gradients, applies directional lighting, optional
 * ambient occlusion for valley darkening, leaf darkening, and water overlay
 * blending, then writes final ABGR pixels to the NativeImage.
 *
 * <p>All shading parameters are read from {@link RenderConfig}, which is
 * snapshotted once per tick from config values. This allows live tuning
 * via the config screen without restarting.
 *
 * <p>Because shading operates on the assembled viewport rather than per-chunk,
 * cross-chunk seams are impossible by design.
 */
public class MinimapAssembler {

  /** Dark gray placeholder for chunks not yet scanned (ABGR format). */
  private static final int PLACEHOLDER = ChunkScanner.abgrFromArgb(0xFF1A1A1A);

  /** Wall color for cave mode solid columns (ABGR format). */
  private static final int WALL_COLOR = ChunkScanner.abgrFromArgb(0xFF000000);
  /** Unknown color for cave columns that are not confirmed as walls yet. */
  private static final int UNKNOWN_COLOR = ChunkScanner.abgrFromArgb(0xFF111111);

  // -- Pre-allocated viewport arrays (recreated when mapSize changes) --

  private int allocatedSize = -1;
  private int[] vBaseColors;
  private int[] vHeights;
  private int[] vWaterDepths;
  private int[] vWaterTints;
  private byte[] vFieldStates;
  private boolean[] vIsLeaf;
  private boolean[] vKnown;
  private boolean[] vHasData;

  // Per-frame shading state (set in shade(), read by compute methods)
  private RenderConfig cfg;
  private float lightX, lightY, lightZ;

  /**
   * Assemble and shade the visible area into the given image.
   *
   * @param image     square NativeImage to write into (ABGR format)
   * @param cache     the chunk color cache (raw column data)
   * @param centerX   player's block X
   * @param centerZ   player's block Z
   * @param scale     blocks per pixel (1 = normal, 2 = zoomed out 2x, etc.)
   * @param mapSize   pixel dimensions of the square image
   * @param seaLevel  world sea level for height-relative brightness
   * @param config    rendering config snapshot from this tick
   */
  public void assemble(
      NativeImage image, ChunkColorCache cache,
      int centerX, int centerZ, int scale, int mapSize,
      int seaLevel, RenderConfig config) {

    ensureArrays(mapSize);
    gather(cache, centerX, centerZ, scale, mapSize);
    shade(image, mapSize, seaLevel, config, centerX, centerZ, scale);
  }

  private void ensureArrays(int mapSize) {
    if (mapSize == allocatedSize) return;
    int len = mapSize * mapSize;
    vBaseColors = new int[len];
    vHeights = new int[len];
    vWaterDepths = new int[len];
    vWaterTints = new int[len];
    vFieldStates = new byte[len];
    vIsLeaf = new boolean[len];
    vKnown = new boolean[len];
    vHasData = new boolean[len];
    allocatedSize = mapSize;
  }

  /**
   * Pass 1: Populate viewport arrays from cached chunk data.
   */
  private void gather(
      ChunkColorCache cache, int centerX, int centerZ, int scale, int mapSize) {

    int half = mapSize / 2;
    int halfWorld = half * scale;
    int minWorldX = centerX - halfWorld;
    int minWorldZ = centerZ - halfWorld;

    // Track last chunk to avoid redundant cache lookups
    int lastCx = Integer.MIN_VALUE;
    int lastCz = Integer.MIN_VALUE;
    ChunkColorData lastData = null;
    boolean caveMode = cache.isCaveMode();
    CaveFloodFill.Result flood = caveMode ? cache.getFloodResult() : CaveFloodFill.EMPTY;

    for (int px = 0; px < mapSize; px++) {
      int wx = minWorldX + px * scale;
      int cx = wx >> 4;

      for (int pz = 0; pz < mapSize; pz++) {
        int wz = minWorldZ + pz * scale;
        int cz = wz >> 4;
        int i = px * mapSize + pz;

        if (caveMode && !flood.isOutsideRadius(wx, wz) && !flood.isReachable(wx, wz)) {
          vFieldStates[i] = currentFloodFieldState(flood, wx, wz, cx, cz);
          vKnown[i] = false;
          vHasData[i] = true;
          continue;
        }

        if (cx != lastCx || cz != lastCz) {
          lastData = cache.get(cx, cz);
          lastCx = cx;
          lastCz = cz;
        }

        if (lastData != null) {
          int lx = wx & 15;
          int lz = wz & 15;
          vBaseColors[i] = lastData.getBaseColor(lx, lz);
          vHeights[i] = lastData.getHeight(lx, lz);
          vWaterDepths[i] = lastData.getWaterDepth(lx, lz);
          vWaterTints[i] = lastData.getWaterTint(lx, lz);
          vFieldStates[i] = lastData.getFieldState(lx, lz);
          vIsLeaf[i] = lastData.isLeaf(lx, lz);
          vKnown[i] = lastData.isKnown(lx, lz);
          vHasData[i] = true;
        } else {
          vFieldStates[i] = 0;
          vHasData[i] = false;
        }
      }
    }
  }

  private static byte currentFloodFieldState(
      CaveFloodFill.Result flood, int wx, int wz, int cx, int cz) {
    if (!flood.complete()
        || flood.isUnknownChunk(cx, cz)
        || flood.isOutsideRadius(wx, wz)) {
      return CaveFieldState.UNKNOWN;
    }
    return CaveFieldState.BOUNDARY;
  }

  /**
   * Pass 2: Shade viewport and write final pixels to the image.
   * Dispatches to classic (sharp pixel-art edges) or heightfield (smooth
   * directional lighting) based on the config's shading mode.
   */
  private void shade(NativeImage image, int mapSize, int seaLevel,
                     RenderConfig config, int centerX, int centerZ, int scale) {
    this.cfg = config;

    if (config.isClassic()) {
      shadeClassic(image, mapSize, seaLevel, centerX, centerZ, scale);
    } else {
      shadeHeightfield(image, mapSize, seaLevel);
    }
  }

  // -- Classic shading (sharp pixel-art edges, vanilla map style) --

  /** Edge shading dither amplitude and thresholds. */
  private static final double DITHER_AMPLITUDE = 0.4;
  private static final double EDGE_THRESH_HIGH = 0.6;
  private static final double EDGE_THRESH_LOW = -0.3;
  private static final double EDGE_THRESH_LOWEST = -0.6;

  /**
   * Classic shade pass: discrete edge-brightness bands with dither, matching
   * the vanilla map's pixel-art look. Uses MapColor.Brightness modifiers
   * for sharp per-pixel shading steps.
   *
   * <p>Runs on the viewport-first architecture so chunk boundary seams are
   * still eliminated, unlike the original per-chunk implementation.
   */
  private void shadeClassic(NativeImage image, int mapSize, int seaLevel,
                            int centerX, int centerZ, int scale) {
    int half = mapSize / 2;
    int halfWorld = half * scale;
    int minWorldX = centerX - halfWorld;
    int minWorldZ = centerZ - halfWorld;

    for (int px = 0; px < mapSize; px++) {
      int worldX = minWorldX + px * scale;

      for (int pz = 0; pz < mapSize; pz++) {
        int i = px * mapSize + pz;

        if (!vHasData[i]) {
          image.setPixelRGBA(px, pz, PLACEHOLDER);
          continue;
        }
        if (!vKnown[i]) {
          image.setPixelRGBA(px, pz,
              CaveFieldState.has(vFieldStates[i], CaveFieldState.UNKNOWN)
                  ? UNKNOWN_COLOR
                  : WALL_COLOR);
          continue;
        }

        int worldZ = minWorldZ + pz * scale;
        int baseColor = vBaseColors[i];
        int height = vHeights[i];
        int waterDepth = vWaterDepths[i];

        // Edge brightness from previous Z pixel in viewport (seam-free)
        int prevHeight = height;
        if (pz > 0 && vHasData[i - 1]) {
          prevHeight = vHeights[i - 1];
        }
        int edgeMod = computeEdgeBrightness(height, prevHeight, worldX, worldZ);

        int shadedColor;
        if (waterDepth > 0) {
          int shadedFloor = applyClassicShading(baseColor, 1.0f, edgeMod);
          shadedColor = blendWaterColor(shadedFloor, vWaterTints[i], waterDepth);
        } else {
          float heightMod = computeHeightModifier(height, seaLevel);
          if (vIsLeaf[i]) {
            heightMod *= cfg.leafShade();
          }
          shadedColor = applyClassicShading(baseColor, heightMod, edgeMod);
        }

        image.setPixelRGBA(px, pz, ChunkScanner.abgrFromArgb(shadedColor));
      }
    }
  }

  /**
   * Computes a discrete edge brightness modifier from the height delta between
   * adjacent pixels. Includes coordinate-based dither for visual noise that
   * gives the pixel-art texture. Returns a MapColor.Brightness modifier value.
   */
  private static int computeEdgeBrightness(
      int currentHeight, int prevHeight, int worldX, int worldZ) {

    double d3 = (double) (currentHeight - prevHeight)
        + (((worldX + worldZ) & 1) - 0.5) * DITHER_AMPLITUDE;

    if (d3 > EDGE_THRESH_HIGH) return 255;      // HIGH
    if (d3 < EDGE_THRESH_LOWEST) return 135;     // LOWEST
    if (d3 < EDGE_THRESH_LOW) return 180;        // LOW
    return 220;                                   // NORMAL
  }

  /** Combines height modifier with discrete edge brightness, then delegates to applyBrightness. */
  private static int applyClassicShading(int argb, float heightMod, int edgeMod) {
    return applyBrightness(argb, heightMod * edgeMod / 255.0f);
  }

  // -- Heightfield shading (smooth directional lighting) --

  /**
   * Heightfield shade pass: surface normals from central differences,
   * directional lighting, and optional ambient occlusion for smooth
   * terrain relief.
   */
  private void shadeHeightfield(NativeImage image, int mapSize, int seaLevel) {
    computeLightDirection(cfg);

    for (int px = 0; px < mapSize; px++) {
      for (int pz = 0; pz < mapSize; pz++) {
        int i = px * mapSize + pz;

        if (!vHasData[i]) {
          image.setPixelRGBA(px, pz, PLACEHOLDER);
          continue;
        }
        if (!vKnown[i]) {
          image.setPixelRGBA(px, pz,
              CaveFieldState.has(vFieldStates[i], CaveFieldState.UNKNOWN)
                  ? UNKNOWN_COLOR
                  : WALL_COLOR);
          continue;
        }

        int baseColor = vBaseColors[i];
        int height = vHeights[i];
        int waterDepth = vWaterDepths[i];

        float brightness = computeDirectionalBrightness(px, pz, mapSize);

        float ao = 1.0f;
        if (cfg.aoEnabled()) {
          ao = computeAmbientOcclusion(px, pz, mapSize);
        }

        int shadedColor;
        if (waterDepth > 0) {
          // Water surface should appear flat, so skip directional lighting
          // from the riverbed floor. AO still applies for coastal depth cues.
          int shadedFloor = applyBrightness(baseColor, ao);
          shadedColor = blendWaterColor(shadedFloor, vWaterTints[i], waterDepth);
        } else {
          float heightMod = computeHeightModifier(height, seaLevel);
          float combined = heightMod * brightness * ao;
          if (vIsLeaf[i]) {
            combined *= cfg.leafShade();
          }
          shadedColor = applyBrightness(baseColor, combined);
        }

        image.setPixelRGBA(px, pz, ChunkScanner.abgrFromArgb(shadedColor));
      }
    }
  }

  // -- Light direction --

  /**
   * Converts the config's light angle (compass degrees) and elevation into
   * a normalized direction vector. Computed once per shade pass.
   */
  private void computeLightDirection(RenderConfig config) {
    double rad = Math.toRadians(config.lightAngle());
    float lx = (float) Math.sin(rad);
    float ly = config.lightElevation();
    float lz = (float) -Math.cos(rad);
    float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
    lightX = lx / len;
    lightY = ly / len;
    lightZ = lz / len;
  }

  // -- Heightfield lighting --

  /**
   * Computes directional brightness from heightfield surface normals.
   * Uses central-difference gradients from the 4 cardinal neighbors to
   * build a surface normal, then dots it with the sun direction.
   */
  private float computeDirectionalBrightness(int px, int pz, int mapSize) {
    int centerIdx = px * mapSize + pz;
    float centerH = vHeights[centerIdx];

    // Central-difference gradients from cardinal neighbors
    float hL = safeHeight(px - 1, pz, mapSize, centerH);
    float hR = safeHeight(px + 1, pz, mapSize, centerH);
    float hU = safeHeight(px, pz - 1, mapSize, centerH);
    float hD = safeHeight(px, pz + 1, mapSize, centerH);

    float dx = hR - hL; // height gradient in X (east - west)
    float dz = hD - hU; // height gradient in Z (south - north)

    // Surface normal: (-dx, smoothness, -dz) then normalize
    float nx = -dx;
    float ny = cfg.terrainSmoothness();
    float nz = -dz;
    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    nx /= len;
    ny /= len;
    nz /= len;

    // Diffuse lighting: dot(normal, lightDir), clamped to [0, 1]
    float diffuse = Math.max(0.0f, nx * lightX + ny * lightY + nz * lightZ);
    float ambient = cfg.ambient();

    return ambient + (1.0f - ambient) * diffuse;
  }

  /**
   * Computes ambient occlusion by comparing local height to the average of
   * its 8 neighbors. Valleys (lower than neighbors) get darkened.
   */
  private float computeAmbientOcclusion(int px, int pz, int mapSize) {
    int centerIdx = px * mapSize + pz;
    float center = vHeights[centerIdx];

    float sum = safeHeight(px - 1, pz - 1, mapSize, center)
        + safeHeight(px, pz - 1, mapSize, center)
        + safeHeight(px + 1, pz - 1, mapSize, center)
        + safeHeight(px - 1, pz, mapSize, center)
        + safeHeight(px + 1, pz, mapSize, center)
        + safeHeight(px - 1, pz + 1, mapSize, center)
        + safeHeight(px, pz + 1, mapSize, center)
        + safeHeight(px + 1, pz + 1, mapSize, center);

    float avg = sum / 8.0f;
    float occlusion = (avg - center) * cfg.aoStrength();
    occlusion = Math.max(0.0f, Math.min(cfg.aoMax(), occlusion));

    return 1.0f - occlusion;
  }

  /**
   * Safe height lookup that clamps to viewport bounds and handles uncached
   * pixels. Returns {@code fallback} (the center pixel's height) for
   * out-of-bounds or uncached neighbors, producing neutral normals at
   * cache boundaries instead of false cliff artifacts.
   */
  private float safeHeight(int px, int pz, int mapSize, float fallback) {
    if (px < 0 || pz < 0 || px >= mapSize || pz >= mapSize) return fallback;

    int i = px * mapSize + pz;
    if (!vHasData[i] || !vKnown[i]) return fallback;
    return vHeights[i];
  }

  // -- Color math --

  private float computeHeightModifier(int height, int seaLevel) {
    int delta = height - seaLevel;
    float mod = 1.0f + delta * cfg.heightFactor();
    return Math.max(cfg.heightModMin(), Math.min(cfg.heightModMax(), mod));
  }

  private static int applyBrightness(int argb, float brightness) {
    if (brightness < 0) brightness = 0;
    int a = (argb >> 24) & 0xFF;
    int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * brightness));
    int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * brightness));
    int b = Math.min(255, (int) ((argb & 0xFF) * brightness));
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  /**
   * Alpha-blends the floor block color with a biome water tint. Deeper water
   * shows more blue, shallow water lets the floor show through.
   */
  private int blendWaterColor(int floorArgb, int waterTint, int depth) {
    float alpha = Math.min(cfg.waterAlphaMax(),
        cfg.waterAlphaBase() + depth * cfg.waterAlphaPerDepth());

    int fR = (floorArgb >> 16) & 0xFF;
    int fG = (floorArgb >> 8) & 0xFF;
    int fB = floorArgb & 0xFF;

    int wR = (waterTint >> 16) & 0xFF;
    int wG = (waterTint >> 8) & 0xFF;
    int wB = waterTint & 0xFF;

    float inv = 1 - alpha;
    int r = (int) (fR * inv + wR * alpha);
    int g = (int) (fG * inv + wG * alpha);
    int b = (int) (fB * inv + wB * alpha);

    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }
}
