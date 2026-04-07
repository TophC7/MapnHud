package dev.mapnhud.client.map.cave;

/** Bit flags for cave field states. */
public final class CaveFieldState {

  private CaveFieldState() {}

  public static final byte UNKNOWN = 1 << 0;
  public static final byte REACHABLE = 1 << 1;
  public static final byte BOUNDARY = 1 << 2;
  public static final byte VISIBLE = 1 << 3;

  public static boolean has(byte state, byte flag) {
    return (state & flag) != 0;
  }

  public static byte with(byte state, byte flag) {
    return (byte) (state | flag);
  }
}
