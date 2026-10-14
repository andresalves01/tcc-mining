package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.issues.Embedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java embedder against vectors sentence-transformers produced once for six sentences
 * ({@code reference.json}: sentence-transformers 6.1.0, the pinned revision, CPU). Needs the model
 * on disk: set MODEL_DIR to a directory issues-download-model filled, or these are skipped.
 */
class OnnxEmbedderTest {

  private static OnnxEmbedder embedder;
  private static JsonNode reference;

  @BeforeAll
  static void load() throws IOException {
    try (InputStream in = OnnxEmbedderTest.class.getResourceAsStream("reference.json")) {
      reference = new ObjectMapper().readTree(in);
    }
    final String dir = System.getenv(OnnxEmbedder.MODEL_DIR);
    if (dir != null && !dir.isBlank()) {
      embedder = OnnxEmbedder.load(Path.of(dir));
    }
  }

  @AfterAll
  static void close() throws Exception {
    if (embedder != null) {
      embedder.close();
    }
  }

  private static void needsModel() {
    assumeTrue(embedder != null, OnnxEmbedder.MODEL_DIR + " is unset: the comparison with "
        + "sentence-transformers needs the model on disk (run issues-download-model into a "
        + "directory and set " + OnnxEmbedder.MODEL_DIR + " to it); skipped");
  }

  private static long[] ids(final JsonNode item) {
    final long[] out = new long[item.get("ids").size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = item.get("ids").get(i).asLong();
    }
    return out;
  }

  @Test
  @DisplayName("the reference was produced at the limit the embedder chunks to")
  void referenceLimit() {
    assertEquals(EmbeddingModel.MAX_TOKENS, reference.get("max_seq_length").asInt());
    assertTrue(reference.get("source").asText().contains(EmbeddingModel.REVISION));
  }

  @Test
  @DisplayName("the six reference sentences give the same token ids as sentence-transformers")
  void tokenIds() {
    needsModel();
    for (final JsonNode item : reference.get("items")) {
      final List<long[]> chunks = embedder.chunks(item.get("text").asText());
      assertEquals(1, chunks.size());
      assertArrayEquals(ids(item), chunks.get(0), item.get("text").asText());
    }
  }

  @Test
  @DisplayName("the six reference sentences give the sentence-transformers vectors within 1e-4, "
      + "at length one")
  void vectors() {
    needsModel();
    for (final JsonNode item : reference.get("items")) {
      final Embedder.Embedding e = embedder.embed(item.get("text").asText());
      final JsonNode expected = item.get("vector");
      assertEquals(expected.size(), e.vector().length);
      assertEquals(1, e.chunks());
      for (int d = 0; d < expected.size(); d++) {
        assertEquals(expected.get(d).asDouble(), e.vector()[d], 1e-4,
            item.get("text").asText() + " at " + d);
      }
      assertEquals(1.0, norm(e.vector()), 1e-5);
    }
  }

  @Test
  @DisplayName("a long text is split into chunks of at most 256 tokens with no token lost, and its "
      + "vector has length one")
  void longText() {
    needsModel();
    final JsonNode item = reference.get("items").get(0);
    final long[] one = ids(item);
    final int repeats = 40;
    final String text = String.join(" ", Collections.nCopies(repeats, item.get("text").asText()));

    // WordPiece splits on whitespace first, so the repeated sentence is its content tokens repeated.
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
        () -> OnnxEmbedder.load(dir));
    assertTrue(e.getMessage().contains(EmbeddingModel.ONNX), e.getMessage());
  }

  private static double norm(final float[] v) {
    double s = 0;
    for (final float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }
}
