package dev.mapnhud.client.map.cave;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * Immutable per-chunk cave navigation snapshot.
 *
 * <p>Stores standable voxel poses, local transition masks, and extracted
 * border portal bands.
 */
public final class CaveChunkNavSnapshot {

  private static final CavePortalBand[] EMPTY_PORTALS = new CavePortalBand[0];

  private final int chunkX;
  private final int chunkZ;
  private final int minY;
  private final int maxY;
  private final int ySpan;
  private final int standableCount;

  private final BitSet standable;
  private final byte[] transitionMasks;
  private final CavePortalBand[] portals;
  private final CavePortalBand[][] portalLookup;

  CaveChunkNavSnapshot(
      int chunkX,
      int chunkZ,
      int minY,
      int maxY,
      BitSet standable,
      byte[] transitionMasks,
      CavePortalBand[] portals,
      int standableCount) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minY = minY;
    this.maxY = maxY;
    this.ySpan = Math.max(0, maxY - minY + 1);
    this.standable = (BitSet) standable.clone();
    this.transitionMasks = transitionMasks.clone();
    this.portals = portals.clone();
    this.standableCount = standableCount;
    this.portalLookup = buildPortalLookup(this.portals);
  }

  private static CavePortalBand[][] buildPortalLookup(CavePortalBand[] portals) {
    @SuppressWarnings("unchecked")
    ArrayList<CavePortalBand>[] builders = new ArrayList[CaveDirection.values().length * 16];
    for (CavePortalBand portal : portals) {
      int slot = portal.direction().ordinal() * 16 + portal.edgeOffset();
      ArrayList<CavePortalBand> list = builders[slot];
      if (list == null) {
        list = new ArrayList<>(2);
        builders[slot] = list;
      }
      list.add(portal);
    }

    CavePortalBand[][] lookup = new CavePortalBand[builders.length][];
    for (int i = 0; i < builders.length; i++) {
      List<CavePortalBand> list = builders[i];
      lookup[i] = list == null ? EMPTY_PORTALS : list.toArray(CavePortalBand[]::new);
    }
    return lookup;
  }

  public int chunkX() {
    return chunkX;
  }

  public int chunkZ() {
    return chunkZ;
  }

  public long chunkKey() {
    return ChunkPos.asLong(chunkX, chunkZ);
  }

  public int minY() {
    return minY;
  }

  public int maxY() {
    return maxY;
  }

  public int ySpan() {
    return ySpan;
  }

  public int standableCount() {
    return standableCount;
  }

  public boolean isStandableLocal(int localX, int y, int localZ) {
    int idx = poseIndex(localX, y, localZ);
    return idx >= 0 && standable.get(idx);
  }

  public byte transitionMaskLocal(int localX, int y, int localZ) {
    int idx = poseIndex(localX, y, localZ);
    return idx >= 0 ? transitionMasks[idx] : 0;
  }

  public CavePortalBand[] portals(CaveDirection direction, int edgeOffset) {
    return portalLookup[direction.ordinal() * 16 + edgeOffset];
  }

  int poseIndex(int localX, int y, int localZ) {
    if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return -1;
    if (y < minY || y > maxY) return -1;
    int layer = y - minY;
    return layer * 256 + columnIndex(localX, localZ);
  }

  static int columnIndex(int localX, int localZ) {
    return localX * 16 + localZ;
  }
}
