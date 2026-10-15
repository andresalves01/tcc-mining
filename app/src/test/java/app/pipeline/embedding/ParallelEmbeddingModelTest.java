package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.issues.Embedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real model asked through the embedding cache on every processor, against the same model
 * asked one text at a time: every vector the same bits. Needs the model on disk, as {@link
 * MiniLmEmbedderTest} does (see {@link ModelGate}).
 */
class ParallelEmbeddingModelTest {

  private static MiniLmEmbedder embedder;
  private static String failure;

  @BeforeAll
  static void load() {
    final ModelGate.Gate<MiniLmEmbedder> gate = ModelGate.fromEnvironment(MiniLmEmbedder::load);
    embedder = gate.model();
    failure = gate.failure();
  }

  private static void needsModel() {
    if (failure != null) {
      fail(failure);
    }
    assumeTrue(embedder != null, MiniLmEmbedder.MODEL_DIR + " is unset; skipped. Set "
        + ModelGate.REQUIRE_MODEL + "=1 to fail instead");
  }

  private static final List<String> WORDS = List.of("the", "build", "fails", "when", "parser",
      "NullPointerException", "at", "org.apache.hadoop.fs.FileSystem.get(FileSystem.java:123)",
      "should", "return", "empty", "list", "instead", "of", "null", "überprüfen", "configuração",
      "日本語", "テスト", "ошибка", "émoji 🚀", "{\"key\": [1, 2, 3]}", "<b>bold</b>", "--flag=on",
      "x", "1.0e-5", "SELECT * FROM t WHERE id = ?", "can't", "won't", "naïve", "café", "\t",
      "https://issues.apache.org/jira/browse/HADOOP-1234", "a_b_c", "CamelCaseIdentifier");

  /**
   * Three hundred texts: single words, sentences, code and stack traces, several scripts and
   * accents, a few repeated, and some long enough to take several chunks.
   */
  static List<String> texts() {
    final Random random = new Random(17);
    final List<String> out = new ArrayList<>();
    for (int t = 0; t < 300; t++) {
      final int words = t % 25 == 0 ? 400 + random.nextInt(900) : 1 + random.nextInt(60);
      final StringBuilder b = new StringBuilder();
      for (int w = 0; w < words; w++) {
        b.append(WORDS.get(random.nextInt(WORDS.size()))).append(w % 13 == 12 ? ".\n" : " ");
      }
      out.add(b.toString().strip());
    }
    out.add(out.get(7));
    out.add(out.get(50));
    return out;
  }

  @Test
  @DisplayName("three hundred varied texts, some in several chunks, embedded on every processor "
      + "through the cache give the model's vectors one text at a time, bit for bit, in order")
  void sameVectors(@TempDir final Path dir) throws IOException {
    needsModel();
    final List<String> texts = texts();
    final List<Embedder.Embedding> one = new ArrayList<>();
    for (final String t : texts) {
      one.add(embedder.embed(t));
    }
    assertTrue(one.stream().filter(e -> e.chunks() > 1).count() >= 5, "multi-chunk texts");

    final EmbeddingCache cache = EmbeddingCache.open(dir, EmbeddingCache.pinned(), embedder,
        Math.max(2, Runtime.getRuntime().availableProcessors()));
    final List<Embedder.Embedding> many = cache.embedAll(texts);
    assertEquals(one.size(), many.size());
    for (int i = 0; i < one.size(); i++) {
      assertEquals(one.get(i).chunks(), many.get(i).chunks(), "text " + i);
      assertArrayEquals(bits(one.get(i).vector()), bits(many.get(i).vector()), "text " + i);
    }
    assertEquals(2, cache.hits(), "the two repeats");

    // and the file a parallel run saves reads back to the same bits
    cache.save();
    assertTrue(Files.size(cache.file()) > 0);
    final EmbeddingCache again = EmbeddingCache.open(dir, EmbeddingCache.pinned(), text -> {
      throw new AssertionError("asked the model for a cached text");
    });
    final List<Embedder.Embedding> read = again.embedAll(texts);
    for (int i = 0; i < one.size(); i++) {
      assertArrayEquals(bits(one.get(i).vector()), bits(read.get(i).vector()), "text " + i);
    }
  }

  private static int[] bits(final float[] v) {
    final int[] out = new int[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = Float.floatToRawIntBits(v[i]);
    }
    return out;
  }
}
