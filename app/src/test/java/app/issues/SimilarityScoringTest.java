package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
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

  /** Each model with the similarity medians at a combination of its own. */
  private static final Map<String, ElasticNetSelection.Combination> COMBINATIONS = Map.of(
      "similarity_alone", new ElasticNetSelection.Combination(5, 20, 8),
      "own_with_similarity", new ElasticNetSelection.Combination(10, 5, 32),
      "union_own", new ElasticNetSelection.Combination(20, 10, 16));

  private static final List<String> ELASTIC_NET = List.of("own_without_similarity",
      "similarity_alone", "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own");

  private static final List<String> LINEAR = List.of("global_median", "project_median",
      "executor_project_median", "own_without_similarity", "similarity_alone",
      "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own");

  private static final List<String> FORESTS = List.of("own_without_similarity_forest",
      "similarity_alone_forest", "own_with_similarity_forest", "alzubaidi_start_forest",
      "kikas_start_forest", "union_own_forest");

  /** The references, the Elastic Nets, then the forests. */
  private static final List<String> MODELS = concat(LINEAR, FORESTS);

  /**
   * Every model and comparison before ticket 13, as the code at 0869f6d scored them on {@link
   * #corpus} with {@code choices(0.2)} on one thread: per model the error and a hash of every
   * prediction, per comparison the gap and both ends of its interval, as bits.
   */
  private static final List<String> BEFORE_FORESTS = List.of(
      "global_median 4040a728527e2a51 484866c1",
      "project_median 403fe7487be95da5 12e05fa1",
      "executor_project_median 403eddb3bf2a5cc9 803e7a3d",
      "own_without_similarity 404156d11269c5f7 5801f079",
      "similarity_alone 401225b80f591ceb d45a827e",
      "own_with_similarity 4016286508506e31 fb263eab",
      "alzubaidi_start 4041306449b0d5fa de40e944",
      "kikas_start 40402687ff26870b 7f7c78a5",
      "union_own 401a87d9889caddb 26ae7c60",
      "own_with_similarity | own_without_similarity c03d2388e2bf7062 c042c90d337e3126 "
          + "c0347ad77b0f95c6",
      "project_median | global_median bff67082912f6fbf c005102c9081c2e4 3f93b3de77a86777",
      "executor_project_median | global_median c00384e72e8fbef6 c0130000fab9e670 "
          + "3fa5094a67443b55",
      "own_without_similarity | global_median 3ff5f517fd7374e7 bfd29882d7afa2a4 "
          + "400738af575dc924",
      "similarity_alone | global_median c03cc4e2a1260d67 c042336dff1daaf4 c0337abf07167fa2",
      "own_with_similarity | global_median c03bc43762e83916 c04220ebd31447e6 c0329bbe534b18cd",
      "alzubaidi_start | global_median 3ff1277ee655751d bfe34bcb4576d96d 40071197432ddf8f",
      "kikas_start | global_median bff0140a6af468cc c000d76e47d53f27 3fd7da4dedecae8b",
      "union_own | global_median c03aac5a42d5292a c0411bd3f4bfca04 c032cf06260ea739",
      "executor_project_median | project_median bff0994bcbf00e2d c003743e9d1391b6 "
          + "3fe65226a22b388f",
      "own_without_similarity | project_median 400632cd47517252 3ff1a1c56742d104 "
          + "401149f19beaee2b",
      "similarity_alone | project_median c03b5dda7813166b c0416090b9e9913e c03292a736d9bf64",
      "own_with_similarity | project_median c03a5d2f39d54219 c041470aea363cbc c031a61119b8992f",
      "alzubaidi_start | project_median 4003cc00bbc2726f 3ff30b46d4cd79e2 4011a969490bfc59",
      "kikas_start | project_median 3fd971e098ec1bcf bff9da0500cc4a1a 4002b71e9f4e04cd",
      "union_own | project_median c039455219c2322f c0403e2502645cd3 c0315215abbb9125");

  private static List<String> concat(final List<String> a, final List<String> b) {
    final List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return List.copyOf(out);
  }

  /** Each model's forest at a point of the declared grid, the leaves differing. */
  private static Map<String, Forest.Choice> forests() {
    final Map<String, Forest.Choice> out = new LinkedHashMap<>();
    for (int m = 0; m < ELASTIC_NET.size(); m++) {
      out.put(ELASTIC_NET.get(m), new Forest.Choice(Forest.MTRY_SHARES[m % 3], 1 + m % 3,
          Forest.NODE_SIZES[m % 3]));
    }
    return out;
  }

  private static String hex(final double d) {
    return Long.toHexString(Double.doubleToRawLongBits(d));
  }

  private static Map<String, ElasticNetSelection.Choice> choices(final double lambda) {
    final Map<String, ElasticNetSelection.Choice> out = new LinkedHashMap<>();
    for (final String name : ELASTIC_NET) {
      out.put(name, new ElasticNetSelection.Choice(0.5, lambda, COMBINATIONS.get(name)));
    }
    return out;
  }

  /**
   * Six projects; per project 20 inner fitting, 10 inner validation, 10 outer test and 2 crossing
   * rows. Every column any combination declares, of both variants, has a value; the similarity
   * medians follow the duration; the cluster medians are missing on every fourth row.
   */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(11);
    final Instant created = Instant.parse("2015-01-01T00:00:00Z");
    final Set<Attribute> declared = new LinkedHashSet<>();
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      for (final ElasticNetSelection.Combination c : ElasticNetSelection.Combination.all()) {
        for (final Cell cell : ElasticNetSelection.cells(c, v)) {
          declared.addAll(cell.attributes());
        }
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
          // its own draw per row and column, so no value moves when another model's columns do
          final Random draw = new Random(1_000_003L * n + a.name().hashCode());
          values.put(a.name(), similarity ? hours * (0.7 + 0.6 * draw.nextDouble())
              : a.kind() == Construct.Kind.CATEGORICAL ? draw.nextInt(3)
              : draw.nextInt(50));
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
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), forests());
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
        SimilarityScoring.run(corpus(), choices(0.0001), forests());
    assertNotEquals(model(r, "own_with_similarity").predictedHours()[0],
        model(other, "own_with_similarity").predictedHours()[0]);
  }

  @Test
  @DisplayName("a model without its alpha and lambda, one the chain does not have, a model with "
      + "the similarity medians without its combination or one without them given one, refuses")
  void refusesIncompleteParameters() {
    final Map<String, ElasticNetSelection.Choice> missing = new LinkedHashMap<>(choices(0.2));
    missing.remove("own_with_similarity");
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), missing, forests()));
    final Map<String, ElasticNetSelection.Choice> extra = new LinkedHashMap<>(choices(0.2));
    extra.put("previous_own_start", new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), extra, forests()));
    final Map<String, ElasticNetSelection.Choice> noCombination =
        new LinkedHashMap<>(choices(0.2));
    noCombination.put("own_with_similarity", new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), noCombination, forests()));
    final Map<String, ElasticNetSelection.Choice> oneTooMany = new LinkedHashMap<>(choices(0.2));
    oneTooMany.put("own_without_similarity", new ElasticNetSelection.Choice(0.5, 0.2,
        new ElasticNetSelection.Combination(5, 5, 8)));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), oneTooMany, forests()));
  }

  @Test
  @DisplayName("the models read the outer variant at the chosen values: changing every inner "
      + "column changes no prediction")
  void readsTheOuterVariant() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r = SimilarityScoring.run(entries, choices(0.2), forests());
    final SimilarityScoring.Report inner = SimilarityScoring.run(
        changed(entries, k -> k.endsWith("_inner")), choices(0.2), forests());
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predictedHours()),
          java.util.Arrays.toString(model(inner, name).predictedHours()), name);
    }
    assertTrue(model(r, "own_with_similarity").fitted().columns()
        .contains("jaccard_median_10_outer"));
  }

  @Test
  @DisplayName("each model with the similarity medians reads them, outer variant, at the Jaccard "
      + "n, embedding n and cluster k chosen for it; the other models read none")
  void eachModelAtItsOwnCombination() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), forests());
    assertEquals(List.of("jaccard_median_5_outer", "embedding_median_20_outer",
        "cluster_median_8_outer"), model(r, "similarity_alone").fitted().columns().stream()
            .filter(c -> !c.endsWith("_ausente")).toList());
    assertTrue(model(r, "own_with_similarity").fitted().columns().containsAll(List.of(
        "jaccard_median_10_outer", "embedding_median_5_outer", "cluster_median_32_outer")));
    assertTrue(model(r, "union_own").fitted().columns().containsAll(List.of(
        "jaccard_median_20_outer", "embedding_median_10_outer", "cluster_median_16_outer")));
    for (final String name : List.of("own_without_similarity", "alzubaidi_start",
        "kikas_start")) {
      assertTrue(model(r, name).fitted().columns().stream().noneMatch(c ->
          c.startsWith("jaccard_") || c.startsWith("embedding_") || c.startsWith("cluster_")),
          name);
    }
  }

  @Test
  @DisplayName("an outer test duration enters no fit: changing them changes no prediction")
  void testDurationsAreNotLearned() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r = SimilarityScoring.run(entries, choices(0.2), forests());
    final List<RowCache.Entry> longer = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final IssueUnit u = e.unit();
      longer.add("TEST".equals(e.outer(Population.IN_PROGRESS))
          ? new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), u.project(), u.reporter(),
              u.executor(), u.createdAt(), u.resolvedAt().plusSeconds(3600 * 500), u.reason(),
              u.moved(), u.startedAt()), e.row(), e.parts())
          : e);
    }
    final SimilarityScoring.Report changed = SimilarityScoring.run(longer, choices(0.2), forests());
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predictedHours()),
          java.util.Arrays.toString(model(changed, name).predictedHours()), name);
    }
  }

  @Test
  @DisplayName("exactly the agreed comparisons, none of a similarity median alone as a prediction: "
      + "the sixteen before the forests, then each forest vs its Elastic Net, the forest with "
      + "similarity vs the forest without, and every forest vs the global and the project median")
  void exactlyTheAgreedPairs() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), forests());
    final List<String> expected =
        new ArrayList<>(List.of("own_with_similarity | own_without_similarity"));
    for (final String m : LINEAR) {
      if (!m.equals("global_median")) {
        expected.add(m + " | global_median");
      }
    }
    for (final String m : LINEAR) {
      if (!m.equals("global_median") && !m.equals("project_median")) {
        expected.add(m + " | project_median");
      }
    }
    assertEquals(16, expected.size());
    for (final String m : ELASTIC_NET) {
      expected.add(m + "_forest | " + m);
    }
    expected.add("own_with_similarity_forest | own_without_similarity_forest");
    for (final String f : FORESTS) {
      expected.add(f + " | global_median");
    }
    for (final String f : FORESTS) {
      expected.add(f + " | project_median");
    }
    assertEquals(expected,
        r.comparisons().stream().map(g -> g.a() + " | " + g.b()).toList());
    assertEquals(16 + 6 + 1 + 6 + 6, new HashSet<>(expected).size());
    assertEquals(15, MODELS.size());
    for (final var g : r.comparisons()) {
      assertTrue(g.low() <= g.gapHours() && g.gapHours() <= g.high(), g.toString());
    }
  }

  @Test
  @DisplayName("every model and comparison that existed before the forests scores, bit for bit, "
      + "what it scored before ticket 13: error, predictions, gap and interval")
  void unchangedModelsAsBefore() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), 1, 1);
    final List<String> now = new ArrayList<>();
    for (final String name : LINEAR) {
      final SimilarityScoring.Scored m = model(r, name);
      now.add(m.name() + " " + hex(m.score().maeHours()) + " "
          + Integer.toHexString(java.util.Arrays.hashCode(m.predictedHours())));
    }
    for (final var g : r.comparisons().subList(0, 16)) {
      now.add(g.a() + " | " + g.b() + " " + hex(g.gapHours()) + " " + hex(g.low()) + " "
          + hex(g.high()));
    }
    assertEquals(BEFORE_FORESTS, now);
  }

  @Test
  @DisplayName("each forest is refitted on its Elastic Net's rows and design columns -- the "
      + "similarity medians at the chosen n and k -- with the column share and leaf given, as a "
      + "forest kind of its own")
  void forestBesideItsElasticNet() {
    final Map<String, Forest.Choice> given = forests();
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), given);
    for (final String name : ELASTIC_NET) {
      final SimilarityScoring.Scored linear = model(r, name);
      final SimilarityScoring.Scored forest = model(r, name + "_forest");
      assertEquals("forest", forest.kind());
      assertNull(forest.fitted());
      assertEquals(linear.fitted().columns(), forest.forest().columns(), name);
      assertEquals(linear.fitted().dropped(), forest.forest().dropped(), name);
      assertEquals(linear.removed(), forest.removed(), name);
      assertEquals(linear.fitted().cell().attributes(), forest.forest().cell().attributes(), name);
      assertEquals(given.get(name).share(), forest.forest().share(), name);
      assertEquals(given.get(name).nodeSize(), forest.forest().nodeSize(), name);
      assertEquals(given.get(name).mtry(), forest.forest().mtry(), name);
      assertEquals(60, forest.predictedHours().length);
      assertEquals(forest.forest().columns(), forest.forest().importance().stream()
          .map(Forest.Importance::column).toList(), name);
      forest.forest().importance().forEach(i ->
          assertEquals(Forest.PERMUTATION_REPEATS, i.increases().length));
    }
    assertTrue(model(r, "own_with_similarity_forest").forest().columns()
        .contains("jaccard_median_10_outer"));
    // the leaf is used, not ignored: another one gives other predictions
    final Map<String, Forest.Choice> other = new LinkedHashMap<>(given);
    assertEquals(100, given.get("own_with_similarity").nodeSize());
    other.put("own_with_similarity", new Forest.Choice(given.get("own_with_similarity").share(),
        given.get("own_with_similarity").mtry(), 5));
    assertNotEquals(java.util.Arrays.toString(
        model(r, "own_with_similarity_forest").predictedHours()), java.util.Arrays.toString(
        model(SimilarityScoring.run(corpus(), choices(0.2), other), "own_with_similarity_forest")
            .predictedHours()));
  }

  @Test
  @DisplayName("forest parameters missing for a model, or given for one the chain does not have, "
      + "refuse the run")
  void refusesIncompleteForests() {
    final Map<String, Forest.Choice> missing = new LinkedHashMap<>(forests());
    missing.remove("union_own");
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), missing));
    final Map<String, Forest.Choice> extra = new LinkedHashMap<>(forests());
    extra.put("previous_own_start", new Forest.Choice(1.0 / 3, 1, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), extra));
    // more columns per split than the outer design has refuses rather than being cut down
    final Map<String, Forest.Choice> wide = new LinkedHashMap<>(forests());
    wide.put("similarity_alone", new Forest.Choice(2.0 / 3, 40, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), wide));
  }

  @Test
  @DisplayName("every model is read by its error, Spearman and pairwise ordering, and by band "
      + "of the true business days: 1, 2-3, 4-10, 11+")
  void readings() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), forests());
    assertEquals(MODELS, r.models().stream().map(SimilarityScoring.Scored::name).toList());
    for (final SimilarityScoring.Scored m : r.models()) {
      assertEquals(m.score().maeHours() / 24, m.score().maeDays(), 1e-12);
      assertEquals(List.of("1", "2-3", "4-10", "11+"),
          m.bands().stream().map(b -> b.label()).toList());
      assertEquals(60, m.bands().stream().mapToInt(b -> b.n()).sum());
    }
    assertNull(model(r, "global_median").fitted());
  }

  @Test
  @DisplayName("the predictions file has one row per scored issue and one column per model")
  void predictionsFile() {
    final SimilarityScoring.Report r = SimilarityScoring.run(corpus(), choices(0.2), forests());
    final List<String> lines = SimilarityScoring.predictions(r);
    final List<String> header = List.of(lines.get(0).split("\t"));
    final List<String> expected = new ArrayList<>(List.of("id", "key", "project",
        "observed_business_days"));
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
    final SimilarityScoring.Report one =
        SimilarityScoring.run(entries, choices(0.2), forests(), 1, 1);
    final SimilarityScoring.Report many =
        SimilarityScoring.run(entries, choices(0.2), forests(), 6, 6);
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
        SimilarityScoring.predictions(SimilarityScoring.run(corpus(), choices(0.2), forests()));
    final List<String> second =
        SimilarityScoring.predictions(SimilarityScoring.run(corpus(), choices(0.2), forests()));
    assertTrue(SimilarityScoring.maxDifference(first, second)
        <= SimilarityScoring.RERUN_TOLERANCE);
    // on the same machine, the forests' per-tree seeds reproduce every prediction exactly
    assertTrue(List.of(first.get(0).split("\t")).containsAll(FORESTS));
    assertEquals(first, second);
    final List<String> off = new ArrayList<>(second);
    final String[] f = off.get(3).split("\t", -1);
    f[f.length - 1] = Double.toString(Double.parseDouble(f[f.length - 1]) + 0.5);
    off.set(3, String.join("\t", f));
    assertEquals(0.5, SimilarityScoring.maxDifference(first, off), 1e-9);
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.maxDifference(first, off.subList(0, 10)));
  }

  /** The entries with every day boundary at {@code hour}. */
  private static List<RowCache.Entry> boundary(final List<RowCache.Entry> entries,
      final double hour) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.put(BusinessDays.BOUNDARY, hour);
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, e.row().absent()),
          e.parts()));
    }
    return out;
  }

  @Test
  @DisplayName("on the business-day population, the issues of 0 business days are in neither the "
      + "outer training nor the outer test, the inner fitting the lambda scales by included; "
      + "every truth and reference is a whole number of business days")
  void businessDayPopulation() {
    final BusinessDays.Population p = BusinessDays.population(boundary(corpus(), 4));
    assertTrue(p.kept().train() < p.all().train(), "some training issues lasted no business day");
    assertTrue(p.kept().test() < p.all().test(), "some test issues lasted no business day");
    final SimilarityScoring.Report r = SimilarityScoring.run(p.entries(), choices(0.2), forests());
    assertEquals(p.kept().train(), r.fitRows());
    assertEquals(p.kept().test(), r.scoredRows());
    assertEquals(p.kept().innerFit(), r.innerFitRows());
    for (final double o : r.observed()) {
      assertTrue(o >= 1 && o == Math.rint(o), "observed " + o);
    }
    final double global = model(r, "global_median").predictedHours()[0];
    assertEquals(global * 2, Math.rint(global * 2), "a median of whole numbers");
  }
}
