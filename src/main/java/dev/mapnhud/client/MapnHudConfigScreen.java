package dev.mapnhud.client;

import dev.mapnhud.client.MapnHudConfig.ScreenCorner;
import dev.mapnhud.client.MapnHudConfig.TooltipPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Configuration screen for Fox Map, accessible from the NeoForge mod list.
 *
 * <p>Uses vanilla's tab navigation system (same as Create World screen) to
 * separate Map and HUD settings. All changes write to {@link MapnHudConfig}
 * immediately and take effect without restart.
 */
public class MapnHudConfigScreen extends Screen {

  private static final int COL_WIDTH = 200;
  private static final int COL_GAP = 12;
  private static final int TAB_NAV_HEIGHT = 24;
  private static final int BOTTOM_MARGIN = 36;
  private static final int SECTION_TITLE_COLOR = 0xFFFFFF;

  private final Screen parent;
  private TabNavigationBar tabNavBar;
  private TabManager tabManager;

  public MapnHudConfigScreen(Screen parent) {
    super(Component.literal("Fox Map Settings"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    this.tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    ConfigTab mapTab = buildMapTab();
    ConfigTab hudTab = buildHudTab();

    this.tabNavBar = TabNavigationBar.builder(this.tabManager, this.width)
        .addTabs(mapTab, hudTab)
        .build();
    this.addRenderableWidget(this.tabNavBar);
    this.tabNavBar.arrangeElements();

    addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
        .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());

    ScreenRectangle contentArea = new ScreenRectangle(
        0, TAB_NAV_HEIGHT,
        this.width, this.height - TAB_NAV_HEIGHT - BOTTOM_MARGIN);
    this.tabManager.setTabArea(contentArea);

    this.tabNavBar.selectTab(0, false);
  }

  private ConfigTab buildMapTab() {
    ConfigTab tab = new ConfigTab(Component.literal("Map"));
    GridLayout grid = tab.grid();
    grid.rowSpacing(4).columnSpacing(COL_GAP);
    var font = Minecraft.getInstance().font;
    int row = 0;

    grid.addChild(sectionTitle("Display", font), row, 0);
    grid.addChild(sectionTitle("Frame", font), row, 1);
    row++;

    // Left: Zoom
    grid.addChild(CycleButton.<Integer>builder(v -> Component.literal(v + "x"))
        .withValues(java.util.Arrays.stream(MapnHudConfig.ZOOM_SCALES).boxed().toList())
        .withInitialValue(MapnHudConfig.MAP_ZOOM.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Zoom"),
            (btn, val) -> MapnHudConfig.MAP_ZOOM.set(val)), row, 0);

    // Right: Size slider (80-320)
    grid.addChild(intSlider(COL_WIDTH, "Size", "px",
        80, 320, 10, MapnHudConfig.MAP_SIZE.get(),
        val -> MapnHudConfig.MAP_SIZE.set(val)), row, 1);
    row++;

    // Left: North Lock
    grid.addChild(CycleButton.onOffBuilder(MapnHudConfig.MAP_NORTH_LOCK.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Lock North Up"),
            (btn, val) -> MapnHudConfig.MAP_NORTH_LOCK.set(val)), row, 0);

    // Right: Shape (fixed ratio presets)
    grid.addChild(CycleButton.<ShapePreset>builder(p -> Component.literal(p.label))
        .withValues(ShapePreset.ALL)
        .withInitialValue(ShapePreset.closest(MapnHudConfig.MAP_SHAPE.get()))
        .create(0, 0, COL_WIDTH, 20, Component.literal("Shape"),
            (btn, val) -> MapnHudConfig.MAP_SHAPE.set(val.ratio)), row, 1);
    row++;

    // Left: Position
    grid.addChild(CycleButton.<ScreenCorner>builder(v -> Component.literal(v.label()))
        .withValues(ScreenCorner.values())
        .withInitialValue(MapnHudConfig.MAP_POSITION.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Position"),
            (btn, val) -> MapnHudConfig.MAP_POSITION.set(val)), row, 0);

    // Right: Opacity slider (30%-100%)
    grid.addChild(percentSlider(COL_WIDTH, "Opacity",
        0.3, 1.0, MapnHudConfig.MAP_OPACITY.get(),
        val -> MapnHudConfig.MAP_OPACITY.set(val)), row, 1);
    row++;

    grid.addChild(SpacerElement.height(6), row, 0, 1, 2);
    row++;

    grid.addChild(sectionTitle("Overlay", font), row, 0, 1, 2);
    row++;

    grid.addChild(Button.builder(Component.literal("Info Overlay..."), btn -> {
      // TODO: open info overlay sub-screen
    }).width(COL_WIDTH).build(), row, 0);

    return tab;
  }

  private ConfigTab buildHudTab() {
    ConfigTab tab = new ConfigTab(Component.literal("HUD"));
    GridLayout grid = tab.grid();
    grid.rowSpacing(4).columnSpacing(COL_GAP);
    var font = Minecraft.getInstance().font;
    int row = 0;

    grid.addChild(sectionTitle("Block Tooltip", font), row, 0);
    grid.addChild(sectionTitle("Tab List", font), row, 1);
    row++;

    grid.addChild(CycleButton.onOffBuilder(MapnHudConfig.BLOCK_TOOLTIP_ENABLED.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Block Tooltip"),
            (btn, val) -> MapnHudConfig.BLOCK_TOOLTIP_ENABLED.set(val)), row, 0);

    grid.addChild(CycleButton.onOffBuilder(MapnHudConfig.TAB_DISTANCES_ENABLED.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Tab Distances"),
            (btn, val) -> MapnHudConfig.TAB_DISTANCES_ENABLED.set(val)), row, 1);
    row++;

    grid.addChild(CycleButton.<TooltipPosition>builder(v -> Component.literal(v.label()))
        .withValues(TooltipPosition.values())
        .withInitialValue(MapnHudConfig.BLOCK_TOOLTIP_POSITION.get())
        .create(0, 0, COL_WIDTH, 20, Component.literal("Tooltip Position"),
            (btn, val) -> MapnHudConfig.BLOCK_TOOLTIP_POSITION.set(val)), row, 0);

    return tab;
  }

  private static StringWidget sectionTitle(String text, net.minecraft.client.gui.Font font) {
    return new StringWidget(COL_WIDTH, 14, Component.literal(text), font)
        .setColor(SECTION_TITLE_COLOR)
        .alignLeft();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    this.tabNavBar.render(graphics, mouseX, mouseY, partialTick);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (this.tabNavBar.keyPressed(keyCode)) return true;
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void onClose() {
    MapnHudConfig.SPEC.save();
    this.minecraft.setScreen(parent);
  }

  // -- Slider widget --

  /**
   * Generic range slider. Maps the 0-1 slider value to a double range,
   * formats the display via a caller-provided function, and reports
   * changes via a callback.
   */
  private static class ConfigSlider extends AbstractSliderButton {
    private final double min;
    private final double max;
    private final java.util.function.DoubleFunction<String> formatter;
    private final java.util.function.DoubleConsumer onChange;

    ConfigSlider(int width, double min, double max, double initial,
                 java.util.function.DoubleFunction<String> formatter,
                 java.util.function.DoubleConsumer onChange) {
      super(0, 0, width, 20, Component.empty(),
          (initial - min) / (max - min));
      this.min = min;
      this.max = max;
      this.formatter = formatter;
      this.onChange = onChange;
      updateMessage();
    }

    private double realValue() {
      return Mth.lerp(this.value, min, max);
    }

    @Override
    protected void updateMessage() {
      setMessage(Component.literal(formatter.apply(realValue())));
    }

    @Override
    protected void applyValue() {
      onChange.accept(realValue());
    }
  }

  /** Creates an integer slider that snaps to a step size. */
  private static ConfigSlider intSlider(int width, String label, String suffix,
                                        int min, int max, int step, int initial,
                                        java.util.function.IntConsumer onChange) {
    return new ConfigSlider(width, min, max, initial,
        v -> {
          int snapped = Math.round((float) Math.round(v) / step) * step;
          return label + ": " + snapped + suffix;
        },
        v -> onChange.accept(Math.round((float) Math.round(v) / step) * step));
  }

  /** Creates a percentage slider. */
  private static ConfigSlider percentSlider(int width, String label,
                                            double min, double max, double initial,
                                            java.util.function.DoubleConsumer onChange) {
    return new ConfigSlider(width, min, max, initial,
        v -> label + ": " + Math.round(v * 100) + "%",
        onChange);
  }

  private record ShapePreset(String label, double ratio) {
    static final ShapePreset[] ALL = {
        new ShapePreset("1:1", 1.0),
        new ShapePreset("4:3", 4.0 / 3),
        new ShapePreset("3:2", 3.0 / 2),
        new ShapePreset("16:9", 16.0 / 9),
        new ShapePreset("2:1", 2.0),
    };

    static ShapePreset closest(double val) {
      ShapePreset best = ALL[0];
      for (ShapePreset p : ALL) {
        if (Math.abs(p.ratio - val) < Math.abs(best.ratio - val)) best = p;
      }
      return best;
    }
  }

  /**
   * Subclass of GridLayoutTab that exposes the layout for adding widgets.
   * The vanilla class marks the field as protected, so we need this to build
   * tabs from outside the tabs package.
   */
  private static class ConfigTab extends GridLayoutTab {
    ConfigTab(Component title) {
      super(title);
    }

    GridLayout grid() {
      return this.layout;
    }
  }
}
