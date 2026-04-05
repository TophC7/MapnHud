package dev.mapnhud.client.map;

import java.util.Arrays;

/**
 * Raw terrain column data for a single 16x16 chunk, collected at scan time.
 *
 * <p>Each column has a "known" flag indicating whether real terrain data exists.
 * In surface mode all columns are known. In cave mode, columns not reachable
 * by the flood fill are unknown (known=false) and carry no valid terrain data.
 *
 * <p>The known mask is persisted so cave exploration history survives across
 * sessions. Wall rendering (solid rock visible inside flood bounds) is purely
 * ephemeral and driven by the live flood result at render time.
 *
 * <p>No shading is applied here. Colors are base ARGB values straight from
 * the block color pipeline. Shading happens later in the viewport assembler
 * where the full visible heightfield is available, eliminating chunk boundary
 * seams by design.
 *
 * <p>Indexed as {@code [localX * 16 + localZ]}. X is the outer axis to
 * match the north-to-south (Z) scanning order used by the assembler's
 * elevation shading.
 */
public final class ChunkColorData {

  public static final int SIZE = 16;
  public static final int PIXELS = SIZE * SIZE;

  /** Shared immutable array for surface scans where all columns are known. */
  private static final boolean[] ALL_KNOWN = allTrue();

  private final int[] baseColors;   // ARGB, unshaded
  private final int[] heights;      // rendered block Y (after skip-block resolution)
  private final int[] waterDepths;  // 0 = dry, >0 = water block count
  private final int[] waterTints;   // biome water ARGB (0 when dry)
  private final boolean[] isLeaf;   // true when rendered block is LeavesBlock
  private final boolean[] known;    // true = real terrain data, false = unexplored
  private final boolean cave;       // true = cave-mode scan produced this data
  private final int knownCount;     // cached count of known columns

  /** Surface constructor: all columns are known terrain. */
  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf) {
    this(baseColors, heights, waterDepths, waterTints, isLeaf, ALL_KNOWN, false);
  }

  /** Cave constructor with per-column known mask. */
  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known) {
    this(baseColors, heights, waterDepths, waterTints, isLeaf, known, true);
  }

  /** Full constructor (used by deserialization and merge). */
  ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known, boolean cave) {
    this.baseColors = baseColors;
    this.heights = heights;
    this.waterDepths = waterDepths;
    this.waterTints = waterTints;
    this.isLeaf = isLeaf;
    this.known = known;
    this.cave = cave;
    int kc = 0;
    for (boolean k : known) { if (k) kc++; }
    this.knownCount = kc;
  }

  public int getBaseColor(int localX, int localZ) {
    return baseColors[localX * SIZE + localZ];
  }

  public int getHeight(int localX, int localZ) {
    return heights[localX * SIZE + localZ];
  }

  public int getWaterDepth(int localX, int localZ) {
    return waterDepths[localX * SIZE + localZ];
  }

  public int getWaterTint(int localX, int localZ) {
    return waterTints[localX * SIZE + localZ];
  }

  public boolean isLeaf(int localX, int localZ) {
    return isLeaf[localX * SIZE + localZ];
  }

  /** Returns true if this column has real terrain data (not unexplored). */
  public boolean isKnown(int localX, int localZ) {
    return known[localX * SIZE + localZ];
  }

  /** Returns true if this data was scanned in cave mode. */
  public boolean isCaveData() {
    return cave;
  }

  /** Returns the cached count of columns with known terrain data. */
  public int knownCount() {
    return knownCount;
  }

  /**
   * Merges two cave-mode chunks additively. Known columns in the fresh scan
   * overwrite old data (most up-to-date). Unknown columns in the fresh scan
   * preserve old known data (exploration history). This accumulates terrain
   * without erasing previously discovered columns.
   */
  public static ChunkColorData mergeCave(ChunkColorData old, ChunkColorData fresh) {
    int[] bc = new int[PIXELS];
    int[] ht = new int[PIXELS];
    int[] wd = new int[PIXELS];
    int[] wt = new int[PIXELS];
    boolean[] lf = new boolean[PIXELS];
    boolean[] kn = new boolean[PIXELS];

    for (int i = 0; i < PIXELS; i++) {
      if (fresh.known[i]) {
        // Fresh has terrain: use it (most current)
        bc[i] = fresh.baseColors[i];
        ht[i] = fresh.heights[i];
        wd[i] = fresh.waterDepths[i];
        wt[i] = fresh.waterTints[i];
        lf[i] = fresh.isLeaf[i];
        kn[i] = true;
      } else if (old.known[i]) {
        // Fresh is unknown but old has terrain: keep old (preserve history)
        bc[i] = old.baseColors[i];
        ht[i] = old.heights[i];
        wd[i] = old.waterDepths[i];
        wt[i] = old.waterTints[i];
        lf[i] = old.isLeaf[i];
        kn[i] = true;
      }
      // Both unknown: kn[i] stays false, arrays stay zeroed
    }

    return new ChunkColorData(bc, ht, wd, wt, lf, kn, true);
  }

  /**
   * Fills unknown columns in the primary layer with known data from the
   * filler. Returns the primary unchanged (no allocation) if no gaps exist.
   * Used by the renderer to composite multiple cave layers so that known
   * terrain from any layer is never hidden behind black walls.
   */
  public static ChunkColorData fillGaps(ChunkColorData primary, ChunkColorData filler) {
    // Fast check: any gaps in primary that filler can fill?
    boolean hasGaps = false;
    for (int i = 0; i < PIXELS; i++) {
      if (!primary.known[i] && filler.known[i]) {
        hasGaps = true;
        break;
      }
    }
    if (!hasGaps) return primary;

    int[] bc = new int[PIXELS];
    int[] ht = new int[PIXELS];
    int[] wd = new int[PIXELS];
    int[] wt = new int[PIXELS];
    boolean[] lf = new boolean[PIXELS];
    boolean[] kn = new boolean[PIXELS];

    for (int i = 0; i < PIXELS; i++) {
      if (primary.known[i]) {
        bc[i] = primary.baseColors[i];
        ht[i] = primary.heights[i];
        wd[i] = primary.waterDepths[i];
        wt[i] = primary.waterTints[i];
        lf[i] = primary.isLeaf[i];
        kn[i] = true;
      } else if (filler.known[i]) {
        bc[i] = filler.baseColors[i];
        ht[i] = filler.heights[i];
        wd[i] = filler.waterDepths[i];
        wt[i] = filler.waterTints[i];
        lf[i] = filler.isLeaf[i];
        kn[i] = true;
      }
    }

    return new ChunkColorData(bc, ht, wd, wt, lf, kn, true);
  }

  // -- Serialization (persistence v3) --

  /** Format version. v3 adds known mask; v1/v2 are rejected on load. */
  private static final byte SERIAL_VERSION = 3;

  private static final byte MODE_SURFACE = 0;
  private static final byte MODE_CAVE = 1;

  /**
   * Serialized size: version(1) + mode(1) + 4 int arrays(4*256*4=4096)
   * + isLeaf bitset(32) + known bitset(32) = 4162 bytes.
   */
  public static final int SERIAL_BYTES = 1 + 1 + (PIXELS * 4 * 4) + (PIXELS / 8) + (PIXELS / 8);

  /**
   * Serializes to a byte array for disk persistence. Includes the known
   * mask so cave exploration history survives across sessions.
   */
  public byte[] toBytes() {
    byte[] buf = new byte[SERIAL_BYTES];
    int pos = 0;

    buf[pos++] = SERIAL_VERSION;
    buf[pos++] = cave ? MODE_CAVE : MODE_SURFACE;
    pos = writeInts(buf, pos, baseColors);
    pos = writeInts(buf, pos, heights);
    pos = writeInts(buf, pos, waterDepths);
    pos = writeInts(buf, pos, waterTints);
    packBooleans(buf, pos, isLeaf);
    pos += PIXELS / 8;
    packBooleans(buf, pos, known);
    return buf;
  }

  /**
   * Deserializes from a byte array produced by {@link #toBytes()}.
   * Only v3 format is accepted; older formats are rejected.
   */
  public static ChunkColorData fromBytes(byte[] buf) {
    if (buf.length < 2) {
      throw new IllegalArgumentException("Buffer too short: " + buf.length);
    }

    int pos = 0;
    int version = buf[pos++] & 0xFF;

    if (version != 3) {
      throw new IllegalArgumentException(
          "Unsupported serial version: " + version + " (only v3 supported)");
    }

    boolean isCave = buf[pos++] == MODE_CAVE;

    if (buf.length < SERIAL_BYTES) {
      throw new IllegalArgumentException(
          "Buffer too short for v3: " + buf.length + " (need " + SERIAL_BYTES + ")");
    }

    int[] baseColors = new int[PIXELS];
    int[] heights = new int[PIXELS];
    int[] waterDepths = new int[PIXELS];
    int[] waterTints = new int[PIXELS];
    boolean[] isLeaf = new boolean[PIXELS];
    boolean[] known = new boolean[PIXELS];

    pos = readInts(buf, pos, baseColors);
    pos = readInts(buf, pos, heights);
    pos = readInts(buf, pos, waterDepths);
    pos = readInts(buf, pos, waterTints);
    unpackBooleans(buf, pos, isLeaf);
    pos += PIXELS / 8;
    unpackBooleans(buf, pos, known);

    return new ChunkColorData(baseColors, heights, waterDepths, waterTints, isLeaf, known, isCave);
  }

  private static boolean[] allTrue() {
    boolean[] arr = new boolean[PIXELS];
    Arrays.fill(arr, true);
    return arr;
  }

  private static int writeInts(byte[] buf, int pos, int[] arr) {
    for (int v : arr) {
      buf[pos++] = (byte) (v >> 24);
      buf[pos++] = (byte) (v >> 16);
      buf[pos++] = (byte) (v >> 8);
      buf[pos++] = (byte) v;
    }
    return pos;
  }

  private static int readInts(byte[] buf, int pos, int[] arr) {
    for (int i = 0; i < arr.length; i++) {
      arr[i] = ((buf[pos++] & 0xFF) << 24)
             | ((buf[pos++] & 0xFF) << 16)
             | ((buf[pos++] & 0xFF) << 8)
             | (buf[pos++] & 0xFF);
    }
    return pos;
  }

  private static void packBooleans(byte[] buf, int pos, boolean[] arr) {
    for (int i = 0; i < arr.length; i += 8) {
      int b = 0;
      for (int bit = 0; bit < 8 && i + bit < arr.length; bit++) {
        if (arr[i + bit]) b |= (1 << bit);
      }
      buf[pos + i / 8] = (byte) b;
    }
  }

  private static void unpackBooleans(byte[] buf, int pos, boolean[] arr) {
    for (int i = 0; i < arr.length; i += 8) {
      int b = buf[pos + i / 8] & 0xFF;
      for (int bit = 0; bit < 8 && i + bit < arr.length; bit++) {
        arr[i + bit] = (b & (1 << bit)) != 0;
      }
    }
  }
}
