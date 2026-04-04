package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;

/**
 * Shows the player's horizontal speed in blocks per second,
 * smoothed with a rolling average over 20 ticks.
 */
public class SpeedProvider implements InfoProvider {

  private static final int WINDOW = 20;
  private final double[] samples = new double[WINDOW];
  private int sampleIndex = 0;
  private boolean filled = false;

  @Override public String id() { return "speed"; }
  @Override public String displayName() { return "Speed"; }

  @Override
  public String getText(Minecraft mc) {
    if (mc.player == null) return null;
    double dx = mc.player.getX() - mc.player.xOld;
    double dz = mc.player.getZ() - mc.player.zOld;
    double tickSpeed = Math.sqrt(dx * dx + dz * dz) * 20;

    samples[sampleIndex] = tickSpeed;
    sampleIndex = (sampleIndex + 1) % WINDOW;
    if (sampleIndex == 0) filled = true;

    int count = filled ? WINDOW : sampleIndex;
    if (count == 0) return "0.0 b/s";
    double sum = 0;
    for (int i = 0; i < count; i++) sum += samples[i];
    double avg = sum / count;

    return String.format("%.1f b/s", avg);
  }
}
