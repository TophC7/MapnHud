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
  private final byte[] fieldStates; // cave field flags (unknown/reachable/boundary/visible)
  private final boolean cave;       // true = cave-mode scan produced this data
  private final int knownCount;     // cached count of known columns

  /** Surface constructor: all columns are known terrain. */
  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf) {
    this(baseColors, heights, waterDepths, waterTints, isLeaf, ALL_KNOWN, false, null, PIXELS);
  }

  /** Cave constructor with per-column known mask. */
  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known) {
    this(baseColors, heights, waterDepths, waterTints, isLeaf, known, true,
        defaultCaveFieldStates(known), countTrue(known));
  }

  /** Cave constructor with explicit per-column field states. */
  public ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known, byte[] fieldStates) {
    this(baseColors, heights, waterDepths, waterTints, isLeaf, known, true, fieldStates, countTrue(known));
  }

  /**
   * Most-explicit constructor. Used by merge paths that already know
   * {@code knownCount} so the constructor can skip the second pass over
   * the known mask.
   */
  ChunkColorData(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known, boolean cave,
      byte[] fieldStates, int knownCount) {
    this.baseColors = baseColors;
    this.heights = heights;
    this.waterDepths = waterDepths;
    this.waterTints = waterTints;
    this.isLeaf = isLeaf;
    this.known = known;
    this.fieldStates = cave
        ? (fieldStates != null ? fieldStates : defaultCaveFieldStates(known))
        : null;
    this.cave = cave;
    this.knownCount = knownCount;
  }

  /** Codec entry point: builds an instance from deserialized arrays. */
  static ChunkColorData fromCodec(
      int[] baseColors, int[] heights, int[] waterDepths,
      int[] waterTints, boolean[] isLeaf, boolean[] known, boolean cave) {
    return new ChunkColorData(baseColors, heights, waterDepths, waterTints, isLeaf, known, cave,
        cave ? defaultCaveFieldStates(known) : null, countTrue(known));
  }

  // -- Codec accessors (package-private, for ChunkColorDataCodec) --

  int[] baseColors() { return baseColors; }
  int[] heights() { return heights; }
  int[] waterDepths() { return waterDepths; }
  int[] waterTints() { return waterTints; }
  boolean[] isLeafArray() { return isLeaf; }
  boolean[] knownArray() { return known; }

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
    int idx = localX * SIZE + localZ;
    if (!cave || fieldStates == null) {
      return known[idx] ? CaveFieldState.REACHABLE : CaveFieldState.UNKNOWN;
    }
    return fieldStates[idx];
  }

  /** Returns true if this cave column is currently unknown/unconfirmed. */
  public boolean isUnknown(int localX, int localZ) {
    return CaveFieldState.has(getFieldState(localX, localZ), CaveFieldState.UNKNOWN);
  }

  /** Counts columns where the given field-state flag is set. */
  public int countFieldFlag(byte flag) {
    int count = 0;
    for (int i = 0; i < PIXELS; i++) {
      if (CaveFieldState.has(fieldStateAt(i), flag)) count++;
    }
    return count;
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
   * overwrite old data (most up-to-date). Unknown columns preserve old known
   * data because the current flood has no authoritative answer there. Boundary
   * columns clear old known data because a completed flood proved that column
   * is not part of the current reachable cave slice.
   */
  public static ChunkColorData mergeCave(ChunkColorData old, ChunkColorData fresh) {
    // Fast path: nothing to preserve from old.
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
      if (fresh.known[i]) {
        bc[i] = fresh.baseColors[i];
        ht[i] = fresh.heights[i];
        wd[i] = fresh.waterDepths[i];
        wt[i] = fresh.waterTints[i];
        lf[i] = fresh.isLeaf[i];
        kn[i] = true;
        st[i] = fresh.fieldStateAt(i);
        newKnown++;
      } else if (CaveFieldState.has(fresh.fieldStateAt(i), CaveFieldState.BOUNDARY)) {
        kn[i] = false;
        st[i] = fresh.fieldStateAt(i);
      } else if (old.known[i]) {
        bc[i] = old.baseColors[i];
        ht[i] = old.heights[i];
        wd[i] = old.waterDepths[i];
        wt[i] = old.waterTints[i];
        lf[i] = old.isLeaf[i];
        kn[i] = true;
        st[i] = old.fieldStateAt(i);
        newKnown++;
      } else {
        st[i] = mergeUnknownState(old.fieldStateAt(i), fresh.fieldStateAt(i));
      }
    }

    return new ChunkColorData(bc, ht, wd, wt, lf, kn, true, st, newKnown);
  }

  /**
   * Fills unknown columns in the primary layer with known data from the
   * filler. Returns the primary unchanged (no allocation) if no gaps exist
   * or if the primary is already fully known. Used by the renderer to
   * composite multiple cave layers so that known terrain from any layer
   * is never hidden behind black walls.
   */
  public static ChunkColorData fillGaps(ChunkColorData primary, ChunkColorData filler) {
    // Fast path: primary already complete.
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
        bc[i] = primary.baseColors[i];
        ht[i] = primary.heights[i];
        wd[i] = primary.waterDepths[i];
        wt[i] = primary.waterTints[i];
        lf[i] = primary.isLeaf[i];
        kn[i] = true;
        st[i] = primary.fieldStateAt(i);
        newKnown++;
      } else if (filler.known[i] && canFillFrom(primary.fieldStateAt(i))) {
        bc[i] = filler.baseColors[i];
        ht[i] = filler.heights[i];
        wd[i] = filler.waterDepths[i];
        wt[i] = filler.waterTints[i];
        lf[i] = filler.isLeaf[i];
        kn[i] = true;
        st[i] = filler.fieldStateAt(i);
        newKnown++;
      } else {
        st[i] = mergeUnknownState(primary.fieldStateAt(i), filler.fieldStateAt(i));
      }
    }

    return new ChunkColorData(bc, ht, wd, wt, lf, kn, true, st, newKnown);
  }

  // -- Helpers --

  private static boolean[] allTrue() {
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
    if (!cave || fieldStates == null) {
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
