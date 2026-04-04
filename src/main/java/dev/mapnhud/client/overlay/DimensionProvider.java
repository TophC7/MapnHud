package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Shows the current dimension name.
 */
public final class DimensionProvider implements InfoProvider {

  @Override public String id() { return "dimension"; }
  @Override public String displayName() { return "Dimension"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.level == null) return null;
    ResourceLocation dim = mc.level.dimension().location();

    // Vanilla dimensions get clean names
    return switch (dim.toString()) {
      case "minecraft:overworld" -> "Overworld";
      case "minecraft:the_nether" -> "The Nether";
      case "minecraft:the_end" -> "The End";
      default -> FormatUtil.titleCase(dim);
    };
  }
}
