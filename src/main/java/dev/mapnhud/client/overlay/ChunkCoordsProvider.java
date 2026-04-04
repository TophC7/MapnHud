package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;

/**
 * Shows the player's chunk coordinates.
 */
public class ChunkCoordsProvider implements InfoProvider {

  @Override public String id() { return "chunk"; }
  @Override public String displayName() { return "Chunk Coords"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null) return null;
    int cx = mc.player.getBlockX() >> 4;
    int cz = mc.player.getBlockZ() >> 4;
    return "Chunk " + cx + " / " + cz;
  }
}
