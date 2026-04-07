package dev.mapnhud.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Tracks whether the player is underground using WORLD_SURFACE heightmap
 * comparison with hysteresis to prevent flicker at surface level.
 *
 * <p>Enter cave mode when the player is 3+ blocks below the heightmap surface.
 * Exit when at or above the surface. Ticked once per client tick; the result
 * is read by the cache and renderer.
 */
public final class CaveModeTracker {

  private CaveModeTracker() {}

  /** Blocks below surface before entering cave mode. */
  private static final int CAVE_ENTER_DEPTH = 3;

  private static boolean caveMode = false;
  private static int playerY = 0;

  /**
   * Updates cave mode state based on the player's position relative to the
   * WORLD_SURFACE heightmap. Call once per client tick.
   */
  public static void tick(Minecraft mc) {
    if (mc.player == null || mc.level == null) return;

    BlockPos pos = mc.player.blockPosition();
    int blockY = pos.getY();
    playerY = blockY;

    boolean caveEnabled = SafeConfig.getBool(MapnHudConfig.CAVE_MODE_ENABLED, true);
    if (!caveEnabled) {
      caveMode = false;
      return;
    }

    int surfaceY = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());

    if (caveMode) {
      if (blockY >= surfaceY - 1) caveMode = false;
    } else {
      if (blockY < surfaceY - CAVE_ENTER_DEPTH) caveMode = true;
    }
  }

  public static boolean isCaveMode() { return caveMode; }
  public static int getPlayerY() { return playerY; }
}
