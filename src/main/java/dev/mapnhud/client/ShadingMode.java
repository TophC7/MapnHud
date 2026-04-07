package dev.mapnhud.client;

/**
 * Selects the minimap shading algorithm.
 *
 * <ul>
 *   <li>{@link #CLASSIC} -- sharp pixel-art edges, vanilla map style.
 *   <li>{@link #HEIGHTFIELD} -- smooth directional lighting with terrain relief.
 * </ul>
 */
public enum ShadingMode {
  CLASSIC("Classic"),
  HEIGHTFIELD("Heightfield");

  private final String label;

  ShadingMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
