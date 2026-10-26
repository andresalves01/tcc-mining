package app.cli.commands;

import app.issues.Embedder;
import app.issues.port.ChangedMethodSource;
import app.labels.ChangedMethod;
import app.pipeline.embedding.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * What the stages after the extraction read besides the corpus, held in memory for the fixture of
 * {@link app.pipeline.ExtractionFixture}: vectors made from the words of a description, and the
 * methods of the commits whose sha starts with {@code a} or {@code c}.
 */
final class StagesFixture {

  private StagesFixture() {}

  /**
   * Each word hashed into one of the model's dimensions, the counts scaled to length one: the
   * same description gives the same vector, and descriptions sharing words are close.
   */
  static final Embedder WORDS = text -> {
    final double[] v = new double[EmbeddingModel.DIMENSION];
    for (final String word : text.split("\\s+")) {
      final byte[] b = word.getBytes(StandardCharsets.UTF_8);
      int h = 7;
      for (final byte x : b) {
        h = h * 31 + x;
      }
      v[Math.floorMod(h, v.length)] += 1 + b.length % 3;
    }
    double norm = 0;
    for (final double x : v) {
      norm += x * x;
    }
    norm = Math.sqrt(norm);
    final float[] out = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = (float) (v[i] / norm);
    }
    return new Embedder.Embedding(out, 1);
  };

  /** The methods of the fixture's commits {@code ca<n>} and {@code cc<n>}, by their number. */
  static ChangedMethodSource methods() {
    return new ChangedMethodSource() {
      @Override
      public Map<String, Map<String, ChangedMethod>> changed(final Collection<String> ids) {
        final Map<String, Map<String, ChangedMethod>> out = new HashMap<>();
        for (final String id : ids) {
          if (id.startsWith("ca") || id.startsWith("cc")) {
            final int n = id.length();
            out.put(id, Map.of("M." + id + "()", new ChangedMethod("M." + id + "()", 1 + n % 4,
                1 + n % 2)));
          }
        }
        return out;
      }
    };
  }
}
