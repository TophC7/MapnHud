package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Shows the current biome name, formatted from the resource location.
 */
public final class BiomeProvider implements InfoProvider {

  @Override public String id() { return "biome"; }
  @Override public String displayName() { return "Biome"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null || mc.level == null) return null;
    BlockPos pos = mc.player.blockPosition();
    Holder<Biome> biome = mc.level.getBiome(pos);

    return biome.unwrapKey()
        .map(key -> FormatUtil.titleCase(key.location()))
        .orElse("Unknown");
  }
}
