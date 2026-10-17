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
    assertEquals("inner_validation", q.split());
    for (final int n : Augmentation.EMBEDDING_N) {
      assertEquals(7.0, embedding(r, "v", n, Variant.INNER), "the median of 7, 1 and 100");
    }
  }

  /**
   * An issue started at hour {@code start} that took {@code hours}, and as many business days: a
   * median reads the business days, the history the instants.
   */
  private void issue(final String id, final String outer, final String inner, final long start,
      final long hours, final String description) {
    issues.add(new Augmentation.Issue(id, "p", "r", start * H, (start + hours) * H, (int) hours,
        outer, inner, description));
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
  @DisplayName("one history per variant, the whole training side whatever the dates: a fitting row "
      + "reads issues resolved after its own start, never itself; each split's coverage reported")
  void wholeTrainingSide() {
    final long[] starts = {0, 0, 10, 10, 10, 20, 30, 40, 50, 60};
    for (int i = 0; i < starts.length; i++) {
      fit("f" + i, starts[i], 5 + i, "x");
    }
    validation("v", 100, "x");
    final Augmentation.Result r = run(issues);
    assertEquals(List.of(new Augmentation.History(Variant.INNER, 11, 10),
        new Augmentation.History(Variant.OUTER, 11, 11)), r.histories());
    // f0 started first: every other fitting issue was resolved after its start, and all are read
    final Augmentation.Query first = query(r, "f0", Variant.INNER);
    assertEquals(9, first.candidates(), "the nine others, never f0 itself");
    assertTrue(first.nearest().stream().noneMatch(n -> n.id().equals("f0")));
    assertEquals(10.0, jaccard(r, "f0", 20, Variant.INNER), "6 to 14, f0's own 5 left out");
    assertEquals(8.0, jaccard(r, "f0", 5, Variant.INNER), "f1 to f5 by id at distance 0");
    // the outer variant's history is the outer training: the validation row v among it
    assertTrue(query(r, "f0", Variant.OUTER).nearest().stream().anyMatch(n -> n.id().equals("v")));
    assertTrue(query(r, "f0", Variant.INNER).nearest().stream().noneMatch(n -> n.id().equals("v")),
        "never a validation label in the inner variant");
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.jaccard(5, Variant.INNER), "split",
            "inner_fitting", 10, 10)));
    assertTrue(r.coverage().stream().noneMatch(c -> c.by().equals("block")));
  }

  /** An issue of {@code reporter} in {@code project} with no description. */
  private void by(final String id, final String project, final String reporter,
      final String outer, final String inner, final long start, final long hours) {
    issues.add(new Augmentation.Issue(id, project, reporter, start * H, (start + hours) * H,
        (int) hours, outer, inner, null));
  }

  private static Double reporter(final Augmentation.Result r, final String id, final Variant v) {
    return r.columns().get(id).get(Augmentation.reporter(v));
  }

  @Test
  @DisplayName("reporter median: only the same reporter and project, only the matching training "
      + "side, whatever the dates, the issue itself left out; validation and test outcomes never "
      + "count")
  void reporterMedian() {
    by("a", "p", "r", "TRAIN", "TRAIN", 0, 10);
    by("b", "p", "r", "TRAIN", "TRAIN", 0, 30);
    by("other", "p", "s", "TRAIN", "TRAIN", 0, 3);
    by("elsewhere", "q", "r", "TRAIN", "TRAIN", 0, 3);
    by("late", "p", "r", "TRAIN", "TRAIN", 5, 35); // resolved at 40, the start of f
    by("f", "p", "r", "TRAIN", "TRAIN", 40, 2);
    by("v1", "p", "r", "TRAIN", "TEST", 100, 1);
    by("v2", "p", "r", "TRAIN", "TEST", 50, 4);
    by("t2", "p", "r", "TEST", "", 150, 1);
    by("t1", "p", "r", "TEST", "", 200, 1);
    final Augmentation.Result r = run(issues);
    assertEquals(30.0, reporter(r, "f", Variant.INNER), "a, b and late, resolved at f's start");
    assertEquals(30.0, reporter(r, "a", Variant.INNER),
        "b, late and f, all resolved after a began");
    assertEquals(10.0, reporter(r, "f", Variant.OUTER), "a, b, late, v1 and v2");
    assertEquals(20.0, reporter(r, "v1", Variant.INNER), "f, a, b, late; never v2");
    assertEquals(10.0, reporter(r, "v1", Variant.OUTER), "f, v2, a, b, late; never itself");
    assertEquals(7.0, reporter(r, "t1", Variant.OUTER), "the outer training; never t2");
    assertEquals(7.0, reporter(r, "t2", Variant.OUTER));
    assertNull(reporter(r, "t1", Variant.INNER), "outside the inner sets");
    assertNull(reporter(r, "other", Variant.INNER), "the only issue of s");
    assertNull(reporter(r, "elsewhere", Variant.INNER), "the only issue of r in q");
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

  /**
   * A random corpus over both splits, the sides drawn at random as the split is, so no instant
   * separates them: 60 fitting, 20 validation, 30 test.
   */
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
      fit("f" + i, rng.nextInt(250) * 10L, 1 + rng.nextInt(900), text.get());
    }
    for (int i = 0; i < 20; i++) {
      issue("v" + i, "TRAIN", "TEST", rng.nextInt(250) * 10L, 1 + rng.nextInt(900), text.get());
    }
    for (int i = 0; i < 30; i++) {
      issue("t" + i, "TEST", "", rng.nextInt(250) * 10L, 1 + rng.nextInt(900), text.get());
    }
  }

  @Test
  @DisplayName("no neighbour from outside the training side -- never a validation label in the "
      + "inner variant, never a test label in either -- and an issue never in its own history; "
      + "training issues resolved after the row's start do enter it")
  void noLeak() {
    random(7);
    final Map<String, Augmentation.Issue> byId = new java.util.HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    int read = 0;
    int after = 0;
    for (final Augmentation.Query q : r.queries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertNotEquals(q.id(), n.id());
        assertTrue(q.variant() == Variant.INNER ? training(h) && h.inner().equals("TRAIN")
            : training(h), q + " " + n);
        after += h.resolved() >= byId.get(q.id()).start() ? 1 : 0;
        read++;
      }
    }
    assertTrue(read > 500, "the corpus exercised the search: " + read);
    assertTrue(after > 100, "issues resolved after the row's start were read: " + after);
  }

  private static boolean training(final Augmentation.Issue h) {
    return h.outer().equals("TRAIN");
  }

  @Test
  @DisplayName("embedding: no neighbour from outside the training side, and an issue never in its "
      + "own history; training issues resolved after the row's start do enter it")
  void embeddingNoLeak() {
    random(7);
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    int read = 0;
    int after = 0;
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertNotEquals(q.id(), n.id());
        assertTrue(q.variant() == Variant.INNER ? training(h) && h.inner().equals("TRAIN")
            : training(h), q + " " + n);
        assertTrue(n.distance() >= 0 && n.distance() < 1, n.toString());
        after += h.resolved() >= byId.get(q.id()).start() ? 1 : 0;
        read++;
      }
    }
    assertTrue(read > 500, "the corpus exercised the search: " + read);
    assertTrue(after > 100, "issues resolved after the row's start were read: " + after);
    assertEquals(r.queries().size(), r.embeddingQueries().size(), "one search per row and variant");
  }

  @Test
  @DisplayName("embedding: the same rows, splits and history as Jaccard; a fitting row reads the "
      + "whole fitting set, never itself, and never a validation label in the inner variant")
  void embeddingHistory() {
    for (int i = 0; i < 10; i++) {
      fit("f" + i, 10L * i, 5, "x");
      validation("v" + i, 10L * i, "x");
    }
    final Augmentation.Result r = run(issues);
    assertEquals(r.queries().size(), r.embeddingQueries().size());
    for (int k = 0; k < r.queries().size(); k++) {
      final Augmentation.Query q = r.queries().get(k);
      final Augmentation.EmbeddingQuery e = r.embeddingQueries().get(k);
      assertEquals(q.id(), e.id());
      assertEquals(q.variant(), e.variant());
      assertEquals(q.split(), e.split(), q.id());
    }
    assertEquals(5.0, embedding(r, "f0", 5, Variant.INNER), "the nine later fitting issues");
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      assertTrue(q.nearest().stream().noneMatch(n -> n.id().equals(q.id())), q.toString());
      if (q.variant() == Variant.INNER) {
        assertTrue(q.nearest().stream().allMatch(n -> n.id().startsWith("f")), q.toString());
      }
    }
    assertEquals(19, near(r, "f0", Variant.OUTER).candidates(),
        "the outer training: the other nine fitting and the ten validation rows");
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
        .filter(x -> x.id().equals("a") && x.split().equals("inner_validation")
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
  @DisplayName("a fitting row reads the whole training side: the duration of an issue started "
      + "and resolved after it moves its Jaccard, embedding and cluster medians")
  void laterTrainingIssues() {
    // f0, then f1 started after f0 was resolved, both described alike; the others share no word
    // with them and point away from them, so f1 is f0's only Jaccard and embedding candidate
    unit("a", 1, 0, 0);
    fit("f0", 0, 5, "a");
    fit("f1", 10, 7, "a");
    for (int i = 0; i < 34; i++) {
      unit("o" + i, -1 - i % 3, Math.cos(i), Math.sin(i));
      fit("o" + i, 20, 3, "o" + i);
    }
    issue("t", "TEST", "", 1_000, 1, "a");
    final Map<String, Map<String, Double>> before = run(issues).columns();
    issues.set(1, new Augmentation.Issue("f1", "p", "r", 10 * H, 17 * H, 9, "TRAIN", "TRAIN",
        "a"));
    final Map<String, Map<String, Double>> changed = run(issues).columns();
    assertEquals(7.0, before.get("f0").get(Augmentation.jaccard(5, Variant.INNER)));
    assertEquals(9.0, changed.get("f0").get(Augmentation.jaccard(5, Variant.INNER)));
    assertEquals(9.0, changed.get("f0").get(Augmentation.embedding(5, Variant.INNER)));
    final String cluster = Augmentation.cluster(32, Variant.INNER);
    assertNotEquals(before.get("f0").get(cluster), changed.get("f0").get(cluster),
        "f1, at f0's very vector, is in f0's cluster");
    assertNotEquals(before.get("t"), changed.get("t"), "the test row reads them all");
  }

  /** The issues with each scored row of {@code part} resolved {@code hours} later. */
  private List<Augmentation.Issue> later(final String part, final long hours) {
    return issues.stream().map(i -> (part.equals("TEST") ? i.outer() : i.inner()).equals("TEST")
        ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
            i.resolved() + hours * H, i.businessDays() + (int) hours, i.outer(), i.inner(),
            i.description()) : i).toList();
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
        .filter(f -> f.variant() == Variant.INNER && f.k() == 8).findFirst().orElseThrow();
    assertTrue(whole.fitted());
    assertEquals(32, whole.vectors());
    assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 4),
        whole.members().stream().map(List::size).toList());
    for (int g = 0; g < 8; g++) {
      assertEquals(10.0 * (g + 1) + 2, cluster(r, "g" + g + ".0", 8, Variant.INNER),
          "a fitting row reads the same fit, the other three of its group, itself left out");
    }
  }

  @Test
  @DisplayName("cluster: a history with fewer distinct vectors than k leaves that k missing for "
      + "every row of the variant, k unchanged; the other columns are untouched")
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
        .filter(f -> f.variant() == Variant.INNER).toList();
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
  @DisplayName("cluster: one fit per variant and k, read by every row of the variant; no member "
      + "of the query's cluster from outside the training side, never the query; training "
      + "issues resolved after the row's start among them; the same splits as Jaccard")
  void clusterNoLeak() {
    random(7);
    randomVectors(3);
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(issues);
    assertEquals(2 * Augmentation.CLUSTER_K.size(), r.clusterFits().size());
    final Map<String, Augmentation.ClusterFit> fits = new HashMap<>();
    r.clusterFits().forEach(f -> fits.put(f.variant() + " " + f.k(), f));
    int read = 0;
    int after = 0;
    for (final Augmentation.ClusterQuery q : r.clusterQueries()) {
      final Augmentation.Query jaccard = query(r, q.id(), q.variant());
      assertEquals(jaccard.split(), q.split(), q.id());
      if (q.cluster() < 0) {
        continue;
      }
      final Augmentation.ClusterFit f = fits.get(q.variant() + " " + q.k());
      int used = 0;
      for (final String m : f.members().get(q.cluster())) {
        final Augmentation.Issue h = byId.get(m);
        assertTrue(q.variant() == Variant.INNER ? training(h) && h.inner().equals("TRAIN")
            : training(h), q + " " + m);
        after += h.resolved() >= byId.get(q.id()).start() ? 1 : 0;
        used += m.equals(q.id()) ? 0 : 1;
        read++;
      }
      assertEquals(used, q.members(), q.toString());
    }
    assertTrue(read > 500, "the corpus exercised the clusters: " + read);
    assertTrue(after > 100, "members resolved after the row's start were read: " + after);
    assertTrue(r.clusterFits().stream().anyMatch(f -> f.fitted() && f.k() == 32));
    for (final Augmentation.History h : r.histories()) {
      for (final int k : Augmentation.CLUSTER_K) {
        assertTrue(fits.get(h.variant() + " " + k).vectors() <= h.history());
      }
    }
  }

  @Test
  @DisplayName("cluster: the inner fit holds the inner fitting set only, never a validation issue; "
      + "the outer fit the outer training, validation included; no fit a test issue")
  void clusterSides() {
    for (int i = 0; i < 10; i++) {
      unit("d" + i, Math.cos(i), Math.sin(i), 0.3);
      unit("e" + i, Math.cos(i + 0.5), Math.sin(i + 0.5), -0.3);
      unit("t" + i, Math.cos(i + 0.25), Math.sin(i + 0.25), 0);
      fit("f" + i, 10L * i, 5, "d" + i);
      validation("v" + i, 10L * i, "e" + i);
      issue("t" + i, "TEST", "", 10L * i, 7, "t" + i);
    }
    final Augmentation.Result r = run(issues);
    assertEquals(List.of(true, false, false, true, true, false), r.clusterFits().stream()
        .map(Augmentation.ClusterFit::fitted).toList(), "ten inner vectors, twenty outer");
    for (final Augmentation.ClusterFit f : r.clusterFits()) {
      if (!f.fitted()) {
        continue;
      }
      final List<String> members = f.members().stream().flatMap(List::stream).toList();
      assertEquals(f.variant() == Variant.INNER ? 10 : 20, members.size(), f.toString());
      assertTrue(members.stream().noneMatch(m -> m.startsWith("t")), f.toString());
      assertEquals(f.variant() == Variant.OUTER, members.stream().anyMatch(m -> m.startsWith("v")),
          f.toString());
    }
    assertTrue(cluster(r, "f0", 8, Variant.INNER) != null, "a fitting row has a history");
    assertNull(cluster(r, "t0", 8, Variant.INNER), "outside the inner sets");
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

  @Test
  @DisplayName("business days: every median reads past issues' business days, and a past issue of "
      + "0 business days enters no history -- no neighbour, no cluster fit or member, no reporter "
      + "median -- though it still gets columns of its own")
  void zeroBusinessDays() {
    for (int i = 0; i < 40; i++) {
      unit("d" + i + " shared", Math.cos(i), Math.sin(i), Math.cos(3 * i));
      // every fifth issue lasted no business day
      fit("f" + i, 10L * i, i % 5 == 0 ? 0 : 1 + i % 7, "d" + i + " shared");
    }
    unit("q shared", 1, 0, 0);
    validation("v", 1_000, "q shared");
    issue("t", "TEST", "", 2_000, 1, "q shared");
    final Augmentation.Result r = run(issues);
    final java.util.Set<String> zero = new java.util.HashSet<>();
    for (int i = 0; i < 40; i += 5) {
      zero.add("f" + i);
    }
    for (final Augmentation.Query q : r.queries()) {
      assertTrue(q.nearest().stream().noneMatch(n -> zero.contains(n.id())), q.toString());
    }
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      assertTrue(q.nearest().stream().noneMatch(n -> zero.contains(n.id())), q.toString());
    }
    int fitted = 0;
    for (final Augmentation.ClusterFit f : r.clusterFits()) {
      assertTrue(f.members().stream().flatMap(List::stream).noneMatch(zero::contains),
          f.toString());
      fitted += f.fitted() ? 1 : 0;
    }
    assertTrue(fitted > 0, "the clusters were exercised");
    final Augmentation.ClusterFit whole = r.clusterFits().stream()
        .filter(f -> f.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(32, whole.vectors(), "the forty fitting issues but the eight of 0");
    // the reporter's every fitting issue but the zeros: 1 + i % 7 over the 32, median 4
    assertEquals(4.0, reporter(r, "v", Variant.INNER));
    assertEquals(4.0, reporter(r, "t", Variant.OUTER));
    assertTrue(r.columns().get("f35").containsKey(Augmentation.jaccard(5, Variant.INNER)),
        "an issue of 0 business days is still a row with columns of its own");
  }
}
