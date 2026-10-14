package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import app.pipeline.clustering.SmileKMeans;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The similarity columns after extraction, built from rows and descriptions in memory. */
class AugmentationTest {

  private static final long H = 3_600_000L;

  private final List<Augmentation.Issue> issues = new ArrayList<>();

  /** The hand-made embedding of each description that has one, by its text. */
  private final Map<String, Embedder.Embedding> hand = new HashMap<>();

  /**
   * The fake embedder: a hand-made vector when the text has one, otherwise the text's distinct
   * tokens counted into {@link #DIMENSION} buckets at length one -- the zero vector, invalid, for a
   * text with no token.
   */
  private final Embedder fake = text -> {
    final Embedder.Embedding e = hand.get(text);
    if (e != null) {
      return e;
    }
    final double[] v = new double[DIMENSION];
    Jaccard.tokens(text).forEach(t -> v[Math.floorMod(t.hashCode(), DIMENSION)]++);
    final double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
    final float[] out = new float[DIMENSION];
    for (int d = 0; d < DIMENSION; d++) {
      out[d] = norm == 0 ? 0f : (float) (v[d] / norm);
    }
    return new Embedder.Embedding(out, 1);
  };

  private static final int DIMENSION = 3;

  private Augmentation.Result run(final List<Augmentation.Issue> rows) {
    return Augmentation.run(rows, fake, DIMENSION, new SmileKMeans());
  }

  /** The description {@code text} embeds as {@code v}, in one chunk. */
  private void vector(final String text, final float... v) {
    chunked(text, 1, v);
  }

  private void chunked(final String text, final int chunks, final float... v) {
    hand.put(text, new Embedder.Embedding(v, chunks));
  }

  private static Double embedding(final Augmentation.Result r, final String id, final int n,
      final Variant v) {
    return r.columns().get(id).get(Augmentation.embedding(n, v));
  }

  private static Augmentation.EmbeddingQuery near(final Augmentation.Result r, final String id,
      final Variant v) {
    return r.embeddingQueries().stream().filter(x -> x.id().equals(id) && x.variant() == v)
        .findFirst().orElseThrow();
  }

  @Test
  @DisplayName("embedding: neighbours by one minus the dot product, closest first, ties by id "
      + "never by duration; only positive similarity is a candidate; fewer than n uses all")
  void embeddingNeighbours() {
    vector("q", 1, 0, 0);
    vector("same", 1, 0, 0);
    vector("up", 0.6f, 0.8f, 0);
    vector("side", 0.6f, 0, 0.8f);
    vector("orthogonal", 0, 1, 0);
    vector("opposite", -1, 0, 0);
    fit("s", 0, 7, "same");
    fit("b", 0, 100, "up");
    fit("a", 0, 1, "side");
    fit("o", 0, 3, "orthogonal");
    fit("n", 0, 4, "opposite");
    validation("v", 1_000, "q");
    final Augmentation.Result r = run(issues);
    final Augmentation.EmbeddingQuery q = near(r, "v", Variant.INNER);
    assertEquals(List.of("s", "a", "b"),
        q.nearest().stream().map(Augmentation.Neighbour::id).toList(),
        "a before b at the same distance, though b took longer");
    assertEquals(0.0, q.nearest().get(0).distance());
    assertEquals(0.4, q.nearest().get(1).distance(), 1e-6);
    assertEquals(3, q.candidates());
    assertEquals(3, q.used(5));
    assertEquals(1_000 * H, q.reference());
    assertEquals(Augmentation.WHOLE, q.block());
    for (final int n : Augmentation.EMBEDDING_N) {
      assertEquals(7.0, embedding(r, "v", n, Variant.INNER), "the median of 7, 1 and 100");
    }
  }

  /** An issue started at hour {@code start} that took {@code hours}. */
  private void issue(final String id, final String outer, final String inner, final long start,
      final long hours, final String description) {
    issues.add(new Augmentation.Issue(id, "p", "r", start * H, (start + hours) * H, outer, inner,
        description));
  }

  private void fit(final String id, final long start, final long hours, final String text) {
    issue(id, "TRAIN", "TRAIN", start, hours, text);
  }

  private void validation(final String id, final long start, final String text) {
    issue(id, "TRAIN", "TEST", start, 1, text);
  }

  private static Double jaccard(final Augmentation.Result r, final String id, final int n,
      final Variant v) {
    return r.columns().get(id).get(Augmentation.jaccard(n, v));
  }

  @Test
  @DisplayName("Jaccard: equal sets at distance 0, disjoint and empty ones never candidates; "
      + "tokens are lowercase runs of letters, digits and underscore")
  void jaccard() {
    fit("equal", 0, 1, "B a");
    fit("half", 0, 2, "b c");
    fit("disjoint", 0, 3, "c d");
    fit("empty", 0, 4, "!! --");
    fit("absent", 0, 5, null);
    fit("unicode", 0, 6, "Ação foo_bar(x2)");
    validation("q", 1_000, "a b");
    validation("u", 1_000, "AÇÃO -- foo_bar! x2");
    final Augmentation.Result r = run(issues);
    final Augmentation.Query q = query(r, "q", Variant.INNER);
    assertEquals(List.of(new Augmentation.Neighbour("equal", 0.0),
        new Augmentation.Neighbour("half", 1 - 1.0 / 3)), q.nearest());
    assertEquals(1.5, jaccard(r, "q", 5, Variant.INNER));
    assertEquals(List.of(new Augmentation.Neighbour("unicode", 0.0)),
        query(r, "u", Variant.INNER).nearest());
    assertEquals(3, query(r, "u", Variant.INNER).tokens());
  }

  @Test
  @DisplayName("embedding: missing with no description, no positive-similarity candidate, or an "
      + "invalid vector -- wrong dimension, not finite, not length one -- which is counted, never "
      + "a crash and never a candidate; the issue stays")
  void embeddingMissing() {
    vector("good", 1, 0, 0);
    vector("short", 1, 0);
    vector("nan", Float.NaN, 0, 0);
    vector("infinite", Float.POSITIVE_INFINITY, 0, 0);
    vector("long", 2, 0, 0);
    vector("almost", 1.00005f, 0, 0);
    vector("orthogonal", 0, 1, 0);
    vector("opposite", -1, 0, 0);
    fit("h", 0, 10, "good");
    for (final String bad : List.of("short", "nan", "infinite", "long")) {
      fit("bad-" + bad, 0, 1, bad);
    }
    fit("almost", 0, 30, "almost");
    validation("none", 1_000, null);
    validation("perpendicular", 1_000, "orthogonal");
    validation("against", 1_000, "opposite");
    validation("invalid", 1_000, "long");
    validation("ok", 1_000, "good");
    final Augmentation.Result r = run(issues);
    for (final String id : List.of("none", "perpendicular", "against", "invalid")) {
      assertTrue(r.columns().containsKey(id), id);
      for (final int n : Augmentation.EMBEDDING_N) {
        assertNull(embedding(r, id, n, Variant.INNER), id);
      }
      assertEquals(0, near(r, id, Variant.INNER).candidates(), id);
    }
    assertEquals(List.of("almost", "h"), near(r, "ok", Variant.INNER).nearest().stream()
        .map(Augmentation.Neighbour::id).toList(), "within the tolerance of length one");
    assertEquals(20.0, embedding(r, "ok", 5, Variant.INNER), "the invalid ones never count");
    final Map<String, String> problems = new HashMap<>();
    r.embedded().stream().filter(e -> e.problem() != null)
        .forEach(e -> problems.put(e.id(), e.problem()));
    assertEquals(java.util.Set.of("bad-short", "bad-nan", "bad-infinite", "bad-long", "invalid"),
        problems.keySet());
    assertTrue(problems.get("bad-short").contains("2 components"), problems.toString());
    assertTrue(r.embedded().stream().noneMatch(e -> e.id().equals("none")));
  }

  @Test
  @DisplayName("embedding: each described issue's chunk count is recorded, once, valid or not")
  void chunkCounts() {
    chunked("long", 3, 0, 1, 0);
    chunked("broken", 2, 0, 0, 0);
    fit("a", 0, 5, "long");
    fit("b", 0, 5, "broken");
    fit("c", 0, 5, null);
    validation("v", 100, "long");
    assertEquals(List.of(new Augmentation.Embedded("a", 3, null),
        new Augmentation.Embedded("b", 2, "length 0.0, not one"),
        new Augmentation.Embedded("v", 3, null)), run(issues).embedded());
  }

  @Test
  @DisplayName("ties in distance go by id, never by duration; fewer candidates than n uses all")
  void tiesById() {
    fit("g", 0, 7, "x");
    fit("a", 0, 100, "x y");
    fit("b", 0, 2, "x y");
    fit("c", 0, 3, "y x");
    fit("d", 0, 4, "x y");
    fit("e", 0, 5, "x y");
    fit("f", 0, 1, "x y");
    validation("q", 1_000, "X");
    final Augmentation.Result r = run(issues);
    // g is the closest (distance 0); a..d follow by id at distance 1/2, f is left out at n = 5
    assertEquals(4.0, jaccard(r, "q", 5, Variant.INNER));
    assertEquals(4.0, jaccard(r, "q", 10, Variant.INNER), "all seven: 1 2 3 4 5 7 100");
    assertEquals(4.0, jaccard(r, "q", 20, Variant.INNER));
    final Augmentation.Query q = r.queries().stream()
        .filter(x -> x.id().equals("q") && x.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(List.of("g", "a", "b", "c", "d", "e", "f"),
        q.nearest().stream().map(Augmentation.Neighbour::id).toList());
    assertEquals(0.0, q.nearest().get(0).distance());
    assertEquals(0.5, q.nearest().get(1).distance());
    assertEquals(7, q.candidates());
    assertEquals(1_000 * H, q.reference());
  }

  @Test
  @DisplayName("missing when the query has no tokens or no candidate shares one; the issue stays")
  void missing() {
    fit("a", 0, 10, "alpha beta");
    fit("b", 0, 20, "gamma");
    fit("c", 0, 30, null);
    validation("disjoint", 1_000, "delta");
    validation("empty", 1_000, "!! --");
    validation("absent", 1_000, null);
    validation("one", 1_000, "beta");
    final Augmentation.Result r = run(issues);
    for (final String id : List.of("disjoint", "empty", "absent")) {
      assertTrue(r.columns().containsKey(id), id);
      for (final int n : Augmentation.JACCARD_N) {
        assertNull(jaccard(r, id, n, Variant.INNER), id);
      }
    }
    assertEquals(10.0, jaccard(r, "one", 5, Variant.INNER), "fewer than n: the only one");
    assertTrue(r.columns().containsKey("c"));
  }

  private static Augmentation.Query query(final Augmentation.Result r, final String id,
      final Variant v) {
    return r.queries().stream().filter(x -> x.id().equals(id) && x.variant() == v).findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("five blocks, equal instants together, limits reported; the first block has no "
      + "history and stays missing")
  void blocks() {
    final long[] starts = {0, 0, 10, 10, 10, 20, 30, 40, 50, 60};
    for (int i = 0; i < starts.length; i++) {
      fit("f" + i, starts[i], 5, "x");
    }
    validation("v", 100, "x");
    final Augmentation.Result r = run(issues);
    final List<Augmentation.Block> inner =
        r.blocks().stream().filter(b -> b.variant() == Variant.INNER).toList();
    assertEquals(List.of(2, 3, 1, 2, 2), inner.stream().map(Augmentation.Block::rows).toList());
    assertEquals(List.of(0L, 10 * H, 20 * H, 30 * H, 50 * H),
        inner.stream().map(Augmentation.Block::begins).toList());
    assertEquals(List.of(0L, 10 * H, 20 * H, 40 * H, 60 * H),
        inner.stream().map(Augmentation.Block::last).toList());
    assertEquals(List.of(0, 2, 5, 6, 8), inner.stream().map(Augmentation.Block::history).toList());
    assertNull(jaccard(r, "f0", 5, Variant.INNER));
    assertNull(jaccard(r, "f1", 5, Variant.INNER));
    assertEquals(5.0, jaccard(r, "f2", 5, Variant.INNER));
    assertEquals("2", query(r, "f4", Variant.INNER).block());
    assertEquals(10 * H, query(r, "f4", Variant.INNER).reference());
    assertEquals(Augmentation.WHOLE, query(r, "v", Variant.INNER).block());
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.jaccard(5, Variant.INNER), "block",
            "inner_fitting 1", 2, 0)));
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.jaccard(5, Variant.INNER), "split",
            "inner_fitting", 10, 8)));
  }

  @Test
  @DisplayName("an issue crossing the inner cut enters an outer block's history only once "
      + "resolved, and never the inner history")
  void crossing() {
    for (int i = 0; i < 10; i++) {
      fit("f" + i, 10L * i, 5, "x");
      validation("v" + i, 100 + 10L * i, "x");
    }
    issue("c", "TRAIN", "CROSSES", 50, 95, "x");
    final Augmentation.Result r = run(issues);
    final List<Augmentation.Block> outer =
        r.blocks().stream().filter(b -> b.variant() == Variant.OUTER).toList();
    assertEquals(List.of(0L, 40 * H, 70 * H, 110 * H, 150 * H),
        outer.stream().map(Augmentation.Block::begins).toList());
    for (final Augmentation.Query q : r.queries()) {
      final boolean seen = q.nearest().stream().anyMatch(n -> n.id().equals("c"));
      final boolean resolved = q.variant() == Variant.OUTER && 145 * H < q.reference();
      assertEquals(resolved, seen, q.toString());
    }
    assertTrue(query(r, "v9", Variant.OUTER).nearest().stream().anyMatch(n -> n.id().equals("c")));
    assertNull(jaccard(r, "c", 5, Variant.INNER), "outside the inner sets");
    assertNotEquals(null, jaccard(r, "c", 5, Variant.OUTER));
  }

  /** An issue of {@code reporter} in {@code project} with no description. */
  private void by(final String id, final String project, final String reporter,
      final String outer, final String inner, final long start, final long hours) {
    issues.add(new Augmentation.Issue(id, project, reporter, start * H, (start + hours) * H, outer,
        inner, null));
  }

  private static Double reporter(final Augmentation.Result r, final String id, final Variant v) {
    return r.columns().get(id).get(Augmentation.reporter(v));
  }

  @Test
  @DisplayName("reporter median: only the same reporter and project, only the matching fitting set, "
      + "only issues resolved strictly before the start; validation and test outcomes never count")
  void reporterMedian() {
    by("a", "p", "r", "TRAIN", "TRAIN", 0, 10);
    by("b", "p", "r", "TRAIN", "TRAIN", 0, 30);
    by("other", "p", "s", "TRAIN", "TRAIN", 0, 3);
    by("elsewhere", "q", "r", "TRAIN", "TRAIN", 0, 3);
    by("late", "p", "r", "TRAIN", "TRAIN", 5, 35); // resolved at 40, the start of f
    by("c", "p", "r", "TRAIN", "CROSSES", 1, 23); // outer training, outside the inner sets
    by("f", "p", "r", "TRAIN", "TRAIN", 40, 2);
    by("v1", "p", "r", "TRAIN", "TEST", 100, 1);
    by("v2", "p", "r", "TRAIN", "TEST", 50, 4);
    by("t2", "p", "r", "TEST", "", 150, 1);
    by("t1", "p", "r", "TEST", "", 200, 1);
    final Augmentation.Result r = run(issues);
    assertEquals(20.0, reporter(r, "f", Variant.INNER), "a and b; late resolved at f's start");
    assertEquals(23.0, reporter(r, "f", Variant.OUTER), "a, b and the crossing c");
    assertEquals(20.0, reporter(r, "v1", Variant.INNER), "f, a, b, late; never v2");
    assertEquals(16.5, reporter(r, "v1", Variant.OUTER), "f, v2, a, c, b, late");
    assertEquals(10.0, reporter(r, "t1", Variant.OUTER), "the outer training; never t2");
    assertEquals(10.0, reporter(r, "t2", Variant.OUTER));
    assertNull(reporter(r, "t1", Variant.INNER), "outside the inner sets");
    assertNull(reporter(r, "c", Variant.INNER), "outside the inner sets");
    assertNull(reporter(r, "c", Variant.OUTER), "nothing resolved before it started");
    assertNull(reporter(r, "other", Variant.INNER), "the only issue of s");
  }

  @Test
  @DisplayName("reporter median: missing with no eligible issue and the row stays; the issue "
      + "itself never counts")
  void reporterMissing() {
    by("a", "p", "r", "TRAIN", "TRAIN", 0, 5);
    by("a", "p", "r", "TRAIN", "TEST", 100, 1);
    by("alone", "p", "s", "TRAIN", "TEST", 100, 1);
    final Augmentation.Result r = run(issues);
    assertNull(reporter(r, "a", Variant.INNER), "its own earlier row is not another issue");
    assertTrue(r.columns().containsKey("alone"));
    assertNull(reporter(r, "alone", Variant.INNER));
    assertNull(reporter(r, "alone", Variant.OUTER));
  }

  /** A random corpus over both splits: 60 fitting, 20 validation, 15 crossing, 30 test. */
  private void random(final long seed) {
    final Random rng = new Random(seed);
    final String[] words = {"npe", "build", "test", "docs", "leak", "race", "ui", "api"};
    final java.util.function.Supplier<String> text = () -> {
      if (rng.nextInt(10) == 0) {
        return null;
      }
      final StringBuilder b = new StringBuilder();
      for (int k = rng.nextInt(4); k >= 0; k--) {
        b.append(words[rng.nextInt(words.length)]).append(' ');
      }
      return b.toString();
    };
    for (int i = 0; i < 60; i++) {
      final long start = rng.nextInt(50) * 10L;
      fit("f" + i, start, 1 + rng.nextInt((int) (600 - start)), text.get());
    }
    for (int i = 0; i < 15; i++) {
      issue("c" + i, "TRAIN", "CROSSES", rng.nextInt(60) * 10L, 700 + rng.nextInt(300),
          text.get());
    }
    for (int i = 0; i < 20; i++) {
      final long start = 610 + rng.nextInt(300);
      issue("v" + i, "TRAIN", "TEST", start, 1 + rng.nextInt((int) (1990 - start)), text.get());
    }
    for (int i = 0; i < 30; i++) {
      issue("t" + i, "TEST", "", 2000 + rng.nextInt(500), 1 + rng.nextInt(900), text.get());
    }
  }

  @Test
  @DisplayName("no neighbour resolved at or after the reference instant, none from outside the "
      + "fitting set, and an issue never in its own history")
  void noLeak() {
    random(7);
    final Map<String, Augmentation.Issue> byId = new java.util.HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    int read = 0;
    for (final Augmentation.Query q : r.queries()) {
      assertTrue(q.reference() <= byId.get(q.id()).start(), q.toString());
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue(h.resolved() < q.reference(), q + " " + n);
        assertTrue(h.start() < q.reference(), q + " " + n);
        assertNotEquals(q.id(), n.id());
        assertTrue(q.variant() == Variant.INNER ? h.inner().equals("TRAIN")
            : h.outer().equals("TRAIN"), q + " " + n);
        read++;
      }
    }
    assertTrue(read > 500, "the corpus exercised the search: " + read);
  }

  @Test
  @DisplayName("embedding: no neighbour resolved at or after the reference instant, none from "
      + "outside the fitting set, and an issue never in its own history")
  void embeddingNoLeak() {
    random(7);
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    int read = 0;
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      assertTrue(q.reference() <= byId.get(q.id()).start(), q.toString());
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue(h.resolved() < q.reference(), q + " " + n);
        assertTrue(h.start() < q.reference(), q + " " + n);
        assertNotEquals(q.id(), n.id());
        assertTrue(q.variant() == Variant.INNER ? h.inner().equals("TRAIN")
            : h.outer().equals("TRAIN"), q + " " + n);
        assertTrue(n.distance() >= 0 && n.distance() < 1, n.toString());
        read++;
      }
    }
    assertTrue(read > 500, "the corpus exercised the search: " + read);
    assertEquals(r.queries().size(), r.embeddingQueries().size(), "one search per row and variant");
  }

  @Test
  @DisplayName("embedding: the same blocks and reference instants as Jaccard; the first block has "
      + "no history and stays missing; a crossing issue enters an outer block only once resolved")
  void embeddingBlocks() {
    for (int i = 0; i < 10; i++) {
      fit("f" + i, 10L * i, 5, "x");
      validation("v" + i, 100 + 10L * i, "x");
    }
    issue("c", "TRAIN", "CROSSES", 50, 95, "x");
    final Augmentation.Result r = run(issues);
    for (final Augmentation.Query q : r.queries()) {
      final Augmentation.EmbeddingQuery e = near(r, q.id(), q.variant());
      assertEquals(q.block(), e.block(), q.id());
      assertEquals(q.reference(), e.reference(), q.id());
      assertEquals(q.split(), e.split(), q.id());
    }
    assertNull(embedding(r, "f0", 5, Variant.INNER));
    assertNull(embedding(r, "f1", 5, Variant.INNER));
    assertEquals(5.0, embedding(r, "f2", 5, Variant.INNER));
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      final boolean seen = q.nearest().stream().anyMatch(n -> n.id().equals("c"));
      assertEquals(q.variant() == Variant.OUTER && 145 * H < q.reference(), seen, q.toString());
    }
    assertNull(embedding(r, "c", 5, Variant.INNER), "outside the inner sets");
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.embedding(5, Variant.INNER), "block",
            "inner_fitting 1", 2, 0)));
  }

  @Test
  @DisplayName("embedding: an issue in its own history set, at distance zero from itself, is "
      + "still never its own neighbour")
  void embeddingNeverItself() {
    fit("a", 0, 5, "x");
    fit("b", 0, 7, "y");
    vector("x", 1, 0, 0);
    vector("y", 0.6f, 0.8f, 0);
    validation("a", 100, "x");
    final Augmentation.EmbeddingQuery q = run(issues).embeddingQueries().stream()
        .filter(x -> x.id().equals("a") && x.block().equals(Augmentation.WHOLE)
            && x.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(List.of("b"), q.nearest().stream().map(Augmentation.Neighbour::id).toList(),
        "the validation row of a reads the fitting a and b; never a");
  }

  @Test
  @DisplayName("an issue in its own history set is still never its own neighbour")
  void neverItself() {
    fit("a", 0, 5, "x");
    fit("b", 0, 5, "x");
    validation("a", 100, "x");
    assertFalse(query(run(issues), "a", Variant.INNER).nearest().stream()
        .anyMatch(n -> n.id().equals("a")));
  }

  @Test
  @DisplayName("adding a future issue, or changing a later duration, leaves earlier rows unchanged")
  void future() {
    final long[] starts = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90};
    for (int i = 0; i < starts.length; i++) {
      fit("f" + i, starts[i], 5 + i, "x y");
    }
    issue("t", "TEST", "", 200, 1, "x");
    final Map<String, Map<String, Double>> before = run(issues).columns();
    // both fitting sets are the same ten rows; eleven split into blocks at the same places as ten: 2, 4, 6, 8
    fit("late", 95, 1, "x");
    final Map<String, Map<String, Double>> added = run(issues).columns();
    issues.set(9, new Augmentation.Issue("f9", "p", "r", 90 * H, 91 * H, "TRAIN", "TRAIN", "x y"));
    final Map<String, Map<String, Double>> changed = run(issues).columns();
    for (int i = 0; i < 10; i++) {
      assertEquals(before.get("f" + i), added.get("f" + i), "f" + i);
      assertEquals(before.get("f" + i), changed.get("f" + i), "f" + i);
    }
    assertNotEquals(before.get("t"), changed.get("t"), "the test row reads them all");
    assertTrue(before.get("f9").containsKey(Augmentation.embedding(5, Variant.INNER)),
        "the embedding columns are among those compared");
  }

  /** The issues with each scored row of {@code part} resolved {@code hours} later. */
  private List<Augmentation.Issue> later(final String part, final long hours) {
    return issues.stream().map(i -> (part.equals("TEST") ? i.outer() : i.inner()).equals("TEST")
        ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
            i.resolved() + hours * H, i.outer(), i.inner(), i.description()) : i).toList();
  }

  @Test
  @DisplayName("changing a validation duration changes no inner column; changing a test duration "
      + "changes no column at all")
  void scoredOutcomes() {
    random(11);
    final Map<String, Map<String, Double>> before = run(issues).columns();
    final Map<String, Map<String, Double>> validation =
        run(later("VALIDATION", 50)).columns();
    boolean outerMoved = false;
    for (final String id : before.keySet()) {
      for (final String column : Augmentation.columns()) {
        final Double was = before.get(id).get(column);
        final Double is = validation.get(id).get(column);
        if (column.endsWith("_inner")) {
          assertEquals(was, is, id + " " + column);
        } else {
          outerMoved |= !java.util.Objects.equals(was, is);
        }
      }
    }
    assertTrue(outerMoved, "the validation rows are outer training: the outer columns may move");
    assertEquals(before, run(later("TEST", 5_000)).columns());
  }

  // ---------------------------------------------------------------- clusters

  private static Double cluster(final Augmentation.Result r, final String id, final int k,
      final Variant v) {
    return r.columns().get(id).get(Augmentation.cluster(k, v));
  }

  private static Augmentation.ClusterQuery assignment(final Augmentation.Result r,
      final String id, final int k, final Variant v) {
    return r.clusterQueries().stream().filter(x -> x.id().equals(id) && x.k() == k
        && x.variant() == v).findFirst().orElseThrow();
  }

  /** The description {@code text} embeds as {@code v} brought to length one. */
  private void unit(final String text, final double... v) {
    final double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
    final float[] out = new float[v.length];
    for (int d = 0; d < v.length; d++) {
      out[d] = (float) (v[d] / norm);
    }
    vector(text, out);
  }

  /** The direction of octant {@code g}: each component plus or minus one by a bit of g. */
  private static double[] octant(final int g, final double jitter) {
    return new double[] {(g & 1) == 0 ? 1 : -1, ((g & 2) == 0 ? 1 : -1) + jitter,
        (g & 4) == 0 ? 1 : -1};
  }

  /**
   * Eight tight groups of four fitting issues, one per octant, all started at hour 0 so the
   * fitting rows have no history; group g's issues took 10(g+1) to 10(g+1)+3 hours. The
   * validation row q{g} lies exactly on group g's first issue, and took 5,000 hours.
   */
  private void octants() {
    for (int g = 0; g < 8; g++) {
      for (int j = 0; j < 4; j++) {
        unit("g" + g + "." + j, octant(g, 0.02 * j));
        fit("g" + g + "." + j, 0, 10L * (g + 1) + j, "g" + g + "." + j);
      }
      issue("q" + g, "TRAIN", "TEST", 1_000, 5_000, "g" + g + ".0");
    }
  }

  @Test
  @DisplayName("cluster: the median duration of the historical members of the query's nearest "
      + "centre, never a centre's coordinate and never a validation outcome")
  void clusterMedian() {
    octants();
    final Augmentation.Result r = run(issues);
    final List<Double> durations = new ArrayList<>();
    for (int g = 0; g < 8; g++) {
      for (int j = 0; j < 4; j++) {
        durations.add(10.0 * (g + 1) + j);
      }
    }
    for (int g = 0; g < 8; g++) {
      final String q = "q" + g;
      assertEquals(10.0 * (g + 1) + 1.5, cluster(r, q, 8, Variant.INNER),
          "group " + g + ": the median of its four");
      assertEquals(10.0 * (g + 1), cluster(r, q, 32, Variant.INNER),
          "32 clusters of 32 vectors: each its own, the query on the first of its group");
      assertEquals(0.0, assignment(r, q, 32, Variant.INNER).distance(), 1e-6);
      final double sixteen = cluster(r, q, 16, Variant.INNER);
      assertTrue(sixteen >= 10.0 * (g + 1) && sixteen <= 10.0 * (g + 1) + 3,
          "within group " + g + ": " + sixteen);
      for (final int k : Augmentation.CLUSTER_K) {
        final double value = cluster(r, q, k, Variant.INNER);
        boolean median = false;
        for (final double a : durations) {
          for (final double b : durations) {
            median |= value == a || value == (a + b) / 2;
          }
        }
        assertTrue(median, q + " k=" + k + ": " + value + " is no member's duration");
      }
    }
    final Augmentation.ClusterFit whole = r.clusterFits().stream()
        .filter(f -> f.variant() == Variant.INNER && f.block().equals(Augmentation.WHOLE)
            && f.k() == 8).findFirst().orElseThrow();
    assertTrue(whole.fitted());
    assertEquals(32, whole.vectors());
    assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 4),
        whole.members().stream().map(List::size).toList());
    for (int g = 0; g < 8; g++) {
      assertNull(cluster(r, "g" + g + ".0", 8, Variant.INNER), "the fitting rows have no history");
    }
  }

  @Test
  @DisplayName("cluster: a history with fewer distinct vectors than k leaves that k missing for "
      + "the whole block, k unchanged; the other columns are untouched")
  void clusterTooFew() {
    for (int i = 0; i < 12; i++) {
      unit("d" + i, Math.cos(i % 10), Math.sin(i % 10), 0.5);
      fit("f" + i, 0, 1 + i, "d" + i);
    }
    validation("v", 1_000, "d3");
    validation("w", 1_000, "d7");
    final Augmentation.Result r = run(issues);
    for (final String q : List.of("v", "w")) {
      assertTrue(cluster(r, q, 8, Variant.INNER) != null, "ten distinct vectors: 8 clusters");
      assertNull(cluster(r, q, 16, Variant.INNER), q);
      assertNull(cluster(r, q, 32, Variant.INNER), q);
      assertTrue(embedding(r, q, 5, Variant.INNER) != null);
      assertEquals(-1, assignment(r, q, 16, Variant.INNER).cluster());
    }
    final List<Augmentation.ClusterFit> whole = r.clusterFits().stream()
        .filter(f -> f.variant() == Variant.INNER && f.block().equals(Augmentation.WHOLE))
        .toList();
    assertEquals(List.of(8, 16, 32), whole.stream().map(Augmentation.ClusterFit::k).toList());
    assertEquals(List.of(true, false, false),
        whole.stream().map(Augmentation.ClusterFit::fitted).toList());
    assertEquals(List.of(10, 10, 10),
        whole.stream().map(Augmentation.ClusterFit::distinct).toList());
    assertEquals(List.of(12, 12, 12),
        whole.stream().map(Augmentation.ClusterFit::vectors).toList());
    assertTrue(Double.isNaN(whole.get(1).distortion()));
    assertTrue(whole.get(0).distortion() >= 0);
  }

  @Test
  @DisplayName("cluster: missing when the query has no valid vector, or its cluster has no "
      + "historical member but the issue itself, which is never its own member")
  void clusterMissing() {
    for (int g = 0; g < 8; g++) {
      unit("o" + g, octant(g, 0));
      fit("f" + g, 0, 10, "o" + g);
    }
    vector("long", 2, 0, 0);
    validation("f7", 1_000, "o7");
    validation("none", 1_000, null);
    validation("invalid", 1_000, "long");
    validation("ok", 1_000, "o0");
    final Augmentation.Result r = run(issues);
    assertEquals(10.0, cluster(r, "ok", 8, Variant.INNER));
    assertEquals(1, assignment(r, "ok", 8, Variant.INNER).members());
    assertNull(cluster(r, "f7", 8, Variant.INNER),
        "f7's validation row lands on the fitting f7, alone in its cluster");
    assertEquals(0, assignment(r, "f7", 8, Variant.INNER).members());
    for (final String id : List.of("none", "invalid")) {
      assertNull(cluster(r, id, 8, Variant.INNER), id);
      assertEquals(-1, assignment(r, id, 8, Variant.INNER).cluster(), id);
      assertTrue(r.columns().containsKey(id));
    }
  }

  /** A random unit vector of the test's dimension for every text of the rows. */
  private void randomVectors(final long seed) {
    final Random rng = new Random(seed);
    for (final Augmentation.Issue i : issues) {
      if (i.description() != null && !hand.containsKey(i.description())) {
        unit(i.description(), rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
      }
    }
  }

  @Test
  @DisplayName("cluster: no member of the query's cluster resolved at or after the reference "
      + "instant, none from outside the fitting set, never the query; the same blocks, reference "
      + "instants and splits as Jaccard")
  void clusterNoLeak() {
    random(7);
    randomVectors(3);
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    final Map<String, Augmentation.ClusterFit> fits = new HashMap<>();
    r.clusterFits().forEach(f -> fits.put(f.variant() + " " + f.block() + " " + f.k(), f));
    int read = 0;
    for (final Augmentation.ClusterQuery q : r.clusterQueries()) {
      final Augmentation.Query jaccard = query(r, q.id(), q.variant());
      assertEquals(jaccard.block(), q.block(), q.id());
      assertEquals(jaccard.reference(), q.reference(), q.id());
      assertEquals(jaccard.split(), q.split(), q.id());
      if (q.cluster() < 0) {
        continue;
      }
      final Augmentation.ClusterFit f = fits.get(q.variant() + " " + q.block() + " " + q.k());
      int used = 0;
      for (final String m : f.members().get(q.cluster())) {
        final Augmentation.Issue h = byId.get(m);
        assertTrue(h.resolved() < q.reference(), q + " " + m);
        assertTrue(q.variant() == Variant.INNER ? h.inner().equals("TRAIN")
            : h.outer().equals("TRAIN"), q + " " + m);
        used += m.equals(q.id()) ? 0 : 1;
        read++;
      }
      assertEquals(used, q.members(), q.toString());
    }
    assertTrue(read > 500, "the corpus exercised the clusters: " + read);
    assertTrue(r.clusterFits().stream().anyMatch(f -> f.fitted() && f.k() == 32));
    for (final Augmentation.Block b : r.blocks()) {
      for (final int k : Augmentation.CLUSTER_K) {
        final Augmentation.ClusterFit f = fits.get(b.variant() + " " + b.index() + " " + k);
        assertTrue(f.vectors() <= b.history(), f.toString());
      }
    }
  }

  @Test
  @DisplayName("cluster: an issue crossing the inner cut enters an outer block's fit only once "
      + "resolved, and never an inner one; the first block has no history and stays missing")
  void clusterCrossing() {
    for (int i = 0; i < 10; i++) {
      unit("d" + i, Math.cos(i), Math.sin(i), 0.3);
      unit("e" + i, Math.cos(i + 0.5), Math.sin(i + 0.5), -0.3);
      fit("f" + i, 10L * i, 5, "d" + i);
      validation("v" + i, 100 + 10L * i, "e" + i);
    }
    unit("c", 0, 0, 1);
    issue("c", "TRAIN", "CROSSES", 50, 95, "c");
    final Augmentation.Result r = run(issues);
    for (final Augmentation.ClusterFit f : r.clusterFits()) {
      final boolean in = f.members().stream().anyMatch(m -> m.contains("c"));
      final long begins = f.block().equals(Augmentation.WHOLE) ? Long.MAX_VALUE
          : r.blocks().stream().filter(b -> b.variant() == f.variant()
              && Integer.toString(b.index()).equals(f.block())).findFirst().orElseThrow()
              .begins();
      if (f.fitted()) {
        assertEquals(f.variant() == Variant.OUTER && 145 * H < begins, in, f.toString());
      }
    }
    assertTrue(r.clusterFits().stream().anyMatch(f -> f.fitted() && f.variant() == Variant.OUTER
        && !f.block().equals(Augmentation.WHOLE)), "an outer block was fitted");
    assertNull(cluster(r, "f0", 8, Variant.INNER));
    assertNull(cluster(r, "c", 8, Variant.INNER), "outside the inner sets");
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.cluster(8, Variant.INNER), "block",
            "inner_fitting 1", 2, 0)));
  }

  @Test
  @DisplayName("cluster: adding a future issue, or changing a later duration, leaves the cluster "
      + "columns of earlier rows unchanged")
  void clusterFuture() {
    for (int i = 0; i < 40; i++) {
      unit("d" + i, Math.cos(i), Math.sin(i), Math.cos(3 * i));
      fit("f" + i, 10L * i, 5 + i % 7, "d" + i);
    }
    issue("t", "TEST", "", 1_000, 1, "d3");
    final Map<String, Map<String, Double>> before = run(issues).columns();
    // forty-one rows split into blocks at the same places as forty: 8, 16, 24, 32
    unit("late", 1, 1, 1);
    fit("late", 395, 1, "late");
    final Map<String, Map<String, Double>> added = run(issues).columns();
    issues.set(39, new Augmentation.Issue("f39", "p", "r", 390 * H, 391 * H, "TRAIN", "TRAIN",
        "d39"));
    final Map<String, Map<String, Double>> changed = run(issues).columns();
    for (int i = 0; i < 40; i++) {
      assertEquals(before.get("f" + i), added.get("f" + i), "f" + i);
      assertEquals(before.get("f" + i), changed.get("f" + i), "f" + i);
    }
    assertTrue(before.get("f39").containsKey(Augmentation.cluster(16, Variant.INNER)),
        "the cluster columns are among those compared");
    assertNotEquals(before.get("t"), changed.get("t"), "the test row reads them all");
  }

  @Test
  @DisplayName("cluster: changing a validation duration changes no inner cluster column; changing "
      + "a test duration changes none at all")
  void clusterScoredOutcomes() {
    random(11);
    randomVectors(5);
    final Map<String, Map<String, Double>> before = run(issues).columns();
    final Map<String, Map<String, Double>> validation = run(later("VALIDATION", 50)).columns();
    int observed = 0;
    for (final String id : before.keySet()) {
      for (final int k : Augmentation.CLUSTER_K) {
        final String column = Augmentation.cluster(k, Variant.INNER);
        assertEquals(before.get(id).get(column), validation.get(id).get(column), id + column);
        observed += before.get(id).containsKey(column) ? 1 : 0;
      }
    }
    assertTrue(observed > 50, "the inner cluster columns were exercised: " + observed);
    assertEquals(before, run(later("TEST", 5_000)).columns());
  }
}
