package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import app.pipeline.clustering.SmileKMeans;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    return Augmentation.run(rows, fake, DIMENSION, new SmileKMeans(),
        Augmentation.Histories.POINT_IN_TIME);
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
    issues.add(new Augmentation.Issue(id, "p", "r", start * H, (start + hours) * H, (int) hours, (int) hours,
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

  /** An issue of {@code reporter} in {@code project} with no description. */
  private void by(final String id, final String project, final String reporter,
      final String outer, final String inner, final long start, final long hours) {
    issues.add(new Augmentation.Issue(id, project, reporter, start * H, (start + hours) * H,
        (int) hours,
        (int) hours, outer, inner, null));
  }

  private static Double reporter(final Augmentation.Result r, final String id, final Variant v) {
    return r.columns().get(id).get(Augmentation.reporter(v));
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

  /** The issues with each scored row of {@code part} resolved {@code hours} later. */
  private List<Augmentation.Issue> later(final String part, final long hours) {
    return issues.stream().map(i -> (part.equals("TEST") ? i.outer() : i.inner()).equals("TEST")
        ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
            i.resolved() + hours * H, i.innerDays() + (int) hours, i.innerDays() + (int) hours, i.outer(), i.inner(),
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

  /** The variant's widest fit for {@code k}, the one its scored rows read. */
  private static Augmentation.ClusterFit widest(final Augmentation.Result r, final Variant v,
      final int k) {
    return r.clusterFits().stream().filter(f -> f.variant() == v && f.k() == k)
        .max(java.util.Comparator.comparingInt(Augmentation.ClusterFit::vectors)).orElseThrow();
  }

  /** The variant's widest fit for every k, in the order of the k. */
  private static List<Augmentation.ClusterFit> widest(final Augmentation.Result r,
      final Variant v) {
    return Augmentation.CLUSTER_K.stream().map(k -> widest(r, v, k)).toList();
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
    final Augmentation.ClusterFit whole = widest(r, Variant.INNER, 8);
    assertTrue(whole.fitted());
    assertEquals(32, whole.vectors());
    assertEquals(List.of(4, 4, 4, 4, 4, 4, 4, 4),
        whole.members().stream().map(List::size).toList());
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
    final List<Augmentation.ClusterFit> whole = widest(r, Variant.INNER);
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
    final List<Augmentation.ClusterFit> widest = new ArrayList<>(widest(r, Variant.INNER));
    widest.addAll(widest(r, Variant.OUTER));
    assertEquals(List.of(true, false, false, true, true, false), widest.stream()
        .map(Augmentation.ClusterFit::fitted).toList(), "nine inner vectors, eighteen outer");
    for (final Augmentation.ClusterFit f : widest) {
      if (!f.fitted()) {
        continue;
      }
      final List<String> members = f.members().stream().flatMap(List::stream).toList();
      // the last rows start at hour 90, before the tenth fitting and validation issues end
      assertEquals(f.variant() == Variant.INNER ? 9 : 18, members.size(), f.toString());
      assertTrue(members.stream().noneMatch(m -> m.startsWith("t")), f.toString());
      assertEquals(f.variant() == Variant.OUTER, members.stream().anyMatch(m -> m.startsWith("v")),
          f.toString());
    }
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
    assertEquals(32, widest(r, Variant.INNER, 8).vectors(),
        "the forty fitting issues but the eight of 0");
    // the reporter's every fitting issue but the zeros: 1 + i % 7 over the 32, median 4
    assertEquals(4.0, reporter(r, "v", Variant.INNER));
    assertEquals(4.0, reporter(r, "t", Variant.OUTER));
    assertTrue(r.columns().get("f35").containsKey(Augmentation.jaccard(5, Variant.INNER)),
        "an issue of 0 business days is still a row with columns of its own");
  }
  // ---------------------------------------------------------------- crossing issues

  /** {@code i} at other verdicts, everything else kept. */
  private static Augmentation.Issue at(final Augmentation.Issue i, final String outer,
      final String inner) {
    return new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(), i.resolved(),
        i.innerDays(), i.outerDays(), outer, inner, i.description());
  }

  /**
   * The rows of {@link #random} with some training issues crossing a cut, as the revised design
   * marks them: of the fitting issues every fourth crosses the outer cut (case 3) and every
   * fourth after it the inner cut alone (case 2); of the validation issues every third crosses
   * the outer cut (case 5).
   */
  private List<Augmentation.Issue> crossed() {
    final List<Augmentation.Issue> out = new ArrayList<>();
    for (final Augmentation.Issue i : issues) {
      final int n = Integer.parseInt(i.id().substring(1));
      if (i.id().startsWith("f") && n % 4 == 0) {
        out.add(at(i, "CROSSING", "CROSSING"));
      } else if (i.id().startsWith("f") && n % 4 == 1) {
        out.add(at(i, "TRAIN", "CROSSING"));
      } else if (i.id().startsWith("v") && n % 3 == 0) {
        out.add(at(i, "CROSSING", "TEST"));
      } else {
        out.add(i);
      }
    }
    return out;
  }

  @Test
  @DisplayName("crossing issues: no neighbour, reporter issue or cluster member at a level is one "
      + "that does not teach there; a crossing issue reads no column at a level that neither "
      + "teaches nor scores it, and the inner validation keeps the ones crossing the outer cut")
  void crossingIssuesTeachNothing() {
    random(7);
    randomVectors(3);
    final List<Augmentation.Issue> rows = crossed();
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    rows.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = run(rows);
    int read = 0;
    for (final Augmentation.Query q : r.queries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue(Verdict.teaches(q.variant(), h.outer(), h.inner()), q + " " + n);
        read++;
      }
    }
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue(Verdict.teaches(q.variant(), h.outer(), h.inner()), q + " " + n);
        read++;
      }
    }
    for (final Augmentation.ClusterFit f : r.clusterFits()) {
      for (final String m : f.members().stream().flatMap(List::stream).toList()) {
        final Augmentation.Issue h = byId.get(m);
        assertTrue(Verdict.teaches(f.variant(), h.outer(), h.inner()), f.variant() + " " + m);
        read++;
      }
    }
    assertTrue(read > 1_000, "the histories were exercised: " + read);
    for (final Augmentation.History h : r.histories()) {
      assertEquals(rows.stream().filter(i -> Verdict.teaches(h.variant(), i.outer(), i.inner())
          && i.innerDays() >= 1).count(), h.history(), h.toString());
    }
    for (final Augmentation.Issue i : rows) {
      for (final Variant v : Variant.values()) {
        // ticket 06: the outer test also answers at the inner level
        final boolean reads = Verdict.teaches(v, i.outer(), i.inner())
            || Verdict.scored(v, i.outer(), i.inner())
            || v == Variant.INNER && "TEST".equals(i.outer());
        if (!reads) {
          assertTrue(r.columns().get(i.id()).keySet().stream()
              .noneMatch(c -> c.endsWith(v.suffix())), i + " " + v);
        }
      }
    }
    // case 5: scored in the inner level, so it has an inner neighbour search of its own
    assertEquals("inner_validation", query(r, "v0", Variant.INNER).split());
    assertTrue(r.queries().stream().noneMatch(q -> q.id().equals("f0")), "case 3 reads nothing");
  }

  @Test
  @DisplayName("point in time: issues that end after every row's first commit move no column of "
      + "any of those rows, the clusters' included: no group is shaped by an issue not yet ended")
  void futureIssuesShapeNoCluster() {
    random(7);
    randomVectors(3);
    final Augmentation.Result before = run(issues);
    final List<Augmentation.Issue> with = new ArrayList<>(issues);
    final Random rng = new Random(9);
    for (int i = 0; i < 40; i++) {
      unit("z" + i, rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
      with.add(new Augmentation.Issue("z" + i, "p", "r", (10_000L + i) * H, (10_005L + i) * H, 5,
          5, "TRAIN", "TRAIN", "z" + i));
    }
    final Augmentation.Result after = run(with);
    int clusters = 0;
    for (final Augmentation.Issue i : issues) {
      assertEquals(before.columns().get(i.id()), after.columns().get(i.id()), i.id());
      for (final int k : Augmentation.CLUSTER_K) {
        clusters += before.columns().get(i.id()).containsKey(Augmentation.cluster(k,
            Variant.INNER)) ? 1 : 0;
      }
    }
    assertTrue(clusters > 50, "the cluster columns were exercised: " + clusters);
    final Map<String, Augmentation.ClusterQuery> was = new HashMap<>();
    before.clusterQueries().forEach(q -> was.put(q.id() + q.variant() + q.k(), q));
    for (final Augmentation.ClusterQuery q : after.clusterQueries()) {
      final Augmentation.ClusterQuery p = was.get(q.id() + q.variant() + q.k());
      if (p != null) {
        assertEquals(p, q, "the same fit, cluster and distance");
      }
    }
  }

  @Test
  @DisplayName("point in time: each row's fit holds the members its history admits and no other, "
      + "those resolved strictly before its first commit; rows admitting the same members share "
      + "one fit")
  void eachRowItsOwnFit() {
    random(7);
    randomVectors(3);
    final List<Augmentation.Issue> rows = crossed();
    final Augmentation.Result r = run(rows);
    final Map<String, Augmentation.Issue> byId = new HashMap<>();
    rows.forEach(i -> byId.put(i.id(), i));
    int checked = 0;
    for (final Augmentation.ClusterQuery q : r.clusterQueries()) {
      final Augmentation.Issue query = byId.get(q.id());
      final long admitted = rows.stream().filter(h -> h.description() != null
          && Verdict.teaches(q.variant(), h.outer(), h.inner()) && h.counted(q.variant())
          && h.resolved() < query.start()).count();
      assertEquals(admitted, q.history(), q.toString());
      checked++;
    }
    assertTrue(checked > 300, "rows checked: " + checked);
    for (final Variant v : Variant.values()) {
      for (final int k : Augmentation.CLUSTER_K) {
        final List<Augmentation.ClusterFit> fits = r.clusterFits().stream()
            .filter(f -> f.variant() == v && f.k() == k).toList();
        assertEquals(fits.size(), fits.stream().map(Augmentation.ClusterFit::vectors).distinct()
            .count(), "one fit per admitted prefix");
        assertEquals(r.clusterQueries().stream().filter(q -> q.variant() == v && q.k() == k)
            .count(), fits.stream().mapToInt(Augmentation.ClusterFit::rows).sum());
      }
    }
  }

  @Test
  @DisplayName("crossing issues: a crossing label moves no column of the level it crosses -- the "
      + "outer cut's crossing issues none at all, the inner cut's alone no inner column")
  void crossingLabelsMoveNothing() {
    random(11);
    randomVectors(5);
    final List<Augmentation.Issue> rows = crossed();
    final Map<String, Map<String, Double>> before = run(rows).columns();
    final List<Augmentation.Issue> outer = rows.stream().map(i -> i.outer().equals("CROSSING")
        ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
            i.resolved() + 5_000 * H, i.innerDays() + 5_000, i.outerDays() + 5_000, i.outer(),
            i.inner(), i.description()) : i).toList();
    assertEquals(before, run(outer).columns());
    final List<Augmentation.Issue> inner = rows.stream().map(i -> i.outer().equals("TRAIN")
        && i.inner().equals("CROSSING") ? new Augmentation.Issue(i.id(), i.project(),
            i.reporter(), i.start(), i.resolved() + 5_000 * H, i.innerDays() + 5_000,
            i.outerDays() + 5_000, i.outer(), i.inner(), i.description()) : i).toList();
    final Map<String, Map<String, Double>> moved = run(inner).columns();
    boolean outerMoved = false;
    for (final String id : before.keySet()) {
      for (final String column : Augmentation.columns()) {
        if (column.endsWith(Variant.INNER.suffix())) {
          assertEquals(before.get(id).get(column), moved.get(id).get(column), id + column);
        } else {
          outerMoved |= !Objects.equals(before.get(id).get(column),
              moved.get(id).get(column));
        }
      }
    }
    assertTrue(outerMoved, "an issue crossing the inner cut alone teaches the outer level");
  }
}
