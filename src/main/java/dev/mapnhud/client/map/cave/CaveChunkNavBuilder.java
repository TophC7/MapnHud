package dev.mapnhud.client.map.cave;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Builds immutable cave navigation snapshots for loaded chunks. */
public final class CaveChunkNavBuilder {

  private CaveChunkNavBuilder() {}

  public static final int DEFAULT_STEP_DELTA = 2;

  public static CaveChunkNavSnapshot build(Level level, LevelChunk chunk) {
    return build(level, chunk, DEFAULT_STEP_DELTA);
  }

  public static CaveChunkNavSnapshot build(Level level, LevelChunk chunk, int stepDelta) {
    int chunkX = chunk.getPos().x;
    int chunkZ = chunk.getPos().z;
    int minY = level.getMinBuildHeight() + 1;
    int maxY = level.getMaxBuildHeight() - 2;
    if (maxY < minY) {
      return new CaveChunkNavSnapshot(
          chunkX, chunkZ, minY, minY - 1, new BitSet(), new byte[0],
          new CavePortalBand[0], 0);
    }

    int ySpan = maxY - minY + 1;
    int poseCount = ySpan * 256;
    BitSet standable = new BitSet(poseCount);
    byte[] transitionMasks = new byte[poseCount];

    int standableCount = 0;
    int baseX = chunk.getPos().getMinBlockX();
    int baseZ = chunk.getPos().getMinBlockZ();
    BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

    for (int localX = 0; localX < 16; localX++) {
      int worldX = baseX + localX;
      for (int localZ = 0; localZ < 16; localZ++) {
        int worldZ = baseZ + localZ;
        for (int y = minY; y <= maxY; y++) {
          if (CaveStandability.isStandablePose(level, mutable, worldX, y, worldZ)) {
            int idx = poseIndex(localX, y, localZ, minY);
            standable.set(idx);
            standableCount++;
          }
        }
      }
    }

    for (int idx = standable.nextSetBit(0); idx >= 0; idx = standable.nextSetBit(idx + 1)) {
      int layer = idx / 256;
      int y = minY + layer;
      int col = idx % 256;
      int localX = col / 16;
      int localZ = col % 16;

      byte mask = 0;
      for (CaveDirection direction : CaveDirection.values()) {
        int nx = localX + direction.dx();
        int nz = localZ + direction.dz();
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;

        boolean connected = false;
        for (int dy = -stepDelta; dy <= stepDelta; dy++) {
          int ny = y + dy;
          if (ny < minY || ny > maxY) continue;
          int nIdx = poseIndex(nx, ny, nz, minY);
          if (standable.get(nIdx)) {
            connected = true;
            break;
          }
        }
        if (connected) {
          mask |= direction.transitionBit();
        }
      }
      transitionMasks[idx] = mask;
    }

    CavePortalBand[] portals = buildPortals(standable, minY, maxY);
    return new CaveChunkNavSnapshot(
        chunkX, chunkZ, minY, maxY, standable, transitionMasks, portals, standableCount);
  }

  private static CavePortalBand[] buildPortals(BitSet standable, int minY, int maxY) {
    List<CavePortalBand> portals = new ArrayList<>();
    for (CaveDirection direction : CaveDirection.values()) {
      for (int edgeOffset = 0; edgeOffset < 16; edgeOffset++) {
        int localX = direction.edgeLocalX(edgeOffset);
        int localZ = direction.edgeLocalZ(edgeOffset);

        int runMin = Integer.MIN_VALUE;
        int runMax = Integer.MIN_VALUE;
        for (int y = minY; y <= maxY; y++) {
          int idx = poseIndex(localX, y, localZ, minY);
          if (standable.get(idx)) {
            if (runMin == Integer.MIN_VALUE) {
              runMin = y;
              runMax = y;
            } else if (y == runMax + 1) {
              runMax = y;
            } else {
              portals.add(new CavePortalBand(direction, edgeOffset, runMin, runMax));
              runMin = y;
              runMax = y;
            }
          } else if (runMin != Integer.MIN_VALUE) {
            portals.add(new CavePortalBand(direction, edgeOffset, runMin, runMax));
            runMin = Integer.MIN_VALUE;
            runMax = Integer.MIN_VALUE;
          }
        }

        if (runMin != Integer.MIN_VALUE) {
          portals.add(new CavePortalBand(direction, edgeOffset, runMin, runMax));
        }
      }
    }
    return portals.toArray(CavePortalBand[]::new);
  }

  private static int poseIndex(int localX, int y, int localZ, int minY) {
    return (y - minY) * 256 + CaveChunkNavSnapshot.columnIndex(localX, localZ);
  }
}
