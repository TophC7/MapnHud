package dev.mapnhud.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Non-interactive display widget that renders a live preview of the minimap
 * texture inside the config screen. Reads the texture from MinimapLayer's
 * existing renderer, which continues updating under the config screen.
 *
 * <p>The preview is centered horizontally within the widget's width and
 * renders as a bordered square. Changes to rendering config values are
 * visible within 1-2 ticks as the renderer picks up the new config.
 */
public class MinimapPreviewWidget extends AbstractWidget {

  private static final int PREVIEW_SIZE = 200;
  private static final int BORDER_COLOR = 0xFF222222;
  private static final int BG_COLOR = 0xFF1A1A1A;
  private static final int LABEL_COLOR = 0xFF808080;

  public MinimapPreviewWidget(int fullWidth) {
    super(0, 0, fullWidth, PREVIEW_SIZE + 18, Component.literal("Preview"));
    this.active = false;
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    ResourceLocation texId = MinimapLayer.getTextureId();

    // Center the preview square within the full width
    int px = getX() + (width - PREVIEW_SIZE) / 2;
    int py = getY() + 14;

    // Label
    graphics.drawString(
        net.minecraft.client.Minecraft.getInstance().font,
        "Preview", px, getY() + 2, LABEL_COLOR, false);

    // Border and background
    graphics.fill(px - 1, py - 1, px + PREVIEW_SIZE + 1, py + PREVIEW_SIZE + 1, BORDER_COLOR);
    graphics.fill(px, py, px + PREVIEW_SIZE, py + PREVIEW_SIZE, BG_COLOR);

    if (texId == null) return;

    // Draw the full minimap texture scaled to the preview size.
    // UV 0-1 maps the entire texture; the GPU handles scaling.
    graphics.blit(texId, px, py, 0, 0,
        PREVIEW_SIZE, PREVIEW_SIZE, PREVIEW_SIZE, PREVIEW_SIZE);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    // Display-only widget, no narration needed
  }

  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    return false; // Non-interactive
  }
}
