package dev.mapnhud.client.map;

import dev.mapnhud.MapnHudMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Routes NeoForge chunk and level events to {@link ChunkColorCache}.
 */
@EventBusSubscriber(modid = MapnHudMod.MOD_ID, value = Dist.CLIENT)
public final class ChunkCacheEventHandler {

  private static final ChunkColorCache CACHE = new ChunkColorCache();
  private static boolean cacheUpdatedThisTick = false;

  public static ChunkColorCache getCache() {
    return CACHE;
  }

  /** Returns true if the cache was updated since the last call. Resets the flag. */
  public static boolean consumeDirty() {
    boolean was = cacheUpdatedThisTick;
    cacheUpdatedThisTick = false;
    return was;
  }

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (!event.getLevel().isClientSide()) return;
    CACHE.enqueueChunk(event.getChunk());
  }

  @SubscribeEvent
  public static void onChunkUnload(ChunkEvent.Unload event) {
    if (!event.getLevel().isClientSide()) return;
    CACHE.onChunkUnload(event.getChunk().getPos().x, event.getChunk().getPos().z);
  }

  @SubscribeEvent
  public static void onLevelLoad(LevelEvent.Load event) {
    if (!(event.getLevel() instanceof ClientLevel clientLevel)) return;
    // Save previous dimension's data before clearing
    CACHE.saveAllToDisk();
    CACHE.clearAll();
    // Force texture color re-extraction on next tick (handles resource pack changes)
    BlockColorExtractor.reset();
    // Load persisted chunks for the new dimension
    CACHE.loadFromDisk(clientLevel);
  }

  @SubscribeEvent
  public static void onLevelUnload(LevelEvent.Unload event) {
    if (!(event.getLevel() instanceof ClientLevel)) return;
    CACHE.saveAllToDisk();
    // Flush pending writes and release the IO thread.
    // The executor recreates itself on next dimension load.
    CACHE.shutdown();
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;

    if (!BlockColorExtractor.isInitialized()) {
      BlockColorExtractor.rebuild();
    }

    boolean caveMode = dev.mapnhud.client.CaveModeTracker.isCaveMode();
    cacheUpdatedThisTick = CACHE.tick(mc.level, mc.player.blockPosition(), caveMode,
        dev.mapnhud.client.MinimapConfigCache.getCaveScanRadiusChunks(),
        dev.mapnhud.client.MinimapConfigCache.getCaveFloodRadiusBlocks());
  }
}
