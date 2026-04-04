package dev.mapnhud.client.overlay;

import dev.mapnhud.client.MapnHudConfig;
import dev.mapnhud.client.MapnHudConfig.OverlayAlign;
import dev.mapnhud.client.MapnHudConfig.OverlayPosition;
import dev.mapnhud.client.MapnHudConfig.ScreenCorner;
import dev.mapnhud.client.MinimapKeybinds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Renders enabled info overlay lines relative to the minimap.
 * All style settings (position, alignment, scale, color, background)
 * are read from config once per tick and cached.
 */
public final class InfoOverlayRenderer {

  private static final int LINE_HEIGHT = 11;
  private static final int PADDING = 2;
  private static final int GAP = 3;
  private static final int MARGIN = 8;
  private static final int BG_COLOR = 0x80000000;

  // Per-tick cache
  private static final List<String> cachedLines = new ArrayList<>();
  private static List<String> cachedOrder = List.of();
  private static Set<String> cachedEnabled = Set.of();
  private static boolean masterEnabled = true;
  private static OverlayPosition position = OverlayPosition.BELOW_MAP;
  private static OverlayAlign alignment = OverlayAlign.LEFT;
  private static float textScale = 1.0f;
  private static int textColor = 0xFFFFFF;
  private static boolean showBackground = true;

  private InfoOverlayRenderer() {}

  /** Called once per tick to refresh text lines and style from config. */
  public static void tick(Minecraft mc) {
    cachedLines.clear();

    masterEnabled = SafeConfig.getBool(MapnHudConfig.OVERLAY_MASTER_TOGGLE, true);
    position = SafeConfig.getEnum(MapnHudConfig.OVERLAY_POSITION, OverlayPosition.BELOW_MAP);
    alignment = SafeConfig.getEnum(MapnHudConfig.OVERLAY_ALIGNMENT, OverlayAlign.LEFT);
    textScale = SafeConfig.getFloat(MapnHudConfig.OVERLAY_TEXT_SCALE, 1.0f);
    textColor = SafeConfig.getInt(MapnHudConfig.OVERLAY_TEXT_COLOR, 0xFFFFFF);
    showBackground = SafeConfig.getBool(MapnHudConfig.OVERLAY_BACKGROUND, true);

    if (!masterEnabled) return;

    List<? extends String> order = SafeConfig.get(MapnHudConfig.OVERLAY_ORDER, List.of());
    List<? extends String> enabled = SafeConfig.get(MapnHudConfig.OVERLAY_ENABLED, List.of());

    if (!order.equals(cachedOrder) || !enabled.equals(cachedEnabled)) {
      cachedOrder = List.copyOf(order);
      cachedEnabled = new HashSet<>(enabled);
    }

    for (String id : cachedOrder) {
      if (!cachedEnabled.contains(id)) continue;
      InfoProvider provider = InfoProviders.get(id);
      if (provider == null) continue;
      String text = provider.getText(mc);
      if (text != null) {
        cachedLines.add(text);
      }
    }
  }

  /** Renders the overlay. Called from the GUI layer after the minimap. */
  public static void render(GuiGraphics graphics, int mapX, int mapY,
                            int mapW, int mapH, int screenW, int screenH) {
    if (!masterEnabled || cachedLines.isEmpty()) return;

    Font font = Minecraft.getInstance().font;
    int scaledLineH = Math.round(LINE_HEIGHT * textScale);

    // Measure lines at scale
    int maxWidth = 0;
    for (String line : cachedLines) {
      maxWidth = Math.max(maxWidth, Math.round(font.width(line) * textScale));
    }

    int blockWidth = maxWidth + PADDING * 2;
    int blockHeight = cachedLines.size() * scaledLineH + PADDING;

    // Compute position based on overlay position setting and map corner
    ScreenCorner corner = MinimapKeybinds.getPosition();
    boolean mapOnTop = corner == ScreenCorner.TOP_LEFT || corner == ScreenCorner.TOP_RIGHT;
    boolean mapOnRight = corner == ScreenCorner.TOP_RIGHT || corner == ScreenCorner.BOTTOM_RIGHT;
    int x, y;

    switch (position) {
      case BESIDE_MAP -> {
        if (mapOnRight) {
          x = mapX - blockWidth - GAP;
        } else {
          x = mapX + mapW + GAP;
        }
        y = mapY;
      }
      case OPPOSITE_Y -> {
        x = mapOnRight ? mapX + mapW - blockWidth : mapX;
        y = mapOnTop ? screenH - blockHeight - MARGIN : MARGIN;
      }
      case OPPOSITE_X -> {
        x = mapOnRight ? MARGIN : screenW - blockWidth - MARGIN;
        y = mapOnTop ? MARGIN : screenH - blockHeight - MARGIN;
      }
      default -> { // BELOW_MAP
        x = mapOnRight ? mapX + mapW - blockWidth : mapX;
        y = mapOnTop ? mapY + mapH + GAP : mapY - blockHeight - GAP;
      }
    }

    // Background
    if (showBackground) {
      graphics.fill(x, y, x + blockWidth, y + blockHeight, BG_COLOR);
    }

    // Text lines with scale
    int argbColor = 0xFF000000 | textColor;
    float textY = y + PADDING;

    graphics.pose().pushPose();
    graphics.pose().scale(textScale, textScale, 1.0f);

    float invScale = 1.0f / textScale;
    for (String line : cachedLines) {
      int lineWidth = font.width(line);

      float textX = switch (alignment) {
        case CENTER -> (x + blockWidth / 2.0f) * invScale - lineWidth / 2.0f;
        case RIGHT -> (x + blockWidth - PADDING) * invScale - lineWidth;
        default -> (x + PADDING) * invScale;
      };

      graphics.drawString(font, line, (int) textX, (int) (textY * invScale), argbColor, true);
      textY += scaledLineH;
    }

    graphics.pose().popPose();
  }
}
