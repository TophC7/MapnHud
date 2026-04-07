package dev.mapnhud.client.map.cave;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Shared standable-pose checks for cave flood and nav builds. */
public final class CaveStandability {

  private CaveStandability() {}

  public static boolean isStandablePose(
      Level level, BlockPos.MutableBlockPos mutable, int x, int y, int z) {
    mutable.set(x, y, z);
    return isStandableAtY(level, mutable, y);
  }

  /** Assumes mutable X/Z already point at the target column. */
  public static boolean isStandableAtY(
      Level level, BlockPos.MutableBlockPos mutable, int y) {
    mutable.setY(y);
    BlockState feet = level.getBlockState(mutable);
    if (feet.blocksMotion()) return false;

    mutable.setY(y + 1);
    BlockState head = level.getBlockState(mutable);
    if (head.blocksMotion()) return false;

    mutable.setY(y - 1);
    BlockState support = level.getBlockState(mutable);
    return support.blocksMotion();
  }
}
