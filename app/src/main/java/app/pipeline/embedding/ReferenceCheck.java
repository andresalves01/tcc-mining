package app.pipeline.embedding;

import app.issues.Embedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The embedder against the vectors sentence-transformers produced once for six sentences
 * ({@code reference.json}, shipped with the program: sentence-transformers 6.1.0, the pinned
 * revision, CPU): the same token ids exactly, each sentence in one chunk, and every vector
 * component within {@link #TOLERANCE}.
 *
 * <p>The one comparison both the embedder's test and the similarity command run, so the two
 * cannot drift: the command refuses to embed anything with a model that fails it.
 */
public final class ReferenceCheck {

  /** The largest difference allowed in any vector component. */
  public static final double TOLERANCE = 1e-4;

  /** The reference file, beside this class on the class path. */
  public static final String RESOURCE = "reference.json";

  /** One reference sentence: its text, its token ids with the special ones, its vector. */
  public record Item(String text, long[] ids, double[] vector) {}

  /**
   * @param problems what differed, one line each; empty when the embedder passed
   * @param maxDifference the largest absolute difference over every component compared,
   *     infinite when a component was not a number
   */
  public record Result(List<String> problems, double maxDifference) {

    public boolean passed() {
      return problems.isEmpty();
    }
  }

  private ReferenceCheck() {}

  /**
   * The six reference sentences, read from the class path at each call.
   *
   * @throws IllegalStateException when the file is missing, incomplete, or not produced at the
   *     pinned revision and limit
   */
  public static List<Item> items() {
    return load();
  }

  /**
   * Compares {@code embedder}'s vectors, and the token ids of each chunk {@code chunks} gives, with
   * the reference.
   */
  public static Result compare(final Embedder embedder,
      final Function<String, List<long[]>> chunks) {
    final List<String> problems = new ArrayList<>();
    double max = 0;
    for (final Item item : items()) {
      final List<long[]> got = chunks.apply(item.text());
      if (got.size() != 1 || !Arrays.equals(got.get(0), item.ids())) {
        problems.add("\"" + item.text() + "\": token ids "
            + got.stream().map(Arrays::toString).toList() + ", reference "
            + Arrays.toString(item.ids()));
      }
      final Embedder.Embedding e = embedder.embed(item.text());
      final float[] v = e == null ? null : e.vector();
      if (v == null || v.length != item.vector().length) {
        problems.add("\"" + item.text() + "\": " + (v == null ? "no" : v.length)
            + " components, reference " + item.vector().length);
        continue;
      }
      if (e.chunks() != 1) {
        problems.add("\"" + item.text() + "\": " + e.chunks() + " chunks, reference 1");
      }
      double worst = 0;
      for (int d = 0; d < v.length; d++) {
        final double diff = Math.abs((double) v[d] - item.vector()[d]);
        worst = Double.isNaN(diff) ? Double.POSITIVE_INFINITY : Math.max(worst, diff);
      }
      max = Math.max(max, worst);
      if (!(worst <= TOLERANCE)) {
        problems.add("\"" + item.text() + "\": a component differs by " + worst
            + ", more than " + TOLERANCE);
      }
    }
    return new Result(List.copyOf(problems), max);
  }

  private static List<Item> load() {
    try (InputStream in = ReferenceCheck.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the class path beside "
            + ReferenceCheck.class.getName());
      }
      final JsonNode root = new ObjectMapper().readTree(in);
      if (root == null || !root.has("max_seq_length") || !root.has("source")
          || !root.path("items").isArray()) {
        throw new IllegalStateException(RESOURCE + " lacks max_seq_length, source or items");
      }
      if (root.get("max_seq_length").asInt() != EmbeddingModel.MAX_TOKENS
          || !root.get("source").asText().contains(EmbeddingModel.REVISION)) {
        throw new IllegalStateException(RESOURCE + " was not produced at the pinned revision "
            + "and limit: " + root.get("source").asText());
      }
      final List<Item> out = new ArrayList<>();
      for (final JsonNode item : root.get("items")) {
        if (!item.path("text").isTextual() || !item.path("ids").isArray()
            || !item.path("vector").isArray()) {
          throw new IllegalStateException(RESOURCE + " has an item without text, ids or vector");
        }
        final long[] ids = new long[item.get("ids").size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = item.get("ids").get(i).asLong();
        }
        final double[] vector = new double[item.get("vector").size()];
        for (int d = 0; d < vector.length; d++) {
          vector[d] = item.get("vector").get(d).asDouble();
        }
        out.add(new Item(item.get("text").asText(), ids, vector));
      }
      return List.copyOf(out);
    } catch (final IOException e) {
      throw new UncheckedIOException("could not read " + RESOURCE, e);
    }
  }
}
