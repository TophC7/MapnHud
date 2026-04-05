package dev.mapnhud.client.overlay;

import dev.mapnhud.client.CaveModeTracker;
import dev.mapnhud.client.map.CaveFloodFill;
import dev.mapnhud.client.map.ChunkCacheEventHandler;
import net.minecraft.client.Minecraft;

/**
 * Shows cave flood fill performance stats when cave mode is active.
 * Returns null when on the surface so the line disappears automatically.
 */
public final class CaveStatsProvider implements InfoProvider {

  @Override public String id() { return "cave_stats"; }
  @Override public String displayName() { return "Cave Stats"; }

  @Override
  public String getText(Minecraft mc) {
    if (!CaveModeTracker.isCaveMode()) return null;

    CaveFloodFill.Result flood = ChunkCacheEventHandler.getCache().getFloodResult();
    return String.format("Cave: %d cols, %.1fms", flood.columnsReachable(), flood.elapsedMs());
  }
}
