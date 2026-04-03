package dev.mapnhud.client;

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
public class ChunkCacheEventHandler {

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
    CACHE.evict(event.getChunk().getPos());
  }

  @SubscribeEvent
  public static void onLevelLoad(LevelEvent.Load event) {
    if (!(event.getLevel() instanceof ClientLevel)) return;
    CACHE.clearAll();
    // Force texture color re-extraction on next tick (handles resource pack changes)
    BlockColorExtractor.reset();
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;

    if (!BlockColorExtractor.isInitialized()) {
      BlockColorExtractor.rebuild();
    }

    cacheUpdatedThisTick = CACHE.tick(mc.level, mc.player.blockPosition());
  }
}
