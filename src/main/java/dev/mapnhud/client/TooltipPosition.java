package dev.mapnhud.client;

/** Where the block tooltip bar appears on screen. */
public enum TooltipPosition {
  CROSSHAIR("Near Crosshair"),
  TOP_CENTER("Top Center"),
  BOTTOM_CENTER("Bottom Center");

  private final String label;

  TooltipPosition(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
