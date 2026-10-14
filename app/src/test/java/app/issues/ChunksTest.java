package app.issues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a tokenised text becomes chunks within the limit, and how the chunk vectors become one. */
class ChunksTest {

  private static final long CLS = 101;
  private static final long SEP = 102;

  @Test
  @DisplayName("a long text is split into consecutive chunks of at most the limit, special tokens "
      + "included, and no token is lost")
  void longText() {
    final long[] content = LongStream.range(1000, 1600).toArray();
    final List<long[]> chunks = Chunks.split(content, 256, CLS, SEP);

    assertEquals(3, chunks.size()); // 600 content tokens, 254 per chunk
    final List<Long> joined = new ArrayList<>();
    for (final long[] c : chunks) {
      assertTrue(c.length <= 256, "chunk of " + c.length);
      assertEquals(CLS, c[0]);
      assertEquals(SEP, c[c.length - 1]);
      for (int i = 1; i < c.length - 1; i++) {
        joined.add(c[i]);
      }
    }
    assertArrayEquals(content, joined.stream().mapToLong(Long::longValue).toArray());
    assertEquals(256, chunks.get(0).length);
    assertEquals(256, chunks.get(1).length);
    assertEquals(600 - 2 * 254 + 2, chunks.get(2).length);
  }

  @Test
  @DisplayName("a text within the limit is one chunk, exactly the limit included; an empty one is "
      + "the two special tokens")
  void shortText() {
    final long[] exact = LongStream.range(0, 254).toArray();
    assertEquals(1, Chunks.split(exact, 256, CLS, SEP).size());
    assertEquals(2, Chunks.split(LongStream.range(0, 255).toArray(), 256, CLS, SEP).size());
    assertArrayEquals(new long[] {CLS, 7, SEP}, Chunks.split(new long[] {7}, 256, CLS, SEP).get(0));
    final List<long[]> empty = Chunks.split(new long[0], 256, CLS, SEP);
    assertEquals(1, empty.size());
    assertArrayEquals(new long[] {CLS, SEP}, empty.get(0));
  }

  @Test
  @DisplayName("chunk vectors are averaged weighted by content tokens and the result has length one")
  void combine() {
    // 3 content tokens pointing along x, 1 along y: the mean is (3, 1) / 4, normalised.
    final float[] v = Chunks.combine(List.of(new float[] {1, 0}, new float[] {0, 1}),
        new int[] {3, 1});
    final double n = Math.sqrt(10);
    assertEquals(3 / n, v[0], 1e-6);
    assertEquals(1 / n, v[1], 1e-6);
  }

  @Test
  @DisplayName("one chunk with no content tokens is its own vector, normalised")
  void combineEmpty() {
    final float[] v = Chunks.combine(List.of(new float[] {3, 4}), new int[] {0});
    assertEquals(0.6, v[0], 1e-6);
    assertEquals(0.8, v[1], 1e-6);
  }

  @Test
  @DisplayName("mean pooling counts only the positions the attention mask keeps, then normalises")
  void meanPool() {
    final float[][] hidden = {{1, 0}, {0, 3}, {100, 100}};
    final float[] v = Chunks.meanPool(hidden, new long[] {1, 1, 0});
    // mean of the first two rows is (0.5, 1.5); length one is (1, 3) / sqrt(10).
    assertEquals(1 / Math.sqrt(10), v[0], 1e-6);
    assertEquals(3 / Math.sqrt(10), v[1], 1e-6);
  }
}
