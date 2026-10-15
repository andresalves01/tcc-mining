package app.pipeline.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A model's weights in the safetensors format, read in plain Java: an 8-byte little-endian header
 * length, a JSON header naming each tensor's dtype, shape and byte span, then the raw data.
 *
 * <p>Only 32-bit floats are read, little-endian and row-major as the format stores them; a tensor
 * asked for under another dtype, another shape, or whose span does not hold exactly its elements
 * is refused, never reinterpreted. Tensors that are not asked for are not read.
 */
final class Safetensors {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One tensor's entry in the header: its dtype, shape and span within the data. */
  private record Entry(String dtype, int[] shape, long begin, long end) {}

  private final Path file;
  private final ByteBuffer data;
  private final Map<String, Entry> entries;

  private Safetensors(final Path file, final ByteBuffer data, final Map<String, Entry> entries) {
    this.file = file;
    this.data = data;
    this.entries = entries;
  }

  /** The tensors of {@code file}, refused unless its header is whole and every span in the file. */
  static Safetensors read(final Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      final long size = channel.size();
      if (size < Long.BYTES) {
        throw refused(file, "shorter than its header length");
      }
      if (size > Integer.MAX_VALUE) {
        throw refused(file, size + " bytes; only files under 2 GiB are read");
      }
      final ByteBuffer all = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
          .order(ByteOrder.LITTLE_ENDIAN);
      final long length = all.getLong(0);
      if (length < 2 || length > size - Long.BYTES) {
        throw refused(file, "a header of " + length + " bytes in a file of " + size);
      }
      final byte[] header = new byte[(int) length];
      all.get(Long.BYTES, header);
      final ByteBuffer data = all.slice(Long.BYTES + (int) length,
          (int) (size - Long.BYTES - length)).order(ByteOrder.LITTLE_ENDIAN);
      return new Safetensors(file, data, entries(file,
          new String(header, StandardCharsets.UTF_8), data.capacity()));
    }
  }

  /**
   * The elements of tensor {@code name}, row-major, refused unless it is a 32-bit float tensor of
   * exactly {@code shape}.
   */
  float[] floats(final String name, final int... shape) throws IOException {
    final Entry e = entries.get(name);
    if (e == null) {
      throw refused(file, "no tensor " + name);
    }
    if (!e.dtype().equals("F32")) {
      throw refused(file, name + " is " + e.dtype() + ", not F32");
    }
    if (!Arrays.equals(e.shape(), shape)) {
      throw refused(file, name + " has shape " + Arrays.toString(e.shape()) + ", expected "
          + Arrays.toString(shape));
    }
    long count = 1;
    for (final int d : shape) {
      count *= d;
    }
    if (e.end() - e.begin() != count * Float.BYTES) {
      throw refused(file, name + " spans " + (e.end() - e.begin()) + " bytes for " + count
          + " floats");
    }
    final float[] out = new float[(int) count];
    data.slice((int) e.begin(), (int) (e.end() - e.begin())).order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer().get(out);
    return out;
  }

  private static Map<String, Entry> entries(final Path file, final String header,
      final long dataBytes) throws IOException {
    final JsonNode root;
    try {
      root = JSON.readTree(header);
    } catch (final JsonProcessingException e) {
      throw refused(file, "a header that is not JSON (" + e.getOriginalMessage() + ")");
    }
    if (root == null || !root.isObject()) {
      throw refused(file, "a header that is not a JSON object");
    }
    final Map<String, Entry> out = new HashMap<>();
    for (final Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
      final Map.Entry<String, JsonNode> f = it.next();
      if (f.getKey().equals("__metadata__")) {
        continue;
      }
      final JsonNode t = f.getValue();
      final JsonNode shape = t.path("shape");
      final JsonNode span = t.path("data_offsets");
      if (!t.path("dtype").isTextual() || !shape.isArray() || !span.isArray()
          || span.size() != 2 || !span.get(0).canConvertToLong()
          || !span.get(1).canConvertToLong()) {
        throw refused(file, f.getKey() + " lacks a dtype, shape or two data offsets");
      }
      final int[] dims = new int[shape.size()];
      for (int d = 0; d < dims.length; d++) {
        if (!shape.get(d).canConvertToInt() || shape.get(d).asInt() < 0) {
          throw refused(file, f.getKey() + " has shape " + shape);
        }
        dims[d] = shape.get(d).asInt();
      }
      final long begin = span.get(0).asLong();
      final long end = span.get(1).asLong();
      if (begin < 0 || end < begin || end > dataBytes) {
        throw refused(file, f.getKey() + " spans bytes " + begin + " to " + end + " of "
            + dataBytes);
      }
      out.put(f.getKey(), new Entry(t.get("dtype").asText(), dims, begin, end));
    }
    return Map.copyOf(out);
  }

  private static IOException refused(final Path file, final String why) {
    return new IOException(file + " refused: " + why);
  }
}
