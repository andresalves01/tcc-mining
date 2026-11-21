package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Histories;
import app.issues.Augmentation.Variant;
import app.pipeline.clustering.SmileKMeans;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The point-in-time histories of the revised temporal design ({@link Histories#POINT_IN_TIME}):
 * for a query, a history member counts only when it teaches at the level, its first resolution
 * comes strictly before the query's first commit, and it is not the query itself. Also the
 * requirement of ticket 06: the outer test answers at the inner level too, from the inner fitting
 * history alone.
 */
class PointInTimeHistoryTest {

  private static final long H = 3_600_000L;

  private static final int DIMENSION = 4;

  /** The hand-made embedding of a description that has one, by its text. */
  private final Map<String, float[]> hand = new HashMap<>();

  /** A hand-made vector when the text has one, otherwise a random unit vector seeded by it. */
  private final Embedder fake = text -> {
    final float[] given = hand.get(text);
    if (given != null) {
      return new Embedder.Embedding(given, 1);
    }
    final Random rng = new Random(text.hashCode());
    final double[] v = new double[DIMENSION];
    double norm = 0;
    for (int d = 0; d < DIMENSION; d++) {
      v[d] = rng.nextGaussian();
      norm += v[d] * v[d];
    }
    final float[] out = new float[DIMENSION];
    for (int d = 0; d < DIMENSION; d++) {
      out[d] = (float) (v[d] / Math.sqrt(norm));
    }
    return new Embedder.Embedding(out, 1);
  };

  private Augmentation.Result run(final List<Augmentation.Issue> rows, final Histories h) {
    return Augmentation.run(rows, fake, DIMENSION, new SmileKMeans(), h);
  }

  private static Augmentation.Issue issue(final String id, final String reporter,
      final String outer, final String inner, final long start, final long hours,
      final String text) {
    return new Augmentation.Issue(id, "p", reporter, start * H, (start + hours) * H, (int) hours,
        (int) hours, outer, inner, text);
  }

  /**
   * A corpus drawn as the revised temporal split draws it, the inner cut at hour 1,000 and the
   * outer at 2,000, every one of the five cases of {@link Verdict} and the outer test present: an
   * issue started by the inner cut is inner fitting, by the outer cut inner validation, after it
   * outer test; it crosses a cut it started by and was resolved after.
   */
  private static List<Augmentation.Issue> temporal(final long seed) {
    final Random rng = new Random(seed);
    final String[] words = {"npe", "build", "test", "docs", "leak", "race", "ui", "api", "db"};
    final List<Augmentation.Issue> out = new ArrayList<>();
    for (int i = 0; i < 240; i++) {
      final long start = rng.nextInt(3_000);
      final long hours = 1 + rng.nextInt(1_500);
      final long resolved = start + hours;
      final String outer;
      final String inner;
      if (start > 2_000) {
        outer = "TEST";
        inner = "";
      } else if (start > 1_000) {
        outer = resolved > 2_000 ? "CROSSING" : "TRAIN";
        inner = "TEST";
      } else {
        outer = resolved > 2_000 ? "CROSSING" : "TRAIN";
        inner = resolved > 1_000 ? "CROSSING" : "TRAIN";
      }
      String text = null;
      if (rng.nextInt(10) != 0) {
        final StringBuilder b = new StringBuilder();
        for (int k = rng.nextInt(4); k >= 0; k--) {
          b.append(words[rng.nextInt(words.length)]).append(' ');
        }
        b.append('w').append(i % 50);
        text = b.toString();
      }
      out.add(issue(String.format("i%03d", i), "r" + rng.nextInt(4), outer, inner, start, hours,
          text));
    }
    return out;
  }

  /** Whether {@code h} may enter {@code q}'s history at {@code v}'s level. */
  private static boolean admitted(final Variant v, final Augmentation.Issue q,
      final Augmentation.Issue h) {
    return Verdict.teaches(v, h.outer(), h.inner()) && h.resolved() < q.start()
        && !h.id().equals(q.id());
  }

  private static Map<String, Augmentation.Issue> byId(final List<Augmentation.Issue> rows) {
    final Map<String, Augmentation.Issue> out = new HashMap<>();
    rows.forEach(i -> out.put(i.id(), i));
    return out;
  }

  /** The fake embedder's vector of {@code text}, exact in double, as the augmentation reads it. */
  private double[] vector(final String text) {
    final float[] v = fake.embed(text).vector();
    final double[] out = new double[v.length];
    for (int d = 0; d < v.length; d++) {
      out[d] = v[d];
    }
    return out;
  }

  private static Double median(final List<Double> days) {
    return days.isEmpty() ? null : Augmentation.median(days);
  }

  @Test
  @DisplayName("no history reads an issue resolved at its first commit or after it, nor itself: "
      + "Jaccard and embedding neighbours, reporter issues and cluster members alike, at both "
      + "levels, every median the brute-force one")
  void neverAtOrAfterTheFirstCommit() {
    final List<Augmentation.Issue> rows = new ArrayList<>(temporal(7));
    // a training issue resolved exactly at a fitting row's first commit, with the same words
    rows.add(issue("edge-q", "r0", "TRAIN", "TRAIN", 500, 10, "npe build edge"));
    rows.add(issue("edge-h", "r0", "TRAIN", "TRAIN", 400, 100, "npe build edge"));
    final Map<String, Augmentation.Issue> byId = byId(rows);
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    int read = 0;
    for (final Augmentation.Query q : r.queries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        assertTrue(admitted(q.variant(), byId.get(q.id()), byId.get(n.id())), q + " " + n);
        read++;
      }
    }
    for (final Augmentation.EmbeddingQuery q : r.embeddingQueries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        assertTrue(admitted(q.variant(), byId.get(q.id()), byId.get(n.id())), q + " " + n);
        read++;
      }
    }
    assertTrue(read > 1_000, "the histories were exercised: " + read);
    assertTrue(r.queries().stream().filter(q -> q.id().equals("edge-q"))
        .allMatch(q -> q.nearest().stream().noneMatch(n -> n.id().equals("edge-h"))),
        "resolved at the very instant of the first commit: not yet known");

    // the cluster median, recomputed by brute force: the centres refitted on the vectors of the
    // members the query's history admits and on those alone, its cluster the nearest centre, its
    // median that of the admitted members nearest the same centre
    final Map<String, Clusterer.Fit> refits = new HashMap<>();
    int clustered = 0;
    for (final Augmentation.ClusterQuery q : r.clusterQueries()) {
      final Augmentation.Issue query = byId.get(q.id());
      final List<Augmentation.Issue> known = rows.stream()
          .filter(h -> admitted(q.variant(), query, h) && h.counted(q.variant())
              && h.description() != null)
          .sorted(Comparator.comparingLong(Augmentation.Issue::start)
              .thenComparing(Augmentation.Issue::id))
          .toList();
      assertEquals(known.size(), q.history(), q.toString());
      final double[][] x = known.stream().map(h -> vector(h.description()))
          .toArray(double[][]::new);
      final Clusterer.Fit fit = refits.computeIfAbsent(q.variant() + " " + q.k() + " "
          + known.stream().map(Augmentation.Issue::id).toList(),
          ids -> new SmileKMeans().fit(x, q.k(), Augmentation.CLUSTER_SEED));
      final Double column = r.columns().get(q.id()).get(Augmentation.cluster(q.k(), q.variant()));
      if (!fit.fitted() || query.description() == null) {
        assertEquals(-1, q.cluster(), q.toString());
        assertEquals(null, column, q.toString());
        continue;
      }
      final Clusterer.Assignment a = fit.assign(vector(query.description()));
      assertEquals(a.cluster(), q.cluster(), q.toString());
      assertEquals(a.distance(), q.distance(), q.toString());
      final List<Double> days = new ArrayList<>();
      for (int j = 0; j < x.length; j++) {
        if (fit.assign(x[j]).cluster() == a.cluster()) {
          days.add(known.get(j).days(q.variant()));
        }
      }
      assertEquals(days.size(), q.members(), q.toString());
      assertEquals(median(days), column, q.toString());
      clustered += days.size();
    }
    assertTrue(clustered > 1_000, "members read: " + clustered);
    assertTrue(refits.size() > 20, "fits refitted: " + refits.size());

    // the reporter median, recomputed by brute force
    int reported = 0;
    for (final Augmentation.Issue q : rows) {
      for (final Variant v : Variant.values()) {
        if (!Verdict.teaches(v, q.outer(), q.inner()) && !Verdict.scored(v, q.outer(), q.inner())
            && !(v == Variant.INNER && "TEST".equals(q.outer()))) {
          continue;
        }
        final List<Double> days = rows.stream().filter(h -> admitted(v, q, h)
            && q.reporter().equals(h.reporter())).map(h -> h.days(v)).toList();
        assertEquals(median(days), r.columns().get(q.id()).get(Augmentation.reporter(v)),
            q.id() + " " + v);
        reported += days.size();
      }
    }
    assertTrue(reported > 1_000, "reporter issues read: " + reported);
  }

  @Test
  @DisplayName("Jaccard: the n nearest are chosen among the admitted members, after the filter; "
      + "every row's neighbours and candidates are the brute-force ones")
  void nearestAfterTheFilter() {
    final List<Augmentation.Issue> rows = new ArrayList<>();
    // 25 identical descriptions resolved after q's first commit, closer than anything else
    for (int i = 0; i < 25; i++) {
      rows.add(issue(String.format("late%02d", i), "r", "TRAIN", "TRAIN", 0, 200 + i,
          "alpha beta gamma"));
    }
    rows.add(issue("early1", "r", "TRAIN", "TRAIN", 0, 10, "alpha zeta"));
    rows.add(issue("early2", "r", "TRAIN", "TRAIN", 0, 20, "alpha eta"));
    rows.add(issue("early3", "r", "TRAIN", "TRAIN", 0, 30, "beta theta"));
    rows.add(issue("q", "r", "TRAIN", "TEST", 100, 5, "alpha beta gamma"));
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    final Augmentation.Query q = r.queries().stream()
        .filter(x -> x.id().equals("q") && x.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(3, q.candidates(), "only the three resolved before its first commit");
    assertEquals(Set.of("early1", "early2", "early3"),
        Set.copyOf(q.nearest().stream().map(Augmentation.Neighbour::id).toList()));
    for (final int n : Augmentation.JACCARD_N) {
      assertEquals(20.0, r.columns().get("q").get(Augmentation.jaccard(n, Variant.INNER)),
          "a filter after the 20 nearest would have left none: n " + n);
    }

    // on the random corpus, every row's neighbours are the brute-force nearest of the admitted
    final List<Augmentation.Issue> corpus = temporal(3);
    final Map<String, Augmentation.Issue> byId = byId(corpus);
    final Augmentation.Result t = run(corpus, Histories.POINT_IN_TIME);
    final int most = Augmentation.JACCARD_N.stream().mapToInt(Integer::intValue).max()
        .orElseThrow();
    int compared = 0;
    for (final Augmentation.Query x : t.queries()) {
      final Augmentation.Issue query = byId.get(x.id());
      final Set<String> mine = Jaccard.tokens(query.description());
      final List<Augmentation.Issue> hits = corpus.stream()
          .filter(h -> admitted(x.variant(), query, h) && h.counted(x.variant()))
          .filter(h -> Jaccard.tokens(h.description()).stream().anyMatch(mine::contains))
          .sorted(Comparator.comparingDouble((Augmentation.Issue h) -> -similarity(mine, h))
              .thenComparing(Augmentation.Issue::id))
          .toList();
      assertEquals(hits.size(), x.candidates(), x.toString());
      assertEquals(hits.subList(0, Math.min(most, hits.size())).stream()
          .map(Augmentation.Issue::id).toList(),
          x.nearest().stream().map(Augmentation.Neighbour::id).toList(), x.toString());
      compared += x.nearest().size();
    }
    assertTrue(compared > 1_000, "neighbours compared: " + compared);
  }

  private static double similarity(final Set<String> mine, final Augmentation.Issue h) {
    final Set<String> theirs = Jaccard.tokens(h.description());
    final long shared = theirs.stream().filter(mine::contains).count();
    return shared / (double) (mine.size() + theirs.size() - shared);
  }

  @Test
  @DisplayName("embedding: the n nearest are chosen among the admitted members, inside the scan")
  void embeddingNearestAfterTheFilter() {
    final List<Augmentation.Issue> rows = new ArrayList<>();
    hand.put("same", new float[] {1, 0, 0, 0});
    hand.put("near", new float[] {0.8f, 0.6f, 0, 0});
    for (int i = 0; i < 25; i++) {
      rows.add(issue(String.format("late%02d", i), "r", "TRAIN", "TRAIN", 0, 200 + i, "same"));
    }
    rows.add(issue("early1", "r", "TRAIN", "TRAIN", 0, 10, "near"));
    rows.add(issue("early2", "r", "TRAIN", "TRAIN", 0, 30, "near"));
    rows.add(issue("q", "r", "TRAIN", "TEST", 100, 5, "same"));
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    final Augmentation.EmbeddingQuery q = r.embeddingQueries().stream()
        .filter(x -> x.id().equals("q") && x.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(2, q.candidates());
    assertEquals(List.of("early1", "early2"),
        q.nearest().stream().map(Augmentation.Neighbour::id).toList());
    for (final int n : Augmentation.EMBEDDING_N) {
      assertEquals(20.0, r.columns().get("q").get(Augmentation.embedding(n, Variant.INNER)));
    }
  }

  @Test
  @DisplayName("an outer test issue never reads a training issue resolved after its first "
      + "commit, crossing or not, at either level")
  void testRowsReadOnlyWhatWasKnown() {
    final List<Augmentation.Issue> rows = new ArrayList<>();
    rows.add(issue("known", "r", "TRAIN", "TRAIN", 0, 100, "alpha"));
    // inner validation resolved before the outer cut but after the test issue's first commit
    rows.add(issue("later", "r", "TRAIN", "TEST", 1_500, 499, "alpha"));
    // crossing the outer cut: teaches nothing at the outer level anyway
    rows.add(issue("crossing", "r", "CROSSING", "TEST", 1_600, 2_000, "alpha"));
    rows.add(issue("t", "r", "TEST", "", 1_800, 5, "alpha"));
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    for (final Variant v : Variant.values()) {
      assertEquals(100.0, r.columns().get("t").get(Augmentation.jaccard(5, v)), v.toString());
      assertEquals(100.0, r.columns().get("t").get(Augmentation.embedding(5, v)), v.toString());
      assertEquals(100.0, r.columns().get("t").get(Augmentation.reporter(v)), v.toString());
    }
  }

  @Test
  @DisplayName("an empty history gives missing values, without error")
  void emptyHistory() {
    final List<Augmentation.Issue> rows = new ArrayList<>();
    hand.put("x", new float[] {1, 0, 0, 0});
    for (int i = 0; i < 40; i++) {
      // everyone starts at hour 0: nobody was resolved before anyone's first commit
      rows.add(issue("f" + i, "r", "TRAIN", "TRAIN", 0, 1 + i, i % 2 == 0 ? "x" : "x y" + i));
    }
    rows.add(issue("v", "r", "TRAIN", "TEST", 0, 3, "x"));
    rows.add(issue("t", "r", "TEST", "", 0, 3, "x"));
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    for (final Augmentation.Issue i : rows) {
      assertEquals(Map.of(), r.columns().get(i.id()), i.id());
    }
    assertTrue(r.clusterQueries().stream().allMatch(q -> q.members() == 0 && q.history() == 0));
    assertTrue(r.clusterFits().stream().noneMatch(Augmentation.ClusterFit::fitted),
        "no centre is fitted: nobody's description was known at anyone's first commit");
  }

  @Test
  @DisplayName("ticket 06: the outer test answers at the inner level, from the inner fitting "
      + "history alone, assigned to the inner centres already fitted; its inner split is "
      + "outer_test; no new fit is made")
  void outerTestAnswersAtTheInnerLevel() {
    final List<Augmentation.Issue> rows = temporal(5);
    final Map<String, Augmentation.Issue> byId = byId(rows);
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    final List<Augmentation.Issue> tests = rows.stream().filter(i -> "TEST".equals(i.outer()))
        .toList();
    assertTrue(tests.size() > 30, "tests: " + tests.size());
    int observed = 0;
    for (final Augmentation.Issue t : tests) {
      observed += (int) r.columns().get(t.id()).keySet().stream()
          .filter(c -> c.endsWith(Variant.INNER.suffix())).count();
      final List<Augmentation.Query> inner = r.queries().stream()
          .filter(q -> q.id().equals(t.id()) && q.variant() == Variant.INNER).toList();
      assertEquals(1, inner.size(), t.id());
      assertEquals("outer_test", inner.get(0).split());
      for (final Augmentation.Neighbour n : inner.get(0).nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue("TRAIN".equals(h.outer()) && "TRAIN".equals(h.inner()), n.toString());
      }
    }
    assertTrue(observed > 100, "inner columns of the outer test: " + observed);
    assertTrue(r.embeddingQueries().stream().filter(q -> q.variant() == Variant.INNER
        && "TEST".equals(byId.get(q.id()).outer())).allMatch(q -> q.split().equals("outer_test")));
    assertTrue(r.clusterQueries().stream().filter(q -> q.variant() == Variant.INNER
        && "TEST".equals(byId.get(q.id()).outer())).allMatch(q -> q.split().equals("outer_test")));
    // every outer test row reads the widest inner fit, the inner validation's: no new fit
    final List<Augmentation.Issue> without = rows.stream()
        .filter(i -> !"TEST".equals(i.outer())).toList();
    assertEquals(fitted(run(without, Histories.POINT_IN_TIME), Variant.INNER),
        fitted(r, Variant.INNER), "no new fit");
    for (final int k : Augmentation.CLUSTER_K) {
      final int widest = r.clusterFits().stream()
          .filter(f -> f.variant() == Variant.INNER && f.k() == k)
          .mapToInt(Augmentation.ClusterFit::vectors).max().orElseThrow();
      assertTrue(r.clusterQueries().stream().filter(q -> q.variant() == Variant.INNER
          && q.k() == k && "TEST".equals(byId.get(q.id()).outer()))
          .allMatch(q -> q.history() == widest), "k " + k);
    }
    assertTrue(r.coverage().stream().anyMatch(c -> c.by().equals("split")
        && c.group().equals("outer_test") && c.column().endsWith(Variant.INNER.suffix())
        && c.rows() == tests.size()));
    // the row count of the inner history: its fitting and validation rows, then the outer test
    final long innerRows = rows.stream().filter(i -> Verdict.teaches(Variant.INNER, i.outer(),
        i.inner()) || Verdict.scored(Variant.INNER, i.outer(), i.inner())).count() + tests.size();
    assertEquals(innerRows, r.histories().get(0).rows());
  }

  @Test
  @DisplayName("ticket 06: the inner and outer columns of the fitting and validation rows are "
      + "the same with or without the outer test rows")
  void testRowsMoveNothing() {
    final List<Augmentation.Issue> rows = temporal(13);
    final Augmentation.Result with = run(rows, Histories.POINT_IN_TIME);
    final List<Augmentation.Issue> without = rows.stream()
        .filter(i -> !"TEST".equals(i.outer())).toList();
    final Augmentation.Result alone = run(without, Histories.POINT_IN_TIME);
    int compared = 0;
    for (final Augmentation.Issue i : without) {
      assertEquals(alone.columns().get(i.id()), with.columns().get(i.id()), i.id());
      compared += alone.columns().get(i.id()).size();
    }
    assertTrue(compared > 500, "values compared: " + compared);
    // the same centres, though the outer test rows also read some of them
    assertEquals(fitted(alone, Variant.INNER), fitted(with, Variant.INNER));
    assertTrue(fitted(with, Variant.OUTER).containsAll(fitted(alone, Variant.OUTER)));
  }

  /**
   * {@code v}'s fits as what was fitted, without how many rows read each or the members kept of
   * the widest: per k and size of the history fitted, its distinct vectors and distortion.
   */
  private static Set<List<Object>> fitted(final Augmentation.Result r, final Variant v) {
    final Set<List<Object>> out = new HashSet<>();
    r.clusterFits().stream().filter(f -> f.variant() == v).forEach(f -> out.add(List.of(f.k(),
        f.vectors(), f.distinct(), f.fitted(), f.distortion())));
    return out;
  }

  @Test
  @DisplayName("ticket 06: no label of the inner validation or of the outer test moves any inner "
      + "column, of any row, the outer test's included")
  void reservedLabelsMoveNoInnerColumn() {
    final List<Augmentation.Issue> rows = temporal(17);
    final Map<String, Map<String, Double>> before = run(rows, Histories.POINT_IN_TIME).columns();
    final List<Augmentation.Issue> moved = rows.stream()
        .map(i -> Verdict.scored(Variant.INNER, i.outer(), i.inner()) || "TEST".equals(i.outer())
            ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
                i.resolved() + 5_000 * H, i.innerDays() + 5_000, i.outerDays() + 5_000,
                i.outer(), i.inner(), i.description())
            : i).toList();
    final Map<String, Map<String, Double>> after = run(moved, Histories.POINT_IN_TIME).columns();
    int compared = 0;
    for (final String id : before.keySet()) {
      for (final String column : Augmentation.columns()) {
        if (column.endsWith(Variant.INNER.suffix())) {
          assertEquals(before.get(id).get(column), after.get(id).get(column), id + " " + column);
          compared += before.get(id).containsKey(column) ? 1 : 0;
        }
      }
    }
    assertTrue(compared > 1_000, "inner values compared: " + compared);
  }

  @Test
  @DisplayName("moving only the outer test rows moves no outer column, of any row")
  void outerTestMovesNoOuterColumn() {
    final List<Augmentation.Issue> rows = temporal(17);
    final Map<String, Map<String, Double>> before = run(rows, Histories.POINT_IN_TIME).columns();
    final List<Augmentation.Issue> moved = rows.stream()
        .map(i -> "TEST".equals(i.outer())
            ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
                i.resolved() + 5_000 * H, i.innerDays() + 5_000, i.outerDays() + 5_000,
                i.outer(), i.inner(), i.description())
            : i).toList();
    final Map<String, Map<String, Double>> after = run(moved, Histories.POINT_IN_TIME).columns();
    int compared = 0;
    for (final String id : before.keySet()) {
      for (final String column : Augmentation.columns()) {
        if (column.endsWith(Variant.OUTER.suffix())) {
          assertEquals(before.get(id).get(column), after.get(id).get(column), id + " " + column);
          compared += before.get(id).containsKey(column) ? 1 : 0;
        }
      }
    }
    assertTrue(compared > 1_000, "outer values compared: " + compared);
  }

  @Test
  @DisplayName("on the inner fitting rows, every member of the outer history that was resolved "
      + "before the row's first commit already teaches at the inner level, so the Jaccard, "
      + "embedding and reporter columns are the same at both levels")
  void innerFittingReadsTheSameAtBothLevels() {
    final List<Augmentation.Issue> rows = temporal(23);
    final Augmentation.Result r = run(rows, Histories.POINT_IN_TIME);
    int compared = 0;
    for (final Augmentation.Issue i : rows) {
      if (!Verdict.teaches(Variant.INNER, i.outer(), i.inner())) {
        continue;
      }
      final Map<String, Double> c = r.columns().get(i.id());
      final List<String> names = new ArrayList<>();
      Augmentation.JACCARD_N.forEach(n -> names.add("jaccard_median_" + n));
      Augmentation.EMBEDDING_N.forEach(n -> names.add("embedding_median_" + n));
      names.add("reporter_median");
      for (final String name : names) {
        assertEquals(c.get(name + Variant.INNER.suffix()), c.get(name + Variant.OUTER.suffix()),
            i.id() + " " + name);
        compared += c.containsKey(name + Variant.INNER.suffix()) ? 1 : 0;
      }
    }
    assertTrue(compared > 40, "values compared: " + compared);
  }
}
