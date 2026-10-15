package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.DescriptionCleaner;
import app.issues.Embedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The embedding cache: what it returns without the model, and when it must ask the model again. */
class EmbeddingCacheTest {

  private static final EmbeddingCache.Key KEY =
      new EmbeddingCache.Key("model", "revision", "implementation", "chunking", "cleaner");

  /** Every text the fake model was asked for, in order. */
  private final List<String> asked = new ArrayList<>();

  private final Embedder model = text -> {
    asked.add(text);
    return new Embedder.Embedding(new float[] {text.length(), 0.1f, Float.MIN_VALUE, -0f},
        text.length() % 3 + 1);
  };

  /** Embeds {@code text} through a cache opened, used and saved under {@code key}. */
  private Embedder.Embedding once(final Path dir, final EmbeddingCache.Key key, final String text)
      throws IOException {
    final EmbeddingCache cache = EmbeddingCache.open(dir, key, model);
    final Embedder.Embedding e = cache.embed(text);
    cache.save();
    return e;
  }

  @Test
  @DisplayName("a rerun under the same key reads the vector and chunk count back, bit for bit, "
      + "without the model; hits and misses are counted")
  void hit(@TempDir final Path dir) throws IOException {
    final Embedder.Embedding first = once(dir, KEY, "some text");
    final EmbeddingCache cache = EmbeddingCache.open(dir, KEY, model);
    final Embedder.Embedding again = cache.embed("some text");
    assertEquals(List.of("some text"), asked);
    assertArrayEquals(first.vector(), again.vector());
    assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(again.vector()[3]));
    assertEquals(first.chunks(), again.chunks());
    cache.embed("other");
    cache.embed("other");
    assertEquals(2, cache.hits());
    assertEquals(1, cache.misses());
    assertEquals(List.of("some text", "other"), asked);
  }

  @Test
  @DisplayName("a change of the text, the model, its revision, the implementation that runs it, "
      + "the chunking policy or the cleaner version asks the model again")
  void invalidated(@TempDir final Path dir) throws IOException {
    once(dir, KEY, "text");
    once(dir, KEY, "text ");
    once(dir, key("other model", "revision", "implementation", "chunking", "cleaner"), "text");
    once(dir, key("model", "other revision", "implementation", "chunking", "cleaner"), "text");
    once(dir, key("model", "revision", "other implementation", "chunking", "cleaner"), "text");
    once(dir, key("model", "revision", "implementation", "other chunking", "cleaner"), "text");
    once(dir, key("model", "revision", "implementation", "chunking", "other cleaner"), "text");
    assertEquals(7, asked.size(), asked.toString());
    once(dir, KEY, "text");
    assertEquals(7, asked.size(), "the first key is still cached");
  }

  private static EmbeddingCache.Key key(final String model, final String revision,
      final String implementation, final String chunking, final String cleaner) {
    return new EmbeddingCache.Key(model, revision, implementation, chunking, cleaner);
  }

  @Test
  @DisplayName("the pinned key names the model, its revision, the Java implementation, the chunk "
      + "limit and the cleaner version")
  void pinned() {
    final EmbeddingCache.Key k = EmbeddingCache.pinned();
    assertEquals(EmbeddingModel.REPOSITORY, k.model());
    assertEquals(EmbeddingModel.REVISION, k.revision());
    assertEquals(MiniLmEmbedder.IMPLEMENTATION, k.implementation());
    assertTrue(k.chunking().contains(Integer.toString(EmbeddingModel.MAX_TOKENS)), k.chunking());
    assertEquals(DescriptionCleaner.VERSION, k.cleaner());
  }

  @Test
  @DisplayName("nothing is written until saved; a cache file whose header is not its key's is "
      + "refused rather than read")
  void refusesOtherHeader(@TempDir final Path dir) throws IOException {
    final EmbeddingCache cache = EmbeddingCache.open(dir, KEY, model);
    cache.embed("text");
    assertTrue(!Files.exists(cache.file()));
    cache.save();
    final List<String> lines = new ArrayList<>(Files.readAllLines(cache.file()));
    lines.set(0, lines.get(0).replace("chunking", "changed"));
    Files.write(cache.file(), lines);
    assertThrows(IOException.class, () -> EmbeddingCache.open(dir, KEY, model));
  }

  @Test
  @DisplayName("a cache file giving the same text twice, or a chunk count below one, is refused")
  void refusesDuplicates(@TempDir final Path dir) throws IOException {
    final EmbeddingCache cache = EmbeddingCache.open(dir, KEY, model);
    cache.embed("text");
    cache.save();
    final List<String> lines = new ArrayList<>(Files.readAllLines(cache.file()));
    Files.write(cache.file(), List.of(lines.get(0), lines.get(1), lines.get(1)));
    assertThrows(IOException.class, () -> EmbeddingCache.open(dir, KEY, model));
    final String[] f = lines.get(1).split("\t");
    Files.write(cache.file(), List.of(lines.get(0), f[0] + "\t0\t" + f[2]));
    assertThrows(IOException.class, () -> EmbeddingCache.open(dir, KEY, model));
  }
}
