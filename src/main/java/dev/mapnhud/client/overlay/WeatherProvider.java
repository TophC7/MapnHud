package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;

/**
 * Shows the current weather. Returns null when clear (nothing to show).
 */
public class WeatherProvider implements InfoProvider {

  @Override public String id() { return "weather"; }
  @Override public String displayName() { return "Weather"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.level == null) return null;
    if (mc.level.isThundering()) return "Thunder";
    if (mc.level.isRaining()) return "Rain";
    return null;
  }
}
