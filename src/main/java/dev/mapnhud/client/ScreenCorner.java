package dev.mapnhud.client;

/** Which corner of the screen the minimap anchors to. */
public enum ScreenCorner {
  TOP_LEFT("Top Left"),
  TOP_RIGHT("Top Right"),
  BOTTOM_LEFT("Bottom Left"),
  BOTTOM_RIGHT("Bottom Right");

  private final String label;

  ScreenCorner(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
