package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Embedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The embedding cache asking the model for the texts it misses on several threads, against the
 * same texts embedded one by one through {@link EmbeddingCache#embed}, the sequential path: the
 * vectors in order, the counts, what the file holds after a save and what a failure leaves behind
 * must all be the same. Needs no model: the fake one sleeps a little at random, so the texts
 * finish out of order.
 */
class ParallelEmbeddingTest {

  private static final EmbeddingCache.Key KEY =
      new EmbeddingCache.Key("model", "revision", "implementation", "chunking", "cleaner");

  /** A fake model: a vector from the text's characters, nothing for one marked as unembeddable. */
  private static final class Fake implements Embedder {

    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private final String failOn;

    Fake(final String failOn) {
      this.failOn = failOn;
    }

    @Override
    public Embedding embed(final String text) {
      asked.add(text);
      LockSupport.parkNanos(Math.floorMod(text.hashCode(), 2_000_000));
      if (text.equals(failOn)) {
        throw new IllegalStateException("the model failed on " + text);
      }
      if (text.startsWith("nothing")) {
        return new Embedding(null, 1);
      }
      final float[] v = new float[4];
      for (int i = 0; i < text.length(); i++) {
        v[i % 4] += (float) Math.sin(text.charAt(i) * (i + 1));
      }
      return new Embedding(v, text.length() % 5 + 1);
    }
  }

  /** Two hundred texts with repeats, texts the model gives nothing for, and some already cached. */
  private static List<String> texts() {
    final Random random = new Random(3);
    final List<String> out = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      final int pick = random.nextInt(140);
      out.add(pick % 17 == 0 ? "nothing " + pick : "text number " + pick + " ".repeat(pick % 4));
    }
    return out;
  }

  private static final List<String> CACHED = List.of("text number 3", "text number 5 ",
      "never asked");

  /** A cache under {@code dir} that already holds {@link #CACHED}. */
  private static EmbeddingCache primed(final Path dir, final Embedder model, final int threads)
      throws IOException {
    final EmbeddingCache first = EmbeddingCache.open(dir, KEY, new Fake(null));
    CACHED.forEach(first::embed);
    first.save();
    return EmbeddingCache.open(dir, KEY, model, threads);
  }

  private static void assertSameEmbeddings(final List<Embedder.Embedding> expected,
      final List<Embedder.Embedding> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      final Embedder.Embedding e = expected.get(i);
      final Embedder.Embedding a = actual.get(i);
      assertEquals(e.chunks(), a.chunks(), "text " + i);
      if (e.vector() == null) {
        assertNull(a.vector(), "text " + i);
      } else {
        assertArrayEquals(bits(e.vector()), bits(a.vector()), "text " + i);
      }
    }
  }

  private static int[] bits(final float[] v) {
    final int[] out = new int[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = Float.floatToRawIntBits(v[i]);
    }
    return out;
  }

  @Test
  @DisplayName("on one thread and on eight, the vectors come back in the texts' order, the hits "
      + "and misses are counted, and the saved file is byte for byte what embedding the texts one "
      + "by one leaves")
  void sameAsOneByOne(@TempDir final Path dir) throws IOException {
    final List<String> texts = texts();
    final Fake oneByOneModel = new Fake(null);
    final EmbeddingCache oneByOne = primed(dir.resolve("loop"), oneByOneModel, 1);
    final List<Embedder.Embedding> expected = new ArrayList<>();
    texts.forEach(t -> expected.add(oneByOne.embed(t)));
    oneByOne.save();
    final byte[] file = Files.readAllBytes(oneByOne.file());
    assertTrue(oneByOne.hits() > 0 && oneByOne.misses() > 0);

    for (final int threads : List.of(1, 8)) {
      final Fake model = new Fake(null);
      final EmbeddingCache cache = primed(dir.resolve("t" + threads), model, threads);
      assertSameEmbeddings(expected, cache.embedAll(texts));
      assertEquals(oneByOne.hits(), cache.hits(), threads + " threads");
      assertEquals(oneByOne.misses(), cache.misses(), threads + " threads");
      cache.save();
      assertArrayEquals(file, Files.readAllBytes(cache.file()), threads + " threads");
      assertEquals(new HashSet<>(model.asked), Set.copyOf(oneByOneModel.asked),
          "the same texts reach the model");
      assertEquals(model.asked.size(), Set.copyOf(model.asked).size(),
          "each text the model is asked for once");
    }
  }

  @Test
  @DisplayName("a text the model fails on surfaces its own exception, and the cache is left as "
      + "embedding one by one leaves it: what came before the failure, nothing after")
  void failure(@TempDir final Path dir) throws IOException {
    final List<String> texts = texts();
    final String bad = texts.get(120);
    final Fake loopModel = new Fake(bad);
    final EmbeddingCache loop = primed(dir.resolve("loop"), loopModel, 1);
    final IllegalStateException expected = assertThrows(IllegalStateException.class,
        () -> texts.forEach(loop::embed));
    loop.save();

    for (final int threads : List.of(1, 8)) {
      final Fake model = new Fake(bad);
      final EmbeddingCache cache = primed(dir.resolve("t" + threads), model, threads);
      final IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> cache.embedAll(texts));
      assertEquals(expected.getMessage(), thrown.getMessage());
      assertSame(IllegalStateException.class, thrown.getClass());
      assertEquals(loop.hits(), cache.hits(), threads + " threads");
      assertEquals(loop.misses(), cache.misses(), threads + " threads");
      cache.save();
      assertArrayEquals(Files.readAllBytes(loop.file()), Files.readAllBytes(cache.file()),
          threads + " threads");
    }
  }

  @Test
  @DisplayName("the plain embedder interface embeds a list one text after the other, in order")
  void interfaceDefault() {
    final Fake model = new Fake(null);
    final List<String> texts = List.of("b", "a", "b", "nothing", "c");
    final List<Embedder.Embedding> all = model.embedAll(texts);
    assertEquals(texts, model.asked);
    assertEquals(texts.size(), all.size());
    for (int i = 0; i < texts.size(); i++) {
      final Embedder.Embedding one = new Fake(null).embed(texts.get(i));
      assertEquals(one.chunks(), all.get(i).chunks());
    }
  }
}
