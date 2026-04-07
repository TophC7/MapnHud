package dev.mapnhud.client.map;

/**
 * Byte-format codec for {@link ChunkColorData}, isolated from the data class
 * so the wire format and the in-memory shape can evolve independently.
 *
 * <p>Format v4 layout (all integers big-endian):
 * <pre>
 *   version       : 1B
 *   mode          : 1B   (0=surface, 1=cave)
 *   baseColors    : 256 * 4B
 *   heights       : 256 * 4B
 *   waterDepths   : 256 * 4B
 *   waterTints    : 256 * 4B
 *   isLeaf bitset : 32B
 *   known bitset  : 32B
 * </pre>
 * Total: {@value #SERIAL_BYTES} bytes per layer.
 *
 * <p>Cave field states are not persisted. They are recalculated at runtime
 * from the live flood result on the next scan.
 */
public final class ChunkColorDataCodec {

  private ChunkColorDataCodec() {}

  /** Format version. v4 is the current cave field payload format. */
  private static final byte SERIAL_VERSION = 4;

  private static final byte MODE_SURFACE = 0;
  private static final byte MODE_CAVE = 1;

  /**
   * Serialized size: version(1) + mode(1) + 4 int arrays(4*256*4=4096)
   * + isLeaf bitset(32) + known bitset(32) = 4162 bytes.
   */
  public static final int SERIAL_BYTES =
      1 + 1 + (ChunkColorData.PIXELS * 4 * 4) + (ChunkColorData.PIXELS / 8) + (ChunkColorData.PIXELS / 8);

  /** Serializes a chunk's column data to a fixed-size byte array. */
  public static byte[] toBytes(ChunkColorData data) {
    byte[] buf = new byte[SERIAL_BYTES];
    int pos = 0;

    buf[pos++] = SERIAL_VERSION;
    buf[pos++] = data.isCaveData() ? MODE_CAVE : MODE_SURFACE;
    pos = writeInts(buf, pos, data.baseColorsForCodec());
    pos = writeInts(buf, pos, data.heightsForCodec());
    pos = writeInts(buf, pos, data.waterDepthsForCodec());
    pos = writeInts(buf, pos, data.waterTintsForCodec());
    packBooleans(buf, pos, data.isLeafArrayForCodec());
    pos += ChunkColorData.PIXELS / 8;
    packBooleans(buf, pos, data.knownArrayForCodec());
    return buf;
  }

  /** Deserializes a v4 byte array. Throws on unsupported versions or short buffers. */
  public static ChunkColorData fromBytes(byte[] buf) {
    if (buf.length < 2) {
      throw new IllegalArgumentException("Buffer too short: " + buf.length);
    }

    int pos = 0;
    int version = buf[pos++] & 0xFF;
    if (version != SERIAL_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported serial version: " + version + " (only v4 supported)");
    }

    boolean isCave = buf[pos++] == MODE_CAVE;

    if (buf.length < SERIAL_BYTES) {
      throw new IllegalArgumentException(
          "Buffer too short for v4: " + buf.length + " (need " + SERIAL_BYTES + ")");
    }

    int[] baseColors = new int[ChunkColorData.PIXELS];
    int[] heights = new int[ChunkColorData.PIXELS];
    int[] waterDepths = new int[ChunkColorData.PIXELS];
    int[] waterTints = new int[ChunkColorData.PIXELS];
    boolean[] isLeaf = new boolean[ChunkColorData.PIXELS];
    boolean[] known = new boolean[ChunkColorData.PIXELS];

    pos = readInts(buf, pos, baseColors);
    pos = readInts(buf, pos, heights);
    pos = readInts(buf, pos, waterDepths);
    pos = readInts(buf, pos, waterTints);
    unpackBooleans(buf, pos, isLeaf);
    pos += ChunkColorData.PIXELS / 8;
    unpackBooleans(buf, pos, known);

    return isCave
        ? ChunkColorData.fromCodecCave(baseColors, heights, waterDepths, waterTints, isLeaf, known)
        : ChunkColorData.fromCodecSurface(baseColors, heights, waterDepths, waterTints, isLeaf, known);
  }

  private static int writeInts(byte[] buf, int pos, int[] arr) {
    for (int v : arr) {
      buf[pos++] = (byte) (v >> 24);
      buf[pos++] = (byte) (v >> 16);
      buf[pos++] = (byte) (v >> 8);
      buf[pos++] = (byte) v;
    }
    return pos;
  }

  private static int readInts(byte[] buf, int pos, int[] arr) {
    for (int i = 0; i < arr.length; i++) {
      arr[i] = ((buf[pos++] & 0xFF) << 24)
             | ((buf[pos++] & 0xFF) << 16)
             | ((buf[pos++] & 0xFF) << 8)
             | (buf[pos++] & 0xFF);
    }
    return pos;
  }

  private static void packBooleans(byte[] buf, int pos, boolean[] arr) {
    for (int i = 0; i < arr.length; i += 8) {
      int b = 0;
      for (int bit = 0; bit < 8 && i + bit < arr.length; bit++) {
        if (arr[i + bit]) b |= (1 << bit);
      }
      buf[pos + i / 8] = (byte) b;
    }
  }

  private static void unpackBooleans(byte[] buf, int pos, boolean[] arr) {
    for (int i = 0; i < arr.length; i += 8) {
      int b = buf[pos + i / 8] & 0xFF;
      for (int bit = 0; bit < 8 && i + bit < arr.length; bit++) {
        arr[i + bit] = (b & (1 << bit)) != 0;
      }
    }
  }
}
