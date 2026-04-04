package dev.mapnhud.client.overlay;

import dev.mapnhud.client.MapnHudConfig;
import net.minecraft.client.Minecraft;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Shows the current time. Can display in-game time or real time,
 * in 12h or 24h format.
 */
public final class TimeProvider implements InfoProvider {

  @Override public String id() { return "time"; }
  @Override public String displayName() { return "Time"; }
  @Override public boolean hasSettings() { return true; }

  @Override
  public String getText(Minecraft mc) {
    if (SafeConfig.getBool(MapnHudConfig.OVERLAY_TIME_REAL, false)) {
      return formatRealTime();
    }
    if (mc.level == null) return null;
    return formatGameTime(mc.level.getDayTime());
  }

  private String formatRealTime() {
    var now = java.time.LocalTime.now();
    if (SafeConfig.getBool(MapnHudConfig.OVERLAY_TIME_24H, false)) {
      return String.format("%02d:%02d", now.getHour(), now.getMinute());
    }
    int hour = now.getHour() % 12;
    if (hour == 0) hour = 12;
    String ampm = now.getHour() < 12 ? "AM" : "PM";
    return String.format("%d:%02d %s", hour, now.getMinute(), ampm);
  }

  private String formatGameTime(long dayTime) {
    // Minecraft day starts at 6:00 AM (tick 0 = 6:00)
    long adjustedTime = (dayTime + 6000) % 24000;
    int hours = (int) (adjustedTime / 1000);
    int minutes = (int) ((adjustedTime % 1000) * 60 / 1000);

    if (SafeConfig.getBool(MapnHudConfig.OVERLAY_TIME_24H, false)) {
      return String.format("%02d:%02d", hours, minutes);
    }
    int displayHour = hours % 12;
    if (displayHour == 0) displayHour = 12;
    String ampm = hours < 12 ? "AM" : "PM";
    return String.format("%d:%02d %s", displayHour, minutes, ampm);
  }
}
