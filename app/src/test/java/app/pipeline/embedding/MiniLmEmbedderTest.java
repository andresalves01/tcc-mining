package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.issues.Embedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plain-Java embedder against vectors sentence-transformers produced once for six sentences,
 * read through {@link ReferenceCheck}, the comparison the similarity command runs too. Needs the
 * model on disk: set MODEL_DIR to a directory issues-download-model filled, or these are skipped;
 * with REQUIRE_MODEL=1 they fail instead (see {@link ModelGate}).
 */
class MiniLmEmbedderTest {

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
    assumeTrue(embedder != null, MiniLmEmbedder.MODEL_DIR + " is unset: the comparison with "
        + "sentence-transformers needs the model on disk (run issues-download-model into a "
        + "directory and set " + MiniLmEmbedder.MODEL_DIR + " to it); skipped. Set "
        + ModelGate.REQUIRE_MODEL + "=1 to fail instead");
  }

  @Test
  @DisplayName("the six reference sentences give the same token ids as sentence-transformers and "
      + "its vectors within 1e-4, at length one")
  void reference() {
    needsModel();
    final ReferenceCheck.Result r = ReferenceCheck.compare(embedder, embedder::chunks);
    assertEquals(List.of(), r.problems());
    assertTrue(r.maxDifference() <= 1e-4, Double.toString(r.maxDifference()));
    for (final ReferenceCheck.Item item : ReferenceCheck.items()) {
      assertEquals(1.0, norm(embedder.embed(item.text()).vector()), 1e-5, item.text());
    }
  }

  @Test
  @DisplayName("a long text is split into chunks of at most 256 tokens with no token lost, and its "
      + "vector has length one")
  void longText() {
    needsModel();
    final ReferenceCheck.Item item = ReferenceCheck.items().get(0);
    final long[] one = item.ids();
    final int repeats = 40;
    final String text = String.join(" ", Collections.nCopies(repeats, item.text()));

    // WordPiece splits on whitespace first, so the repeated sentence is its content tokens
    // repeated.
    final List<Long> expected = new ArrayList<>();
    for (int r = 0; r < repeats; r++) {
      for (int i = 1; i < one.length - 1; i++) {
        expected.add(one[i]);
      }
    }
    final List<long[]> chunks = embedder.chunks(text);
    final List<Long> joined = new ArrayList<>();
    for (final long[] c : chunks) {
      assertTrue(c.length <= EmbeddingModel.MAX_TOKENS, "chunk of " + c.length);
      assertEquals(one[0], c[0]);
      assertEquals(one[one.length - 1], c[c.length - 1]);
      for (int i = 1; i < c.length - 1; i++) {
        joined.add(c[i]);
      }
    }
    assertEquals(expected, joined);
    final int perChunk = EmbeddingModel.MAX_TOKENS - 2;
    assertEquals((expected.size() + perChunk - 1) / perChunk, chunks.size());

    final Embedder.Embedding e = embedder.embed(text);
    assertEquals(chunks.size(), e.chunks());
    assertEquals(1.0, norm(e.vector()), 1e-5);
  }

  @Test
  @DisplayName("a model directory with a file of the wrong SHA-256 is refused at load")
  void refusesWrongHash(@TempDir final Path dir) throws IOException {
    for (final EmbeddingModel.File f : EmbeddingModel.FILES) {
      Files.createDirectories(dir.resolve(f.path()).getParent());
      Files.writeString(dir.resolve(f.path()), "not the model");
    }
    final ModelFileMismatch e = assertThrows(ModelFileMismatch.class,
        () -> MiniLmEmbedder.load(dir));
    assertTrue(e.getMessage().contains(EmbeddingModel.WEIGHTS), e.getMessage());
  }

  @Test
  @DisplayName("the same text gives the same vector bit for bit, also from several threads at once")
  void deterministic() throws Exception {
    needsModel();
    final ReferenceCheck.Item item = ReferenceCheck.items().get(1);
    final String text = String.join(" ", Collections.nCopies(30, item.text()));
    final float[] first = embedder.embed(text).vector();
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      final List<Future<float[]>> runs = new ArrayList<>();
      for (int r = 0; r < 8; r++) {
        runs.add(pool.submit(() -> embedder.embed(text).vector()));
      }
      for (final Future<float[]> run : runs) {
        assertArrayEquals(first, run.get());
      }
    } finally {
      pool.shutdown();
    }
  }

  private static double norm(final float[] v) {
    double s = 0;
    for (final float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }
}
