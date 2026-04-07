package dev.mapnhud.client.map.cave;

/**
 * Vertical portal band at a chunk border column.
 *
 * @param direction border side this portal exits toward
 * @param edgeOffset offset [0..15] along the border side
 * @param minY inclusive minimum standable Y in this band
 * @param maxY inclusive maximum standable Y in this band
 */
public record CavePortalBand(
    CaveDirection direction,
    int edgeOffset,
    int minY,
    int maxY
) {
  public boolean connectsTo(CavePortalBand other, int stepDelta) {
    return direction.opposite() == other.direction
        && edgeOffset == other.edgeOffset
        && maxY + stepDelta >= other.minY
        && other.maxY + stepDelta >= minY;
  }
}
