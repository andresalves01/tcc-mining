package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ParallelSweepTest;
import app.study.Construct;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The test run of the description-similarity chain, on a synthetic corpus checked by hand. */
class SimilarityScoringTest {

  private static final Map<String, Integer> CHOSEN =
      Map.of("jaccard_median", 10, "embedding_median", 5, "cluster_median", 32);

  private static final List<String> ELASTIC_NET = List.of("previous_own_start", "revised_nine",
      "similarity_alone", "revised_twelve", "alzubaidi_start", "kikas_start", "union_revised");

  private static final List<String> MODELS = List.of("global_median", "project_median",
      "executor_project_median", "jaccard_direct", "embedding_direct", "cluster_direct",
      "previous_own_start", "revised_nine", "similarity_alone", "revised_twelve",
      "alzubaidi_start", "kikas_start", "union_revised");

  private static Map<String, ElasticNetSelection.Choice> choices(final double lambda) {
    final Map<String, ElasticNetSelection.Choice> out = new LinkedHashMap<>();
    for (final String name : ELASTIC_NET) {
      out.put(name, new ElasticNetSelection.Choice(0.5, lambda));
    }
    return out;
  }

  /**
   * Six projects; per project 20 inner fitting, 10 inner validation, 10 outer test and 2 crossing
   * rows. Every declared column of both variants has a value; the similarity medians follow the
   * duration; the cluster median is missing on every fourth row.
   */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(11);
    final Instant created = Instant.parse("2015-01-01T00:00:00Z");
    final Set<Attribute> declared = new LinkedHashSet<>();
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      for (final Cell cell : ElasticNetSelection.cells(CHOSEN, v)) {
        declared.addAll(cell.attributes());
      }
    }
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 6; p++) {
      for (int k = 0; k < 42; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 40 ? new String[] {"TEST", ""} : new String[] {"CROSSES", ""};
        final double hours = Math.exp(1 + 4 * random.nextDouble()) + 5 * p;
        final Map<String, Double> values = new HashMap<>();
        for (final Attribute a : declared) {
          final boolean similarity = a.name().startsWith("jaccard_")
              || a.name().startsWith("embedding_") || a.name().startsWith("cluster_");
          if (a.name().startsWith("cluster_") && n % 4 == 0) {
            continue;
          }
          values.put(a.name(), similarity ? hours * (0.7 + 0.6 * random.nextDouble())
              : a.kind() == Construct.Kind.CATEGORICAL ? random.nextInt(3)
              : random.nextInt(50));
        }
        final String id = "i" + n++;
        final Instant start = created.plusSeconds(3600L * n);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.IN_PROGRESS, part);
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e" + (k % 3),
            created, start.plusMillis((long) (hours * 3_600_000)), "Fixed", false, start),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  private static SimilarityScoring.Scored model(final SimilarityScoring.Report r,
      final String name) {
    return r.models().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
  }

  /** The same entries with every value of the columns {@code which} selects replaced. */
  private static List<RowCache.Entry> changed(final List<RowCache.Entry> entries,
      final java.util.function.Predicate<String> which) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.replaceAll((k, v) -> which.test(k) ? v * 1000 + 7 : v);
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, e.row().absent()),
          e.parts()));
    }
    return out;
  }

  @Test
  @DisplayName("every model is refitted with the alpha and lambda it was given, the lambda scaled "
      + "by the ratio of rows as the registered chain does; nothing is swept or chosen")
  void readsParametersAndNeverSelects() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), CHOSEN, choices(0.2));
    assertEquals(6 * 30, r.fitRows());
    assertEquals(6 * 20, r.innerFitRows());
    assertEquals(6 * 10, r.scoredRows());
    for (final String name : ELASTIC_NET) {
      final Fitting.Fitted f = model(r, name).fitted();
      assertEquals(0.5, f.alpha(), name);
      assertEquals(Fitting.finalLambda(0.2, 180, 120), f.lambda(), 1e-12, name);
      assertEquals(-1, f.step(), name + " was swept");
      assertTrue(f.trials().isEmpty(), name + " tried a grid");
    }
    // the parameters are used, not searched over: another lambda gives other predictions
    final SimilarityScoring.Report other =
        SimilarityScoring.run(corpus(), CHOSEN, choices(0.0001));
    assertNotEquals(model(r, "revised_twelve").predictedHours()[0],
        model(other, "revised_twelve").predictedHours()[0]);
  }

  @Test
  @DisplayName("a model without its alpha and lambda, or one the chain does not have, refuses")
  void refusesIncompleteParameters() {
    final Map<String, ElasticNetSelection.Choice> missing = new LinkedHashMap<>(choices(0.2));
    missing.remove("revised_twelve");
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), CHOSEN, missing));
    final Map<String, ElasticNetSelection.Choice> extra = new LinkedHashMap<>(choices(0.2));
    extra.put("revised_thirteen", new ElasticNetSelection.Choice(0.5, 0.2));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), CHOSEN, extra));
  }

  @Test
  @DisplayName("the models read the outer variant at the chosen values: changing every inner "
      + "column changes no prediction")
  void readsTheOuterVariant() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r = SimilarityScoring.run(entries, CHOSEN, choices(0.2));
    final SimilarityScoring.Report inner = SimilarityScoring.run(
        changed(entries, k -> k.endsWith("_inner")), CHOSEN, choices(0.2));
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predictedHours()),
          java.util.Arrays.toString(model(inner, name).predictedHours()), name);
    }
    assertTrue(model(r, "revised_twelve").fitted().columns()
        .contains("jaccard_median_10_outer"));
  }

  @Test
  @DisplayName("a direct predictor reads its chosen outer column, and the outer training median "
      + "where it is missing")
  void directPredictorFallsBack() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r = SimilarityScoring.run(entries, CHOSEN, choices(0.2));
    final double[] predicted = model(r, "cluster_direct").predictedHours();
    final List<Double> training = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      if ("TRAIN".equals(e.outer(Population.IN_PROGRESS))) {
        training.add(e.unit().durationHours());
      }
    }
    training.sort(Double::compare);
    final double median = (training.get(89) + training.get(90)) / 2;
    assertEquals(median, r.fallbackHours(), 1e-9);
    int missing = 0;
    for (int i = 0; i < predicted.length; i++) {
      final String id = r.scoredUnits().get(i).issueId();
      final RowCache.Entry e = entries.stream()
          .filter(x -> x.unit().issueId().equals(id)).findFirst().orElseThrow();
      final Double value = e.row().values().get("cluster_median_32_outer");
      assertEquals(value == null ? median : value, predicted[i], 1e-9);
      missing += value == null ? 1 : 0;
    }
    assertTrue(missing > 0);
    final SimilarityScoring.Direct cluster = r.directs().stream()
        .filter(d -> d.name().equals("cluster_direct")).findFirst().orElseThrow();
    assertEquals("cluster_median_32_outer", cluster.column());
    assertEquals(60 - missing, cluster.observed());
  }

  @Test
  @DisplayName("an outer test duration enters no fit: changing them changes no prediction")
  void testDurationsAreNotLearned() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r = SimilarityScoring.run(entries, CHOSEN, choices(0.2));
    final List<RowCache.Entry> longer = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final IssueUnit u = e.unit();
      longer.add("TEST".equals(e.outer(Population.IN_PROGRESS))
          ? new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), u.project(), u.reporter(),
              u.executor(), u.createdAt(), u.resolvedAt().plusSeconds(3600 * 500), u.reason(),
              u.moved(), u.startedAt()), e.row(), e.parts())
          : e);
    }
    final SimilarityScoring.Report changed = SimilarityScoring.run(longer, CHOSEN, choices(0.2));
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predictedHours()),
          java.util.Arrays.toString(model(changed, name).predictedHours()), name);
    }
  }

  @Test
  @DisplayName("exactly the agreed comparisons: revised nine vs previous, revised twelve vs revised "
      + "nine, embedding vs Jaccard direct, cluster vs embedding direct, every model vs the global "
      + "median and vs the project median")
  void exactlyTheAgreedPairs() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), CHOSEN, choices(0.2));
    final List<String> expected = new ArrayList<>(List.of(
        "revised_nine | previous_own_start", "revised_twelve | revised_nine",
        "embedding_direct | jaccard_direct", "cluster_direct | embedding_direct"));
    for (final String m : MODELS) {
      if (!m.equals("global_median")) {
        expected.add(m + " | global_median");
      }
    }
    for (final String m : MODELS) {
      if (!m.equals("global_median") && !m.equals("project_median")) {
        expected.add(m + " | project_median");
      }
    }
    assertEquals(expected,
        r.comparisons().stream().map(g -> g.a() + " | " + g.b()).toList());
    assertEquals(4 + 12 + 11, new HashSet<>(expected).size());
    for (final var g : r.comparisons()) {
      assertTrue(g.low() <= g.gapHours() && g.gapHours() <= g.high(), g.toString());
    }
  }

  @Test
  @DisplayName("every model is read in days, Spearman and pairwise ordering, and by duration "
      + "band with the longest tenth apart")
  void readings() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), CHOSEN, choices(0.2));
    assertEquals(MODELS, r.models().stream().map(SimilarityScoring.Scored::name).toList());
    for (final SimilarityScoring.Scored m : r.models()) {
      assertEquals(m.score().maeHours() / 24, m.score().maeDays(), 1e-12);
      assertEquals(5, m.bands().size());
      assertEquals("slowest 10%", m.bands().get(4).label());
      assertEquals(60, m.bands().stream().mapToInt(b -> b.n()).sum());
    }
    assertNull(model(r, "global_median").fitted());
  }

  @Test
  @DisplayName("the predictions file has one row per scored issue and one column per model")
  void predictionsFile() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), CHOSEN, choices(0.2));
    final List<String> lines = SimilarityScoring.predictions(r);
    final List<String> header = List.of(lines.get(0).split("\t"));
    final List<String> expected = new ArrayList<>(List.of("id", "key", "project", "observed_h"));
    expected.addAll(MODELS);
    assertEquals(expected, header);
    assertEquals(1 + 60, lines.size());
    final Set<String> ids = new HashSet<>();
    for (final String line : lines.subList(1, lines.size())) {
      final String[] f = line.split("\t", -1);
      assertEquals(header.size(), f.length);
      assertTrue(ids.add(f[0]), "twice: " + f[0]);
    }
    assertEquals(new HashSet<>(r.scoredUnits().stream().map(IssueUnit::issueId).toList()), ids);
  }

  @Test
  @DisplayName("refitted and resampled on several threads, every prediction, score, coefficient "
      + "and interval is, bit for bit, what one thread gives")
  void parallelIsSequential() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report one = SimilarityScoring.run(entries, CHOSEN, choices(0.2), 1, 1);
    final SimilarityScoring.Report many =
        SimilarityScoring.run(entries, CHOSEN, choices(0.2), 6, 6);
    assertEquals(SimilarityScoring.predictions(one), SimilarityScoring.predictions(many));
    assertEquals(one.comparisons(), many.comparisons(), "every interval, as exact doubles");
    assertEquals(one.models().size(), many.models().size());
    for (int m = 0; m < one.models().size(); m++) {
      final SimilarityScoring.Scored a = one.models().get(m);
      final SimilarityScoring.Scored b = many.models().get(m);
      assertEquals(a.name(), b.name());
      assertEquals(a.score(), b.score());
      assertEquals(a.removed(), b.removed());
      assertEquals(a.fitted() == null, b.fitted() == null);
      if (a.fitted() != null) {
        ParallelSweepTest.assertIdentical(a.fitted(), b.fitted());
      }
    }
  }

  @Test
  @DisplayName("a rerun with the same inputs reproduces every prediction within the stated "
      + "tolerance, and a changed prediction is caught")
  void rerunReproduces() {
    final List<String> first =
        SimilarityScoring.predictions(SimilarityScoring.run(corpus(), CHOSEN, choices(0.2)));
    final List<String> second =
        SimilarityScoring.predictions(SimilarityScoring.run(corpus(), CHOSEN, choices(0.2)));
    assertTrue(SimilarityScoring.maxDifference(first, second)
        <= SimilarityScoring.RERUN_TOLERANCE_HOURS);
    final List<String> off = new ArrayList<>(second);
    final String[] f = off.get(3).split("\t", -1);
    f[f.length - 1] = Double.toString(Double.parseDouble(f[f.length - 1]) + 0.5);
    off.set(3, String.join("\t", f));
    assertEquals(0.5, SimilarityScoring.maxDifference(first, off), 1e-9);
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.maxDifference(first, off.subList(0, 10)));
  }
}
