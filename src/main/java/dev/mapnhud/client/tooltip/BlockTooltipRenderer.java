package dev.mapnhud.client.tooltip;

import dev.mapnhud.MapnHudMod;
import dev.mapnhud.client.MapnHudConfig;
import dev.mapnhud.client.TooltipPosition;
import dev.mapnhud.client.overlay.FormatUtil;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import xyz.kwahson.core.config.SafeConfig;

/**
 * Renders a compact block/entity tooltip near the crosshair or at a fixed
 * screen position. Shows icon, name, mod name, and health hearts for mobs.
 *
 * <p>Follows the tick/render split: {@link #tick} reads config and resolves
 * the targeted block/entity once per tick (20/sec), while {@link #render}
 * draws the cached tooltip every frame with interpolated fade alpha.
 *
 * <p>Auto-disables when Jade or WTHIT is detected.
 */
@EventBusSubscriber(modid = MapnHudMod.MOD_ID, value = Dist.CLIENT)
public final class BlockTooltipRenderer {

  // -- Resolved target data (rebuilt only on target change) --

  /**
   * Immutable snapshot of what the tooltip should display. Built once when the
   * player starts looking at a new block/entity/fluid, then read every frame.
   */
  private record TooltipTarget(
      Component name,
      Component modName,
      ItemStack icon,
      boolean hasItemIcon,
      LivingEntity livingEntity
  ) {}

  // -- Pre-computed hearts state (rebuilt per tick, read per frame) --

  /**
   * Pre-formatted heart display data. Rebuilt every tick from the living
   * target's health so the render path does zero allocation.
   */
  private record HeartsCache(
      String fullHeartsStr,
      boolean halfHeart,
      boolean compact,
      String compactStr
  ) {
    static final HeartsCache NONE = new HeartsCache("", false, false, "");

    boolean visible() {
      return !fullHeartsStr.isEmpty() || halfHeart;
    }
  }

  // -- Constants --

  private static final ResourceLocation LAYER_ID =
      ResourceLocation.fromNamespaceAndPath(MapnHudMod.MOD_ID, "block_tooltip");

  // Timing
  private static final int STARE_DELAY_TICKS = 10;
  private static final float FADE_IN_SPEED = 0.25f;
  private static final float FADE_OUT_SPEED = 0.167f;

  // Layout
  private static final int BG_PADDING_H = 6;
  private static final int BG_PADDING_V = 4;
  private static final int ITEM_ICON_SIZE = 16;
  private static final int ENTITY_ICON_SIZE = 24;
  private static final int ICON_TEXT_GAP = 4;
  private static final int LINE_GAP = 1;
  private static final int CROSSHAIR_OFFSET_Y = 25;

  // Colors
  private static final int HEART_COLOR = 0xFF0000;
  private static final int HALF_HEART_COLOR = 0x880000;
  private static final int MOD_NAME_COLOR = 0x5555FF;

  // -- Mutable state --

  // Config cache (read per tick)
  private static boolean enabled = true;
  private static TooltipPosition position = TooltipPosition.TOP_CENTER;

  // Target identity (for same-target detection only)
  private static Block currentBlock;
  private static Entity currentEntity;
  private static Fluid currentFluid;

  // Resolved display data
  private static TooltipTarget target;
  private static float targetHealth = -1;
  private static float targetMaxHealth = -1;
  private static HeartsCache hearts = HeartsCache.NONE;

  // Stare + fade
  private static int stareTickCount;
  private static float fadeAlpha;
  private static float prevFadeAlpha;
  private static boolean fadeIn;

  // Compat
  private static boolean compatChecked;
  private static boolean suppressedByCompat;

  // Caches
  private static final Map<String, Component> modNameCache = new HashMap<>();
  private static int cachedHeartCharW = -1;
  private static boolean entityRenderWarned;

  private BlockTooltipRenderer() {}

  // -- Event wiring --

  @SubscribeEvent
  public static void registerLayer(RegisterGuiLayersEvent event) {
    event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER_ID,
        BlockTooltipRenderer::render);
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    tick(Minecraft.getInstance());
  }

  // -- Tick --

  private static void tick(Minecraft mc) {
    if (!compatChecked) {
      compatChecked = true;
      suppressedByCompat = ModList.get().isLoaded("jade")
          || ModList.get().isLoaded("wthit");
    }

    enabled = SafeConfig.getBool(MapnHudConfig.BLOCK_TOOLTIP_ENABLED, true);
    position = SafeConfig.getEnum(MapnHudConfig.BLOCK_TOOLTIP_POSITION, TooltipPosition.TOP_CENTER);

    if (!enabled || suppressedByCompat || mc.player == null || mc.level == null) {
      clearTarget();
      updateFade();
      return;
    }

    resolveTarget(mc);

    // Health updates every tick for living entities
    if (target != null && target.livingEntity != null && target.livingEntity.isAlive()) {
      targetHealth = target.livingEntity.getHealth();
      targetMaxHealth = target.livingEntity.getMaxHealth();
    }

    updateFade();
    hearts = refreshHeartsCache(mc);
  }

  // -- Render --

  private static void render(GuiGraphics graphics, DeltaTracker delta) {
    float alpha = computeAlpha(delta);
    if (alpha <= 0.01f) return;

    TooltipTarget t = target;
    if (t == null) return;

    Minecraft mc = Minecraft.getInstance();
    if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;

    Font font = mc.font;
    boolean hasIcon = t.hasItemIcon || t.livingEntity != null;
    int iconSize = t.livingEntity != null ? ENTITY_ICON_SIZE : ITEM_ICON_SIZE;
    int iconSpace = hasIcon ? iconSize + ICON_TEXT_GAP : 0;

    // Text line widths
    int nameWidth = font.width(t.name);
    int modWidth = t.modName != null ? font.width(t.modName) : 0;
    int textContentWidth = Math.max(nameWidth, modWidth);

    // Hearts width from tick-cached data
    HeartsCache h = hearts;
    int heartsWidth = 0;
    if (h.visible()) {
      if (h.compact) {
        heartsWidth = font.width(h.compactStr);
      } else {
        int heartW = heartCharWidth(font);
        heartsWidth = heartW * (h.fullHeartsStr.length() + (h.halfHeart ? 1 : 0));
      }
    }

    int textBlockWidth = Math.max(textContentWidth, heartsWidth);
    int contentWidth = textBlockWidth + iconSpace;
    int tooltipWidth = contentWidth + BG_PADDING_H * 2;

    int lineCount = 1;
    if (t.modName != null) lineCount++;
    if (h.visible()) lineCount++;
    int textBlockHeight = lineCount * font.lineHeight + (lineCount - 1) * LINE_GAP;
    int innerHeight = Math.max(hasIcon ? iconSize : 0, textBlockHeight);
    int tooltipHeight = innerHeight + BG_PADDING_V * 2;

    // Screen position
    int screenW = graphics.guiWidth();
    int screenH = graphics.guiHeight();
    int tooltipX, tooltipY;

    switch (position) {
      case TOP_CENTER -> {
        tooltipX = (screenW - tooltipWidth) / 2;
        tooltipY = 8;
      }
      case BOTTOM_CENTER -> {
        tooltipX = (screenW - tooltipWidth) / 2;
        tooltipY = screenH - 48 - tooltipHeight;
      }
      default -> {
        tooltipX = (screenW - tooltipWidth) / 2;
        tooltipY = screenH / 2 + CROSSHAIR_OFFSET_Y;
      }
    }

    renderTooltipFrame(graphics, tooltipX, tooltipY, tooltipWidth, tooltipHeight, alpha);

    int textAlpha = (int) (255 * alpha);

    // Icon
    int iconX = tooltipX + BG_PADDING_H;
    int iconY = tooltipY + BG_PADDING_V + (innerHeight - iconSize) / 2;
    if (hasIcon && alpha > 0.3f) {
      if (t.livingEntity != null) {
        renderEntityIcon(graphics, t.livingEntity, iconX, iconY, iconSize);
      } else if (t.hasItemIcon && !t.icon.isEmpty()) {
        graphics.renderItem(t.icon, iconX, iconY);
      }
    }

    // Text lines
    int textX = tooltipX + BG_PADDING_H + iconSpace;
    int textStartY = tooltipY + BG_PADDING_V + (innerHeight - textBlockHeight) / 2;
    int lineY = textStartY;

    graphics.drawString(font, t.name, textX, lineY, withAlpha(0xFFFFFF, textAlpha), true);
    lineY += font.lineHeight + LINE_GAP;

    if (t.modName != null) {
      graphics.drawString(font, t.modName, textX, lineY,
          withAlpha(MOD_NAME_COLOR, textAlpha), true);
      lineY += font.lineHeight + LINE_GAP;
    }

    if (h.visible()) {
      renderHearts(graphics, font, h, textX, lineY, textAlpha);
    }
  }

  // -- Target resolution --

  private static void resolveTarget(Minecraft mc) {
    HitResult hit = mc.hitResult;

    if (hit instanceof EntityHitResult entityHit) {
      Entity entity = entityHit.getEntity();
      if (!entity.isRemoved()) {
        resolveEntity(entity);
        return;
      }
    }

    if (hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK) {
      BlockState state = mc.level.getBlockState(((BlockHitResult) hit).getBlockPos());
      if (!state.isAir()) {
        resolveBlock(state.getBlock());
        return;
      }
    }

    // Fluid fallback: only when primary hit missed (not when looking at solid block)
    if (mc.player != null && (hit == null || hit.getType() == HitResult.Type.MISS)) {
      double reach = mc.player.blockInteractionRange();
      HitResult fluidHit = mc.player.pick(reach, 1f, true);
      if (fluidHit instanceof BlockHitResult
          && fluidHit.getType() == HitResult.Type.BLOCK) {
        FluidState fluid = mc.level.getFluidState(((BlockHitResult) fluidHit).getBlockPos());
        if (!fluid.isEmpty()) {
          resolveFluid(fluid);
          return;
        }
      }
    }

    clearTarget();
  }

  private static void resolveBlock(Block block) {
    if (block == currentBlock && currentEntity == null && currentFluid == null) {
      stareTickCount++;
      applyStareDelay();
      return;
    }

    currentEntity = null;
    currentFluid = null;
    currentBlock = block;

    ItemStack icon = new ItemStack(block.asItem());
    boolean hasIcon = !icon.isEmpty() && icon.getItem() != Items.AIR;
    target = new TooltipTarget(
        block.getName(),
        lookupModName(BuiltInRegistries.BLOCK.getKey(block).getNamespace()),
        icon, hasIcon, null);
    targetHealth = -1;
    targetMaxHealth = -1;

    resetStare();
  }

  private static void resolveEntity(Entity entity) {
    if (entity == currentEntity) {
      stareTickCount++;
      applyStareDelay();
      return;
    }

    currentBlock = null;
    currentFluid = null;
    currentEntity = entity;

    Component name = entity.getDisplayName();
    Component modName = lookupModName(
        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());

    if (entity instanceof ItemEntity itemEntity) {
      ItemStack icon = itemEntity.getItem().copy();
      target = new TooltipTarget(name, modName, icon, !icon.isEmpty(), null);
      targetHealth = -1;
      targetMaxHealth = -1;
    } else if (entity instanceof LivingEntity living) {
      target = new TooltipTarget(name, modName, ItemStack.EMPTY, false, living);
      targetHealth = living.getHealth();
      targetMaxHealth = living.getMaxHealth();
    } else {
      target = new TooltipTarget(name, modName, ItemStack.EMPTY, false, null);
      targetHealth = -1;
      targetMaxHealth = -1;
    }

    resetStare();
  }

  private static void resolveFluid(FluidState fluid) {
    Fluid fluidType = fluid.getType();
    if (fluidType == currentFluid && currentBlock == null && currentEntity == null) {
      stareTickCount++;
      applyStareDelay();
      return;
    }

    currentBlock = null;
    currentEntity = null;
    currentFluid = fluidType;

    ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluidType);
    target = new TooltipTarget(
        Component.literal(FormatUtil.titleCase(fluidId)),
        lookupModName(fluidId.getNamespace()),
        ItemStack.EMPTY, false, null);
    targetHealth = -1;
    targetMaxHealth = -1;

    resetStare();
  }

  private static void clearTarget() {
    currentBlock = null;
    currentEntity = null;
    currentFluid = null;
    target = null;
    targetHealth = -1;
    targetMaxHealth = -1;
    hearts = HeartsCache.NONE;
    fadeIn = false;
    stareTickCount = 0;
  }

  // -- Stare delay --

  private static void resetStare() {
    stareTickCount = 0;
    // Skip stare delay if tooltip is still partially visible (smooth target switching)
    fadeIn = fadeAlpha > 0;
  }

  private static void applyStareDelay() {
    if (fadeAlpha > 0 || stareTickCount >= STARE_DELAY_TICKS) {
      fadeIn = true;
    }
  }

  // -- Mod name (cached per namespace, never changes at runtime) --

  private static Component lookupModName(String namespace) {
    return modNameCache.computeIfAbsent(namespace, ns -> {
      String displayName = ModList.get().getModContainerById(ns)
          .map(c -> c.getModInfo().getDisplayName())
          .orElse(FormatUtil.titleCase(ns));
      return Component.literal(displayName).withStyle(Style.EMPTY.withItalic(true));
    });
  }

  // -- Fade --

  private static void updateFade() {
    prevFadeAlpha = fadeAlpha;
    if (fadeIn) {
      fadeAlpha = Math.min(1f, fadeAlpha + FADE_IN_SPEED);
    } else {
      fadeAlpha = Math.max(0f, fadeAlpha - FADE_OUT_SPEED);
    }
  }

  private static float computeAlpha(DeltaTracker delta) {
    if (prevFadeAlpha == fadeAlpha) return fadeAlpha;
    float pt = delta.getGameTimeDeltaPartialTick(false);
    return prevFadeAlpha + (fadeAlpha - prevFadeAlpha) * pt;
  }

  // -- Hearts --

  private static HeartsCache refreshHeartsCache(Minecraft mc) {
    if (targetHealth <= 0 || targetMaxHealth <= 0) {
      return HeartsCache.NONE;
    }

    int halfHeartUnits = (int) Math.ceil(targetHealth);
    int full = halfHeartUnits / 2;
    boolean half = halfHeartUnits % 2 == 1;
    if (full <= 0 && !half) return HeartsCache.NONE;

    String fullStr = full > 0 ? "\u2764".repeat(full) : "";

    // Check if hearts fit within a reasonable width
    TooltipTarget t = target;
    Font font = mc.font;
    int heartW = heartCharWidth(font);
    int nameWidth = t != null ? font.width(t.name) : 0;
    int modWidth = t != null && t.modName != null ? font.width(t.modName) : 0;
    int textContentWidth = Math.max(nameWidth, modWidth);
    int idealWidth = heartW * (full + (half ? 1 : 0));

    if (idealWidth > textContentWidth && textContentWidth > 0) {
      String compactStr = "\u2764 " + (int) targetHealth + "/" + (int) targetMaxHealth;
      return new HeartsCache(fullStr, half, true, compactStr);
    }

    return new HeartsCache(fullStr, half, false, "");
  }

  private static int heartCharWidth(Font font) {
    if (cachedHeartCharW < 0) {
      cachedHeartCharW = font.width("\u2764");
    }
    return cachedHeartCharW;
  }

  private static void renderHearts(GuiGraphics graphics, Font font, HeartsCache h,
                                    int x, int y, int textAlpha) {
    int fullColor = withAlpha(HEART_COLOR, textAlpha);

    if (h.compact) {
      graphics.drawString(font, h.compactStr, x, y, fullColor, true);
      return;
    }

    int hx = x;
    if (!h.fullHeartsStr.isEmpty()) {
      graphics.drawString(font, h.fullHeartsStr, hx, y, fullColor, true);
      hx += font.width(h.fullHeartsStr);
    }

    if (h.halfHeart) {
      graphics.drawString(font, "\u2764", hx, y, withAlpha(HALF_HEART_COLOR, textAlpha), true);
    }
  }

  // -- Tooltip frame (vanilla colors with alpha support) --

  private static void renderTooltipFrame(GuiGraphics g, int x, int y,
                                          int w, int h, float alpha) {
    int bg = withAlpha(0x100010, (int) (0xF0 * alpha));
    int bt = withAlpha(0x5000FF, (int) (0x50 * alpha));
    int bb = withAlpha(0x28007F, (int) (0x50 * alpha));

    g.fill(x + 1, y, x + w - 1, y + 1, bg);
    g.fill(x, y + 1, x + w, y + h - 1, bg);
    g.fill(x + 1, y + h - 1, x + w - 1, y + h, bg);

    g.fillGradient(x + 1, y + 1, x + 2, y + h - 1, bt, bb);
    g.fillGradient(x + w - 2, y + 1, x + w - 1, y + h - 1, bt, bb);
    g.fill(x + 1, y + 1, x + w - 1, y + 2, bt);
    g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, bb);
  }

  private static int withAlpha(int rgb, int alpha) {
    return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
  }

  // -- Entity icon --

  private static void renderEntityIcon(GuiGraphics graphics, LivingEntity entity,
                                        int x, int y, int size) {
    if (entity.isRemoved()) return;

    try {
      int scale = Math.max(8, (int) (14.0f / entity.getBbHeight()));
      float yOffset = entity.getBbHeight() * 0.35f;

      InventoryScreen.renderEntityInInventoryFollowsMouse(
          graphics,
          x, y, x + size, y + size,
          scale,
          yOffset,
          (float) (x + size * 5), (float) (y + size / 2),
          entity
      );
    } catch (Exception e) {
      if (!entityRenderWarned) {
        entityRenderWarned = true;
        MapnHudMod.LOG.warn("Tooltip entity render failed for {}: {}",
            entity.getType(), e.getMessage());
      }
    }
  }
}
