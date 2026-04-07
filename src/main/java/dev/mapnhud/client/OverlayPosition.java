package dev.mapnhud.client;

/** Where the info overlay is placed relative to the map. */
public enum OverlayPosition {
  BELOW_MAP("Below Map"),
  BESIDE_MAP("Beside Map"),
  OPPOSITE_Y("Opposite Side (Vertical)"),
  OPPOSITE_X("Opposite Side (Horizontal)");

  private final String label;

  OverlayPosition(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
