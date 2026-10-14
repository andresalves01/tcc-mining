package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The arithmetic of an embedding beyond the model itself: how a tokenised description is cut to
 * fit the model's limit, how a chunk's token vectors become one, and how the chunks' vectors become
 * the description's.
 *
 * <p><b>Chunks.</b> The content tokens (the text's tokens, no special ones) are cut into
 * consecutive runs of at most {@code limit - 2}, each wrapped in the classifier and separator
 * tokens, so every chunk holds at most {@code limit} tokens, special tokens included, and the
 * runs joined give back every content token in order. A text with no content token is the one
 * chunk of the two special tokens, as the tokenizer gives it.
 *
 * <p><b>Pooling.</b> A chunk's vector is the mean of its token vectors over the positions the
 * attention mask keeps, scaled to length one -- what sentence-transformers does for
 * all-MiniLM-L6-v2.
 *
 * <p><b>Combining.</b> The description's vector is the mean of its chunks' vectors weighted by each
 * chunk's content tokens, scaled to length one, so a short closing chunk counts for what it holds.
 * A single chunk with no content token keeps its own vector.
 *
 * <p>Pure: arrays in, arrays out.
 */
public final class Chunks {

  /** The classifier and separator tokens each chunk carries besides its content. */
  public static final int SPECIAL_TOKENS = 2;

  private Chunks() {}

  /** The chunks of {@code content}, each opening with {@code cls} and closing with {@code sep}. */
  public static List<long[]> split(final long[] content, final int limit, final long cls,
      final long sep) {
    final int room = limit - SPECIAL_TOKENS;
    if (room < 1) {
      throw new IllegalArgumentException("a limit of " + limit + " leaves no room for content");
    }
    final List<long[]> out = new ArrayList<>();
    int from = 0;
    do {
      final int to = Math.min(content.length, from + room);
      final long[] chunk = new long[to - from + SPECIAL_TOKENS];
      chunk[0] = cls;
      System.arraycopy(content, from, chunk, 1, to - from);
      chunk[chunk.length - 1] = sep;
      out.add(chunk);
      from = to;
    } while (from < content.length);
    return out;
  }

  /** The mean of the rows of {@code hidden} the mask keeps (non-zero), at length one. */
  public static float[] meanPool(final float[][] hidden, final long[] mask) {
    if (hidden.length == 0 || hidden.length != mask.length) {
      throw new IllegalArgumentException(hidden.length + " token vectors for a mask of "
          + mask.length);
    }
    final double[] sum = new double[hidden[0].length];
    long kept = 0;
    for (int t = 0; t < hidden.length; t++) {
      if (mask[t] != 0) {
        kept++;
        for (int d = 0; d < sum.length; d++) {
          sum[d] += hidden[t][d];
        }
      }
    }
    if (kept == 0) {
      throw new IllegalArgumentException("the attention mask keeps no position");
    }
    return unit(sum);
  }

  /** The mean of {@code vectors} weighted by {@code contentTokens}, at length one. */
  public static float[] combine(final List<float[]> vectors, final int[] contentTokens) {
    if (vectors.size() != contentTokens.length || vectors.isEmpty()) {
      throw new IllegalArgumentException(vectors.size() + " vectors for "
          + contentTokens.length + " weights");
    }
    if (vectors.size() == 1) {
      final float[] only = vectors.get(0);
      return unit(toDouble(only));
    }
    final double[] sum = new double[vectors.get(0).length];
    for (int c = 0; c < vectors.size(); c++) {
      final float[] v = vectors.get(c);
      for (int d = 0; d < sum.length; d++) {
        sum[d] += (double) contentTokens[c] * v[d];
      }
    }
    return unit(sum);
  }

  private static double[] toDouble(final float[] v) {
    final double[] out = new double[v.length];
    for (int d = 0; d < v.length; d++) {
      out[d] = v[d];
    }
    return out;
  }

  private static float[] unit(final double[] v) {
    final double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
    if (norm == 0) {
      throw new IllegalArgumentException("a zero vector has no direction");
    }
    final float[] out = new float[v.length];
    for (int d = 0; d < v.length; d++) {
      out[d] = (float) (v[d] / norm);
    }
    return out;
  }
}
