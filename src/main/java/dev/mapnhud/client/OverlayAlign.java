package dev.mapnhud.client;

/** Text alignment within the info overlay. */
public enum OverlayAlign {
  LEFT("Left"),
  CENTER("Center"),
  RIGHT("Right");

  private final String label;

  OverlayAlign(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
