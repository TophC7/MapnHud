package dev.mapnhud.client.overlay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all available info providers in their default order.
 */
public final class InfoProviders {

  private static final Map<String, InfoProvider> BY_ID = new LinkedHashMap<>();

  static {
    register(new CoordinatesProvider());
    register(new BiomeProvider());
    register(new TimeProvider());
    register(new WeatherProvider());
    register(new LightLevelProvider());
    register(new DimensionProvider());
    register(new CompassProvider());
    register(new FpsProvider());
    register(new SpeedProvider());
    register(new ChunkCoordsProvider());
    register(new CaveStatsProvider());
  }

  private static void register(InfoProvider provider) {
    BY_ID.put(provider.id(), provider);
  }

  /** All providers in default order. */
  public static List<InfoProvider> all() {
    return List.copyOf(BY_ID.values());
  }

  /** All provider IDs in default order. */
  public static List<String> defaultOrder() {
    return List.copyOf(BY_ID.keySet());
  }

  /** Lookup by ID, or null if unknown. */
  public static InfoProvider get(String id) {
    return BY_ID.get(id);
  }

  private InfoProviders() {}
}
