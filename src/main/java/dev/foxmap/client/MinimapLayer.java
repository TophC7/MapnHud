package dev.foxmap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.foxmap.FoxMapMod;
import dev.foxmap.client.FoxMapConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Registers and renders the minimap as a NeoForge GUI layer.
 *
 * <p>The map is drawn as a configurable rectangle. The terrain texture
 * is rotated around its center so the player's facing direction is always "up"
 * (unless north-lock is enabled).
 *
 * <p>Features:
 * <ul>
 *   <li>Smooth scrolling via sub-block quad offset
 *   <li>Nearby player dots with UUID-derived colors and distance fading
 *   <li>Scissor-clipped rotation so corners never show
 *   <li>Configurable size, position, opacity, and aspect ratio
 * </ul>
 */
@EventBusSubscriber(modid = FoxMapMod.MOD_ID, value = Dist.CLIENT)
public class MinimapLayer {

  /** Padding from screen edge. */
  private static final int MARGIN = 8;

  private static final MinimapRenderer renderer = new MinimapRenderer();

  private static final ResourceLocation LAYER_ID =
      ResourceLocation.fromNamespaceAndPath(FoxMapMod.MOD_ID, "minimap");

  @SubscribeEvent
  public static void registerLayer(RegisterGuiLayersEvent event) {
    event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID, MinimapLayer::render);
    FoxMapMod.LOG.info("Registered minimap GUI layer");
  }

  private static void render(GuiGraphics graphics, DeltaTracker delta) {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (player == null || mc.level == null) return;

    if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;

    // All config values cached per-tick by MinimapKeybinds, no config tree traversal here
    int configSize = MinimapKeybinds.getDisplaySize();
    float aspectRatio = MinimapKeybinds.getAspectRatio();
    int displayW = configSize;
    int displayH = Math.round(configSize / aspectRatio);
    int texSize = displayW;
    int scale = MinimapKeybinds.getScale();
    float pixelScale = 1.0f / scale;

    // Interpolated position for smooth scrolling
    float partialTick = delta.getGameTimeDeltaPartialTick(false);
    double playerX = player.xOld + (player.getX() - player.xOld) * partialTick;
    double playerZ = player.zOld + (player.getZ() - player.zOld) * partialTick;

    ChunkColorCache cache = ChunkCacheEventHandler.getCache();
    boolean cacheUpdated = ChunkCacheEventHandler.consumeDirty();
    ResourceLocation texId = renderer.update(playerX, playerZ, cache, cacheUpdated, scale, texSize);
    if (texId == null) return;

    int screenW = graphics.guiWidth();
    int screenH = graphics.guiHeight();

    FoxMapConfig.ScreenCorner corner = MinimapKeybinds.getPosition();
    int mapX, mapY;
    switch (corner) {
      case TOP_LEFT -> { mapX = MARGIN; mapY = MARGIN; }
      case BOTTOM_LEFT -> { mapX = MARGIN; mapY = screenH - displayH - MARGIN; }
      case BOTTOM_RIGHT -> { mapX = screenW - displayW - MARGIN; mapY = screenH - displayH - MARGIN; }
      default -> { mapX = screenW - displayW - MARGIN; mapY = MARGIN; } // TOP_RIGHT
    }
    int centerX = mapX + displayW / 2;
    int centerY = mapY + displayH / 2;

    float rotation;
    if (MinimapKeybinds.isNorthLocked()) {
      rotation = 0.0f;
    } else {
      float yaw = player.getYRot();
      rotation = yaw + 180.0f;
    }

    // Sub-block fractional offset for smooth scrolling
    float fractX = (float) (playerX - Math.floor(playerX));
    float fractZ = (float) (playerZ - Math.floor(playerZ));
    float offsetX = -fractX * pixelScale;
    float offsetZ = -fractZ * pixelScale;

    // -- Scissor + background --
    graphics.enableScissor(mapX, mapY, mapX + displayW, mapY + displayH);
    graphics.fill(mapX, mapY, mapX + displayW, mapY + displayH, 0xAA000000);

    // -- Rotated terrain texture --
    graphics.pose().pushPose();
    graphics.pose().translate(centerX, centerY, 0);
    graphics.pose().mulPose(Axis.ZP.rotationDegrees(-rotation));

    // Scale by sqrt(2) so rotated square always fills the display
    float rotScale = 1.42f;
    graphics.pose().scale(rotScale, rotScale, 1.0f);

    // Draw the quad as a square (texSize x texSize) so it fills the display
    // rectangle at any rotation angle. The scissor clips to the actual frame shape.
    graphics.pose().translate(
        offsetX - texSize / 2.0f,
        offsetZ - texSize / 2.0f,
        0);

    float opacity = MinimapKeybinds.getOpacity();
    RenderSystem.setShaderTexture(0, texId);
    RenderSystem.setShader(GameRenderer::getPositionTexShader);
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, opacity);
    RenderSystem.enableBlend();

    Matrix4f matrix = graphics.pose().last().pose();
    BufferBuilder buf = Tesselator.getInstance().begin(
        VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
    buf.addVertex(matrix, 0, texSize, 0).setUv(0, 1);
    buf.addVertex(matrix, texSize, texSize, 0).setUv(1, 1);
    buf.addVertex(matrix, texSize, 0, 0).setUv(1, 0);
    buf.addVertex(matrix, 0, 0, 0).setUv(0, 0);
    BufferUploader.drawWithShader(buf.buildOrThrow());

    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    RenderSystem.disableBlend();

    // -- Player dots (inside the rotated pose stack so they rotate with the map) --
    int halfBlocks = (texSize / 2) * scale;
    renderPlayerDots(graphics, mc, player, playerX, playerZ, displayW, displayH, pixelScale, halfBlocks);

    graphics.pose().popPose();
    graphics.disableScissor();

    // -- Border --
    int borderColor = 0xFF222222;
    graphics.fill(mapX - 1, mapY - 1, mapX + displayW + 1, mapY, borderColor);
    graphics.fill(mapX - 1, mapY + displayH, mapX + displayW + 1,
        mapY + displayH + 1, borderColor);
    graphics.fill(mapX - 1, mapY, mapX, mapY + displayH, borderColor);
    graphics.fill(mapX + displayW, mapY, mapX + displayW + 1,
        mapY + displayH, borderColor);

    // -- Player indicator (fixed at center, not rotated) --
    graphics.fill(centerX - 2, centerY - 2, centerX + 2, centerY + 2, 0xFFFFFFFF);
    graphics.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 1, 0xFF333333);
  }

  /**
   * Renders other players as colored dots on the minimap.
   *
   * <p>Called inside the rotated pose stack so dots are positioned in world-relative
   * coordinates so they rotate with the terrain automatically.
   *
   * <p>Players beyond the map edge are clamped to the border at their correct
   * bearing angle. Dot opacity fades with distance.
   */
  private static void renderPlayerDots(
      GuiGraphics graphics, Minecraft mc, LocalPlayer localPlayer,
      double playerX, double playerZ, int displayW, int displayH,
      float pixelScale, int halfBlocks) {

    float halfW = displayW / 2.0f;
    float halfH = displayH / 2.0f;

    for (AbstractClientPlayer other : mc.level.players()) {
      if (other == localPlayer) continue;

      double dx = other.getX() - playerX;
      double dz = other.getZ() - playerZ;
      double dist = Math.sqrt(dx * dx + dz * dz);

      // Clamp to map edge if beyond visible area
      boolean clamped = false;
      if (dist > halfBlocks) {
        double angle = Math.atan2(dz, dx);
        dx = Math.cos(angle) * (halfBlocks - 2);
        dz = Math.sin(angle) * (halfBlocks - 2);
        clamped = true;
      }

      // Convert world offset to pixel coordinates on the display quad
      float px = (float) (dx * pixelScale) + halfW;
      float pz = (float) (dz * pixelScale) + halfH;

      // Alpha fading: vivid close, fades to 100 at max distance
      int alpha;
      if (clamped) {
        alpha = 100;
      } else {
        alpha = 255 - (int) ((dist / halfBlocks) * 155);
        alpha = Math.max(100, Math.min(255, alpha));
      }

      int dotColor = uuidColor(other.getUUID().hashCode(), alpha);

      // 3x3 filled rect
      int x1 = Math.round(px) - 1;
      int z1 = Math.round(pz) - 1;
      graphics.fill(x1, z1, x1 + 3, z1 + 3, dotColor);
    }
  }

  /**
   * Derives a deterministic, visually distinct color from a UUID hash.
   * Uses HSB with fixed saturation and brightness for readability.
   */
  private static int uuidColor(int uuidHash, int alpha) {
    float hue = (uuidHash & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    int rgb = Mth.hsvToRgb(hue, 0.7f, 1.0f);
    return (alpha << 24) | (rgb & 0x00FFFFFF);
  }
}
