package dev.mapnhud.client.overlay;

import dev.mapnhud.client.CaveModeTracker;
import dev.mapnhud.client.map.CaveFloodFill;
import dev.mapnhud.client.map.ChunkColorCache;
import dev.mapnhud.client.map.ChunkCacheEventHandler;
import dev.mapnhud.client.map.cave.CaveCacheDiagnostics;
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

    ChunkColorCache cache = ChunkCacheEventHandler.getCache();
    CaveFloodFill.Result flood = cache.getFloodResult();
    if (!CaveCacheDiagnostics.ENABLED) {
      return String.format("Cave: %d cols, %.1fms", flood.columnsReachable(), flood.elapsedMs());
    }

    CaveCacheDiagnostics.DebugSnapshot debug = cache.getDebugSnapshot();
    return String.format(
        "Cave: cols=%d %.1fms q=%d/%d/%d/%d near(miss=%d dead=%d) drop=%d requeue=%d",
        flood.columnsReachable(),
        flood.elapsedMs(),
        debug.priorityQueueSize(),
        debug.scanQueueSize(),
        debug.refloodQueueSize(),
        debug.navQueueSize(),
        debug.nearMissingChunks(),
        debug.nearSuspectDeadChunks(),
        debug.totalDroppedScans(),
        debug.totalRequeues());
  }
}
