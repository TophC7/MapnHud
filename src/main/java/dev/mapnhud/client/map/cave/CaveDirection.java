package dev.mapnhud.client.map.cave;

/**
 * Cardinal directions for cave navigation and chunk-border portal extraction.
 */
public enum CaveDirection {
  EAST(1, 0, (byte) (1 << 0)),
  WEST(-1, 0, (byte) (1 << 1)),
  SOUTH(0, 1, (byte) (1 << 2)),
  NORTH(0, -1, (byte) (1 << 3));

  private final int dx;
  private final int dz;
  private final byte transitionBit;

  CaveDirection(int dx, int dz, byte transitionBit) {
    this.dx = dx;
    this.dz = dz;
    this.transitionBit = transitionBit;
  }

  public int dx() {
    return dx;
  }

  public int dz() {
    return dz;
  }

  public byte transitionBit() {
    return transitionBit;
  }

  public CaveDirection opposite() {
    return switch (this) {
      case EAST -> WEST;
      case WEST -> EAST;
      case SOUTH -> NORTH;
      case NORTH -> SOUTH;
    };
  }

  /** Returns edge-local X for this direction and edge offset [0..15]. */
  public int edgeLocalX(int edgeOffset) {
    return switch (this) {
      case EAST -> 15;
      case WEST -> 0;
      case NORTH, SOUTH -> edgeOffset;
    };
  }

  /** Returns edge-local Z for this direction and edge offset [0..15]. */
  public int edgeLocalZ(int edgeOffset) {
    return switch (this) {
      case SOUTH -> 15;
      case NORTH -> 0;
      case EAST, WEST -> edgeOffset;
    };
  }
}
