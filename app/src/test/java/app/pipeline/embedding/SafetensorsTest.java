package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The safetensors reader, on small files written by hand in the format's layout. */
class SafetensorsTest {

  @TempDir
  Path dir;

  /** A file of {@code header} and {@code data}: the header's length, little-endian, first. */
  private Path file(final String header, final byte[] data) throws IOException {
    final byte[] h = header.getBytes(StandardCharsets.UTF_8);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(h.length).array());
    out.write(h);
    out.write(data);
    final Path p = dir.resolve("model.safetensors");
    Files.write(p, out.toByteArray());
    return p;
  }

  private static byte[] floats(final float... v) {
    final ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
    for (final float x : v) {
      b.putFloat(x);
    }
    return b.array();
  }

  private static byte[] concat(final byte[] a, final byte[] b) {
    final byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static final String TWO = "{\"__metadata__\":{\"format\":\"pt\"},"
      + "\"ids\":{\"dtype\":\"I64\",\"shape\":[2],\"data_offsets\":[0,16]},"
      + "\"w\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[16,40]}}";

  @Test
  @DisplayName("a float tensor is read in row-major order from its little-endian bytes")
  void readsFloats() throws IOException {
    final Path p = file(TWO, concat(new byte[16], floats(1, -2, 3.5f, 0, 1e-8f, -0f)));
    final Safetensors s = Safetensors.read(p);
    assertArrayEquals(new float[] {1, -2, 3.5f, 0, 1e-8f, -0f}, s.floats("w", 2, 3));
  }

  @Test
  @DisplayName("a missing tensor, another dtype or another shape is refused, naming the tensor")
  void refusesWhatItDoesNotHold() throws IOException {
    final Safetensors s = Safetensors.read(file(TWO, concat(new byte[16],
        floats(1, 2, 3, 4, 5, 6))));
    assertMessage(assertThrows(IOException.class, () -> s.floats("absent", 2, 3)), "absent");
    assertMessage(assertThrows(IOException.class, () -> s.floats("ids", 2)), "I64");
    assertMessage(assertThrows(IOException.class, () -> s.floats("w", 3, 2)), "[2, 3]");
    assertMessage(assertThrows(IOException.class, () -> s.floats("w", 6)), "[2, 3]");
  }

  @Test
  @DisplayName("a tensor whose offsets do not hold exactly its elements, or run past the file, is "
      + "refused")
  void refusesBadOffsets() throws IOException {
    final String shortSpan = "{\"w\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[0,20]}}";
    final Safetensors a = Safetensors.read(file(shortSpan, floats(1, 2, 3, 4, 5, 6)));
    assertMessage(assertThrows(IOException.class, () -> a.floats("w", 2, 3)), "w");
    final String past = "{\"w\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[8,32]}}";
    assertThrows(IOException.class, () -> Safetensors.read(file(past, floats(1, 2, 3, 4, 5, 6))));
  }

  @Test
  @DisplayName("a header longer than the file, or not a JSON object, is refused")
  void refusesBadHeader() throws IOException {
    final Path p = dir.resolve("truncated.safetensors");
    Files.write(p, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(1000).array());
    assertThrows(IOException.class, () -> Safetensors.read(p));
    assertThrows(IOException.class, () -> Safetensors.read(file("[1, 2]", new byte[0])));
    assertThrows(IOException.class, () -> Safetensors.read(file("{not json", new byte[0])));
  }

  private static void assertMessage(final IOException e, final String part) {
    assertTrue(e.getMessage().contains(part), e.getMessage());
  }
}
