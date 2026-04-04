package dev.mapnhud.client.overlay;

import net.minecraft.resources.ResourceLocation;

public final class FormatUtil {
  private FormatUtil() {}

  /** Converts "minecraft:dark_forest" -> "Dark Forest". Handles empty parts safely. */
  public static String titleCase(ResourceLocation loc) {
    return titleCase(loc.getPath());
  }

  /** Converts "dark_forest" -> "Dark Forest". Splits on underscores and capitalizes. */
  public static String titleCase(String raw) {
    String[] parts = raw.split("_");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (part.isEmpty()) continue;
      if (!sb.isEmpty()) sb.append(' ');
      sb.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) sb.append(part.substring(1));
    }
    return sb.toString();
  }
}
