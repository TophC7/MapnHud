package dev.mapnhud.client.map;

import dev.mapnhud.client.map.cave.CaveFieldState;
import java.util.Arrays;

/**
 * Raw terrain column data for a single 16x16 chunk, collected at scan time.
 *
 * <p>Each column has a "known" flag indicating whether real terrain data exists.
 * Cave data also carries per-column field flags (unknown/reachable/boundary).
 * In surface mode all columns are known.
 *
 * <p>The known mask is persisted (via {@link ChunkColorDataCodec}) so cave
 * exploration history survives across sessions. Cave field flags are
 * runtime-only and recalculated from live flood data on rescans.
 *
 * <p>Indexed as {@code [localX * 16 + localZ]}. X is the outer axis to match
 * the north-to-south (Z) scanning order used by the assembler's elevation
 * shading.
 *
 * <p>Surface vs cave data is distinguished by whether {@link #fieldStates} is
 * present. Surface data has no field-state machinery; cave data always has a
 * field-states array even when every column is known.
 */
public final class ChunkColorData {

  public static final int SIZE = 16;
  public static final int PIXELS = SIZE * SIZE;

  /**
   * Shared known mask for surface scans where every column is known terrain.
   * Treat as immutable. Returned by {@link #knownArrayForCodec()} only because
   * the codec performs a read-only pack of the bits.
   */
  private static final boolean[] ALL_KNOWN = filledTrue();

  private final int[] baseColors;   // ARGB, unshaded
  private final int[] heights;      // rendered block Y (after skip-block resolution)
  private final int[] waterDepths;  // 0 = dry, >0 = water block count
  private final int[] waterTints;   // biome water ARGB (0 when dry)
  private final boolean[] isLeaf;   // true when rendered block is LeavesBlock
  private final boolean[] known;    // true = real terrain data, false = unexplored
  private final byte[] fieldStates; // null = surface data; non-null = cave data
  private final int knownCount;     // cached count of known columns

  /** One canonical constructor. All factories funnel through here. */
  private ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths, int[] waterTints,
      boolean[] isLeaf, boolean[] known, byte[] fieldStates, int knownCount) {
    this.baseColors = baseColors;
    this.heights = heights;
    this.waterDepths = waterDepths;
    this.waterTints = waterTints;
    this.isLeaf = isLeaf;
    this.known = known;
    this.fieldStates = fieldStates;
    this.knownCount = knownCount;
  }

  // -- Factories --

  /** Surface scan: every column is known terrain, no field-state machinery. */
  public static ChunkColorData surface(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf) {
    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf,
        ALL_KNOWN, null, PIXELS);
  }

  /**
   * Cave scan with explicit per-column known mask and field states. The known
   * count is computed from the mask.
   */
  public static ChunkColorData cave(
      int[] baseColors, int[] heights, int[] waterDepths, int[] waterTints,
      boolean[] isLeaf, boolean[] known, byte[] fieldStates) {
    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf,
        known, fieldStates, countTrue(known));
  }

  /**
   * Cave merge result. Caller already counted the new known columns during
   * the merge loop, so we skip the second pass.
   */
  private static ChunkColorData caveWithKnownCount(
      int[] baseColors, int[] heights, int[] waterDepths, int[] waterTints,
      boolean[] isLeaf, boolean[] known, byte[] fieldStates, int knownCount) {
    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf,
        known, fieldStates, knownCount);
  }

  /**
   * Codec entry point for a surface chunk. The known array is freshly
   * deserialized but in practice should be all-true; we keep it as-is.
   */
  static ChunkColorData fromCodecSurface(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known) {
    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf,
        known, null, countTrue(known));
  }

  /** Codec entry point for a cave chunk. Field states default from the known mask. */
  static ChunkColorData fromCodecCave(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known) {
    return new ChunkColorData(
        baseColors, heights, waterDepths, waterTints, isLeaf,
        known, defaultCaveFieldStates(known), countTrue(known));
  }

  // -- Codec accessors. Read-only — callers must not mutate the returned arrays. --

  int[] baseColorsForCodec() { return baseColors; }
  int[] heightsForCodec() { return heights; }
  int[] waterDepthsForCodec() { return waterDepths; }
  int[] waterTintsForCodec() { return waterTints; }
  boolean[] isLeafArrayForCodec() { return isLeaf; }
  boolean[] knownArrayForCodec() { return known; }

  // -- Public accessors --

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

  /** Returns cave field state flags for this column. */
  public byte getFieldState(int localX, int localZ) {
    return fieldStateAt(localX * SIZE + localZ);
  }

  /** Returns true if this cave column is currently unknown/unconfirmed. */
  public boolean isUnknown(int localX, int localZ) {
    return CaveFieldState.has(getFieldState(localX, localZ), CaveFieldState.UNKNOWN);
  }

  /** Flat-index field state read. Public for diagnostics in a sibling package. */
  public byte fieldStateAtIndex(int index) {
    return fieldStateAt(index);
  }

  /** Returns true if this data was scanned in cave mode. */
  public boolean isCaveData() {
    return fieldStates != null;
  }

  /** Returns the cached count of columns with known terrain data. */
  public int knownCount() {
    return knownCount;
  }

  // -- Merge operations --

  /**
   * Merges two cave-mode chunks additively. Known columns in the fresh scan
   * overwrite old data (most up-to-date). Unknown columns preserve old known
   * data because the current flood has no authoritative answer there. Boundary
   * columns clear old known data because a completed flood proved that column
   * is not part of the current reachable cave slice.
   */
  public static ChunkColorData mergeCave(ChunkColorData old, ChunkColorData fresh) {
    if (old.knownCount == 0) return fresh;

    int[] bc = new int[PIXELS];
    int[] ht = new int[PIXELS];
    int[] wd = new int[PIXELS];
    int[] wt = new int[PIXELS];
    boolean[] lf = new boolean[PIXELS];
    boolean[] kn = new boolean[PIXELS];
    byte[] st = new byte[PIXELS];
    int newKnown = 0;

    for (int i = 0; i < PIXELS; i++) {
      byte freshState = fresh.fieldStateAt(i);
      if (fresh.known[i]) {
        copyColumn(fresh, i, bc, ht, wd, wt, lf, i);
        kn[i] = true;
        st[i] = freshState;
        newKnown++;
      } else if (CaveFieldState.has(freshState, CaveFieldState.BOUNDARY)) {
        st[i] = freshState;
      } else if (old.known[i]) {
        copyColumn(old, i, bc, ht, wd, wt, lf, i);
        kn[i] = true;
        st[i] = old.fieldStateAt(i);
        newKnown++;
      } else {
        st[i] = mergeUnknownState(old.fieldStateAt(i), freshState);
      }
    }

    return caveWithKnownCount(bc, ht, wd, wt, lf, kn, st, newKnown);
  }

  /**
   * Fills unknown columns in the primary layer with known data from the
   * filler. Returns the primary unchanged (no allocation) if no gaps exist
   * or if the primary is already fully known. Used by the renderer to
   * composite multiple cave layers so that known terrain from any layer
   * is never hidden behind black walls.
   */
  public static ChunkColorData fillGaps(ChunkColorData primary, ChunkColorData filler) {
    if (primary.knownCount == PIXELS) return primary;

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
    byte[] st = new byte[PIXELS];
    int newKnown = 0;

    for (int i = 0; i < PIXELS; i++) {
      if (primary.known[i]) {
        copyColumn(primary, i, bc, ht, wd, wt, lf, i);
        kn[i] = true;
        st[i] = primary.fieldStateAt(i);
        newKnown++;
      } else if (filler.known[i] && canFillFrom(primary.fieldStateAt(i))) {
        copyColumn(filler, i, bc, ht, wd, wt, lf, i);
        kn[i] = true;
        st[i] = filler.fieldStateAt(i);
        newKnown++;
      } else {
        st[i] = mergeUnknownState(primary.fieldStateAt(i), filler.fieldStateAt(i));
      }
    }

    return caveWithKnownCount(bc, ht, wd, wt, lf, kn, st, newKnown);
  }

  // -- Internal helpers --

  /** Copies one column from src into the merge output arrays. */
  private static void copyColumn(
      ChunkColorData src, int srcIdx,
      int[] bc, int[] ht, int[] wd, int[] wt, boolean[] lf, int dstIdx) {
    bc[dstIdx] = src.baseColors[srcIdx];
    ht[dstIdx] = src.heights[srcIdx];
    wd[dstIdx] = src.waterDepths[srcIdx];
    wt[dstIdx] = src.waterTints[srcIdx];
    lf[dstIdx] = src.isLeaf[srcIdx];
  }

  private static boolean[] filledTrue() {
    boolean[] arr = new boolean[PIXELS];
    Arrays.fill(arr, true);
    return arr;
  }

  private static int countTrue(boolean[] arr) {
    int n = 0;
    for (boolean b : arr) { if (b) n++; }
    return n;
  }

  private static byte[] defaultCaveFieldStates(boolean[] known) {
    byte[] states = new byte[PIXELS];
    for (int i = 0; i < PIXELS; i++) {
      states[i] = known[i] ? CaveFieldState.REACHABLE : CaveFieldState.UNKNOWN;
    }
    return states;
  }

  private byte fieldStateAt(int index) {
    if (fieldStates == null) {
      return known[index] ? CaveFieldState.REACHABLE : CaveFieldState.UNKNOWN;
    }
    return fieldStates[index];
  }

  private static byte mergeUnknownState(byte a, byte b) {
    if (CaveFieldState.has(a, CaveFieldState.UNKNOWN)
        || CaveFieldState.has(b, CaveFieldState.UNKNOWN)) {
      return CaveFieldState.UNKNOWN;
    }
    if (CaveFieldState.has(a, CaveFieldState.BOUNDARY)
        || CaveFieldState.has(b, CaveFieldState.BOUNDARY)) {
      return CaveFieldState.BOUNDARY;
    }
    return 0;
  }

  private static boolean canFillFrom(byte primaryState) {
    return !CaveFieldState.has(primaryState, CaveFieldState.BOUNDARY);
  }
}
