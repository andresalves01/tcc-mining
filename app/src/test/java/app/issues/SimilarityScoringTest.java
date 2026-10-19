package app.issues;

import static app.issues.SimilarityScoring.Scope.INNER;
import static app.issues.SimilarityScoring.Scope.RESERVED;
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
import app.issues.model.ProjectBootstrap;
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
      "union_own", new ElasticNetSelection.Combination(20, 10, 16),
      "own_retrospective", new ElasticNetSelection.Combination(5, 10, 32),
      "own_with_similarity_before_pressure", new ElasticNetSelection.Combination(10, 10, 16),
      "own_retrospective_before_pressure", new ElasticNetSelection.Combination(20, 5, 8));

  /** The ninth Elastic Net model, the retrospective one before ticket 19 (ticket 20). */
  private static final String NINTH = "own_retrospective_before_pressure";

  /** The tenth Elastic Net model, Kikas's retrospective one (ticket 26). */
  private static final String TENTH = "kikas_retrospective";

  private static final List<String> ELASTIC_NET = List.of("own_without_similarity",
      "similarity_alone", "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own",
      "own_retrospective", "own_with_similarity_before_pressure");

  private static final List<String> LINEAR = List.of("global_median", "project_median",
      "executor_project_median", "own_without_similarity", "similarity_alone",
      "own_with_similarity", "alzubaidi_start", "kikas_start", "union_own", "own_retrospective",
      "own_with_similarity_before_pressure");

  private static final List<String> FORESTS = List.of("own_without_similarity_forest",
      "similarity_alone_forest", "own_with_similarity_forest", "alzubaidi_start_forest",
      "kikas_start_forest", "union_own_forest", "own_retrospective_forest",
      "own_with_similarity_before_pressure_forest");

  /**
   * The ten Elastic Net models the parameters hold: the eight the outer test scores, then the
   * ninth and the tenth, which only the inner report scores.
   */
  private static final List<String> CHOSEN = concat(ELASTIC_NET, List.of(NINTH, TENTH));

  /** The references and Elastic Nets before the retrospective model (ticket 15). */
  private static final List<String> LINEAR_BEFORE = LINEAR.subList(0, 9);

  /** The sixteen comparisons before the forests, in their order then. */
  private static final List<String> SIXTEEN = sixteen();

  private static List<String> sixteen() {
    final List<String> out = new ArrayList<>(List.of("own_with_similarity | own_without_similarity"));
    for (final String m : LINEAR_BEFORE.subList(1, 9)) {
      out.add(m + " | global_median");
    }
    for (final String m : LINEAR_BEFORE.subList(2, 9)) {
      out.add(m + " | project_median");
    }
    return List.copyOf(out);
  }

  /** The references, the Elastic Nets, then the forests. */
  private static final List<String> MODELS = concat(LINEAR, FORESTS);

  /**
   * Every model and comparison before ticket 13, as the code scored them on {@link #corpus} with
   * {@code choices(0.2)} on one thread: per model the error and a hash of every prediction, per
   * comparison the gap and both ends of its interval, as bits. First captured from 0869f6d on the
   * hours; regenerated once by ticket 17, when the corpus moved onto business days through the
   * population, by the code of 11d6710 (main after ticket 15).
   */
  private static final List<String> BEFORE_FORESTS = List.of(
      "global_median 3ff6aaaaaaaaaaab ba75a081",
      "project_median 3ff6666666666666 a8f5a081",
      "executor_project_median 3ff5777777777777 4895a081",
      "own_without_similarity 3ff8b6cb1fa33d36 c863f421",
      "similarity_alone 3fdb7bf10daba907 62e6de30",
      "own_with_similarity 3fde701f48adf5c4 50a389ec",
      "alzubaidi_start 3ff7870131e7d2b6 a779127f",
      "kikas_start 3ff6a80b84fdcf1d 4e0d4d23",
      "union_own 3fdf3db62c91d402 2c09e86c",
      "own_with_similarity | own_without_similarity bff11ac34d77bfc3 bff5dac062e4f37f "
          + "bfe79271db5996fd",
      "project_median | global_median bf91111111111111 bfb999999999999a 3fb1111111111111",
      "executor_project_median | global_median bfb3333333333333 bfcbbbbbbbbbbbbc "
          + "3fb5555555555555",
      "own_without_similarity | global_median 3fc06103a7c4944d 3fb2be9df7d6207d "
          + "3fc98c63609558ca",
      "similarity_alone | global_median bfef975cce7f80cf bff3fc0b009fd83e bfe5da4ea7a27d21",
      "own_with_similarity | global_median bfee1d45b0fe5a76 bff4885cdfc628ad "
          + "bfe21ff4cfb8fa80",
      "alzubaidi_start | global_median 3fab8ad0e7a50107 3f51c0787b242b55 3fbb43cf05b8705b",
      "kikas_start | global_median bf44f92d66dc7377 bfaec40b253451e6 3fae1c41b9fd6e4b",
      "union_own | global_median bfedb67a3f0c6b57 bff3c52db5871d1e bfe1f8578e52ae43",
      "executor_project_median | project_median bfadddddddddddde bfc1111111111111 "
          + "3f9999999999999a",
      "own_without_similarity | project_median 3fc28325c9e6b66f 3fb0ff8774291bb8 "
          + "3fcd3d6f0813b7fa",
      "similarity_alone | project_median bfef0ed445f6f846 bff402fc8207e68a bfe4c93d96916c11",
      "own_with_similarity | project_median bfed94bd2875d1ee bff4ba80d0c32e0e "
          + "bfe151c3a6e80bfa",
      "alzubaidi_start | project_median 3fb209acb816c4c7 bfa08e7f23ac8e2d 3fc6e03f9097c2c5",
      "kikas_start | project_median 3f906947a5da2d75 bfb4e35018b6d15a 3fbbc13abb7c51a9",
      "union_own | project_median bfed2df1b683e2cf bff44db63e0fa5a6 bfe1435843f4b887");

  /**
   * The nineteen models before ticket 22 in the inner scope, as the code scored them on {@link
   * #training} with {@code choices(0.2)} on one thread: per model the error and a hash of every
   * prediction, as bits. Captured from 8edd07b (main after ticket 21).
   */
  private static final List<String> INNER_BEFORE_NINTH = List.of(
      "global_median 3ff599999999999a ba75a081",
      "project_median 3ff7bbbbbbbbbbbc 9c75a081",
      "executor_project_median 3ff8444444444444 a05da081",
      "own_without_similarity 3ff8a39085886969 e389fb37",
      "similarity_alone 3fdd80296c8e73f3 2cb71f02",
      "own_with_similarity 3fddab6c2285810d 70b285c8",
      "alzubaidi_start 3ff783ccc9c612e5 6c6d99d3",
      "kikas_start 3ff599cb67cd5555 2145ca3c",
      "union_own 3fe2dcd5ef91b952 618f3502",
      "own_retrospective 3fdef1a976fe8dd1 9d80ed7d",
      "own_with_similarity_before_pressure 3fddbe5526d3eb72 5abad1d",
      "own_without_similarity_forest 3ff68da08ce3afa9 ac3397e0",
      "similarity_alone_forest 3fe376882a4672c9 9f0f82ba",
      "own_with_similarity_forest 3ff5d38e960e7a55 c7897c81",
      "alzubaidi_start_forest 3ff68d75bff9c77a 43e55399",
      "kikas_start_forest 3ff5dfa9abc1278b 86e19cff",
      "union_own_forest 3ff5d38e960e7a55 c7897c81",
      "own_retrospective_forest 3ff1fcbec9853410 4fcf0c83",
      "own_with_similarity_before_pressure_forest 3fef018dfd2a94b9 1a143cac");

  /**
   * The same run's comparisons and ordering intervals before ticket 22: their count and a hash of
   * every gap and interval end as bits, in their order.
   */
  private static final String PAIRS_BEFORE_NINTH = "80 eb1f0a39";

  /**
   * The whole inner run of ticket 22, the ninth model and its pairs included: the count of models,
   * comparisons and ordering intervals and a hash of every one's bits ({@link #innerBits}), in
   * their order. Captured from 5ff8564 (main after ticket 22), before the tail cut.
   */
  private static final String INNER_AT_TICKET_22 = "113 43e66d61";

  /**
   * The inner run of the fixture with a tail, cut at k = 1.5, as the code scored it before ticket
   * 26: per reading the count of models, comparisons and ordering intervals and a hash of every
   * one's bits ({@link #innerBits}) and of the fences and band limits, in their order, none of the
   * Kikas retrospective model's. Captured from 6d7edf1 (main after ticket 24).
   */
  private static final List<String> INNER_AT_TICKET_24 =
      List.of("114 9b40e4b5", "114 3993d3ba");

  private static List<String> concat(final List<String> a, final List<String> b) {
    final List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return List.copyOf(out);
  }

  /** Each model's forest at a point of the declared grid, the leaves differing. */
  private static Map<String, Forest.Choice> forests() {
    final Map<String, Forest.Choice> out = new LinkedHashMap<>();
    for (int m = 0; m < CHOSEN.size(); m++) {
      out.put(CHOSEN.get(m), new Forest.Choice(Forest.MTRY_SHARES[m % 3], 1 + m % 3,
          Forest.NODE_SIZES[m % 3]));
    }
    return out;
  }

  private static String hex(final double d) {
    return Long.toHexString(Double.doubleToRawLongBits(d));
  }

  private static Map<String, ElasticNetSelection.Choice> choices(final double lambda) {
    final Map<String, ElasticNetSelection.Choice> out = new LinkedHashMap<>();
    for (final String name : CHOSEN) {
      out.put(name, new ElasticNetSelection.Choice(0.5, lambda, COMBINATIONS.get(name)));
    }
    return out;
  }

  /**
   * Six projects; per project 20 inner fitting, 10 inner validation, 10 outer test and 2 crossing
   * rows. Every column any combination declares, of both variants, has a value; the similarity
   * medians follow the duration; the cluster medians are missing on every fourth row, and the
   * five retrospective attributes of the content on every fifth, as without an implementation
   * commit. Each issue lasts a business day per 24 of its drawn hours, at least one, and reaches
   * the run through the population, as the chain's entries do.
   */
  private static List<RowCache.Entry> corpus() {
    return BusinessDayFixtures.counted(rawCorpus());
  }

  /** The issues of {@link #corpus} before the population, their business days not yet set. */
  private static List<RowCache.Entry> rawCorpus() {
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
          if (Retrospective.CONTENT.contains(a.name()) && n % 5 == 2) {
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
            created, BusinessDayFixtures.after(start, BusinessDayFixtures.days(hours)), "Fixed",
            false, start),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  private static SimilarityScoring.Scored model(final SimilarityScoring.Report r,
      final String name) {
    return r.models().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
  }

  /** The same entries without the given columns. */
  private static List<RowCache.Entry> without(final List<RowCache.Entry> entries,
      final List<String> columns) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.keySet().removeAll(columns);
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, e.row().absent()),
          e.parts()));
    }
    return out;
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
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
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
        SimilarityScoring.run(corpus(), choices(0.0001), forests(), RESERVED);
    assertNotEquals(model(r, "own_with_similarity").predicted()[0],
        model(other, "own_with_similarity").predicted()[0]);
  }

  @Test
  @DisplayName("a model without its alpha and lambda, one the chain does not have, a model with "
      + "the similarity medians without its combination or one without them given one, refuses")
  void refusesIncompleteParameters() {
    final Map<String, ElasticNetSelection.Choice> missing = new LinkedHashMap<>(choices(0.2));
    missing.remove("own_with_similarity");
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), missing, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> extra = new LinkedHashMap<>(choices(0.2));
    extra.put("previous_own_start", new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), extra, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> noCombination =
        new LinkedHashMap<>(choices(0.2));
    noCombination.put("own_with_similarity", new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), noCombination, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> oneTooMany = new LinkedHashMap<>(choices(0.2));
    oneTooMany.put("own_without_similarity", new ElasticNetSelection.Choice(0.5, 0.2,
        new ElasticNetSelection.Combination(5, 5, 8)));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), oneTooMany, forests(), RESERVED));
  }

  @Test
  @DisplayName("the models read the outer variant at the chosen values: changing every inner "
      + "column changes no prediction")
  void readsTheOuterVariant() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r =
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED);
    final SimilarityScoring.Report inner = SimilarityScoring.run(
        changed(entries, k -> k.endsWith("_inner")), choices(0.2), forests(), RESERVED);
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predicted()),
          java.util.Arrays.toString(model(inner, name).predicted()), name);
    }
    assertTrue(model(r, "own_with_similarity").fitted().columns()
        .contains("jaccard_median_10_outer"));
  }

  @Test
  @DisplayName("each model with the similarity medians reads them, outer variant, at the Jaccard "
      + "n, embedding n and cluster k chosen for it; the other models read none")
  void eachModelAtItsOwnCombination() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
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
    final SimilarityScoring.Report r =
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED);
    final List<RowCache.Entry> longer = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final IssueUnit u = e.unit();
      longer.add("TEST".equals(e.outer(Population.IN_PROGRESS))
          ? new RowCache.Entry(u.withBusinessDays(u.businessDays() + 500), e.row(), e.parts())
          : e);
    }
    final SimilarityScoring.Report changed =
        SimilarityScoring.run(longer, choices(0.2), forests(), RESERVED);
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r, name).predicted()),
          java.util.Arrays.toString(model(changed, name).predicted()), name);
    }
  }

  @Test
  @DisplayName("exactly the agreed comparisons, none of a similarity median alone as a prediction: "
      + "the sixteen before the forests, then each forest vs its Elastic Net, the forest with "
      + "similarity vs the forest without, the new start model vs the one before ticket 19, and "
      + "every forest vs the global and the project median")
  void exactlyTheAgreedPairs() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
    final List<String> expected = new ArrayList<>(List.of(
        "own_with_similarity | own_without_similarity",
        "own_retrospective | own_with_similarity",
        "own_with_similarity | own_with_similarity_before_pressure"));
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
    assertEquals(16 + 1 + 1 + 2 + 2, expected.size());
    assertTrue(expected.containsAll(SIXTEEN));
    for (final String m : ELASTIC_NET) {
      expected.add(m + "_forest | " + m);
    }
    expected.add("own_with_similarity_forest | own_without_similarity_forest");
    expected.add("own_retrospective_forest | own_with_similarity_forest");
    expected.add("own_with_similarity_forest | own_with_similarity_before_pressure_forest");
    for (final String f : FORESTS) {
      expected.add(f + " | global_median");
    }
    for (final String f : FORESTS) {
      expected.add(f + " | project_median");
    }
    assertEquals(expected,
        r.comparisons().stream().map(g -> g.a() + " | " + g.b()).toList());
    assertEquals(22 + 8 + 3 + 8 + 8, new HashSet<>(expected).size());
    assertEquals(19, MODELS.size());
    // the outer test scores the nineteen it scored before the ninth Elastic Net (ticket 20)
    assertEquals(MODELS, r.models().stream().map(SimilarityScoring.Scored::name).toList());
    for (final var g : r.comparisons()) {
      assertTrue(g.low() <= g.gap() && g.gap() <= g.high(), g.toString());
    }
  }

  @Test
  @DisplayName("every model and comparison that existed before the forests scores, bit for bit, "
      + "what it scored before ticket 13, the retrospective model and columns beside them, when "
      + "the matrix lacks ticket 19's start columns: error, predictions, gap and interval")
  void unchangedModelsAsBefore() {
    final SimilarityScoring.Report r = SimilarityScoring.run(without(corpus(), Pressure.COLUMNS),
        choices(0.2), forests(), RESERVED, 1, 1);
    final List<String> now = new ArrayList<>();
    for (final String name : LINEAR_BEFORE) {
      final SimilarityScoring.Scored m = model(r, name);
      now.add(m.name() + " " + hex(m.score().mae()) + " "
          + Integer.toHexString(java.util.Arrays.hashCode(m.predicted())));
    }
    for (final String pair : SIXTEEN) {
      final var g = r.comparisons().stream().filter(x -> (x.a() + " | " + x.b()).equals(pair))
          .findFirst().orElseThrow();
      now.add(g.a() + " | " + g.b() + " " + hex(g.gap()) + " " + hex(g.low()) + " "
          + hex(g.high()));
    }
    assertEquals(BEFORE_FORESTS, now);
  }

  @Test
  @DisplayName("every model and comparison of the forests scores, bit for bit, what it scores "
      + "without the retrospective model's columns in the matrix")
  void forestsUnchangedByTheRetrospectiveColumns() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r =
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED, 1, 1);
    final SimilarityScoring.Report before = SimilarityScoring.run(
        without(entries, Retrospective.COLUMNS), choices(0.2), forests(), RESERVED, 1, 1);
    for (final String name : MODELS) {
      if (name.startsWith("own_retrospective")) {
        continue;
      }
      assertEquals(java.util.Arrays.toString(model(before, name).predicted()),
          java.util.Arrays.toString(model(r, name).predicted()), name);
      assertEquals(model(before, name).score(), model(r, name).score(), name);
    }
    for (final var g : r.comparisons()) {
      if (!g.a().startsWith("own_retrospective") && !g.b().startsWith("own_retrospective")) {
        assertTrue(before.comparisons().contains(g), g.toString());
      }
    }
    assertEquals(before.comparisons().size(), r.comparisons().size());
  }

  @Test
  @DisplayName("the side reading compares the retrospective model with the own specification with "
      + "similarity, Elastic Net and forest, on the scored issues with an implementation commit "
      + "only, resampling their projects")
  void sideReadingOnIssuesWithCommits() {
    final List<RowCache.Entry> entries = corpus();
    final SimilarityScoring.Report r =
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED, 1, 1);
    final List<Integer> rows = new ArrayList<>();
    for (int i = 0; i < r.scoredRows(); i++) {
      final String id = r.scoredUnits().get(i).issueId();
      final int n = Integer.parseInt(id.substring(1));
      if (n % 5 != 2) {
        rows.add(i);
      }
    }
    assertTrue(rows.size() < r.scoredRows() && !rows.isEmpty());
    assertEquals(rows.size(), r.withCommitRows());
    assertEquals(List.of("own_retrospective | own_with_similarity",
        "own_retrospective_forest | own_with_similarity_forest"),
        r.withCommits().stream().map(g -> g.a() + " | " + g.b()).toList());
    for (final var g : r.withCommits()) {
      final double[] a = model(r, g.a()).predicted();
      final double[] b = model(r, g.b()).predicted();
      double gap = 0;
      for (final int i : rows) {
        gap += Math.abs(a[i] - r.observed()[i]) - Math.abs(b[i] - r.observed()[i]);
      }
      assertEquals(gap / rows.size(), g.gap(), 1e-9, g.toString());
      assertTrue(g.low() <= g.gap() && g.gap() <= g.high(), g.toString());
    }
  }

  @Test
  @DisplayName("each forest is refitted on its Elastic Net's rows and design columns -- the "
      + "similarity medians at the chosen n and k -- with the column share and leaf given, as a "
      + "forest kind of its own")
  void forestBesideItsElasticNet() {
    final Map<String, Forest.Choice> given = forests();
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), given, RESERVED);
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
      assertEquals(60, forest.predicted().length);
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
        model(r, "own_with_similarity_forest").predicted()), java.util.Arrays.toString(
        model(SimilarityScoring.run(corpus(),
            choices(0.2), other, RESERVED), "own_with_similarity_forest")
            .predicted()));
  }

  @Test
  @DisplayName("forest parameters missing for a model, or given for one the chain does not have, "
      + "refuse the run")
  void refusesIncompleteForests() {
    final Map<String, Forest.Choice> missing = new LinkedHashMap<>(forests());
    missing.remove("union_own");
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), missing, RESERVED));
    final Map<String, Forest.Choice> extra = new LinkedHashMap<>(forests());
    extra.put("previous_own_start", new Forest.Choice(1.0 / 3, 1, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), extra, RESERVED));
    // more columns per split than the outer design has refuses rather than being cut down
    final Map<String, Forest.Choice> wide = new LinkedHashMap<>(forests());
    wide.put("similarity_alone", new Forest.Choice(2.0 / 3, 40, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), wide, RESERVED));
  }

  @Test
  @DisplayName("every model is read by its error, Spearman and pairwise ordering, and by band "
      + "of the true business days, cut at the quartiles of the fitting rows (ticket 24)")
  void readings() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
    assertEquals(MODELS, r.models().stream().map(SimilarityScoring.Scored::name).toList());
    for (final SimilarityScoring.Scored m : r.models()) {
      assertEquals(List.of(BusinessDays.bandLabels(r.bandTops())),
          m.bands().stream().map(b -> b.label()).toList());
      assertEquals(60, m.bands().stream().mapToInt(b -> b.n()).sum());
    }
    assertNull(model(r, "global_median").fitted());
  }

  @Test
  @DisplayName("the predictions file has one row per scored issue and one column per model")
  void predictionsFile() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
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
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED, 1, 1);
    final SimilarityScoring.Report many =
        SimilarityScoring.run(entries, choices(0.2), forests(), RESERVED, 6, 6);
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
        SimilarityScoring.predictions(
            SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED));
    final List<String> second =
        SimilarityScoring.predictions(
            SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED));
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

  @Test
  @DisplayName("on the business-day population, the issues of 0 business days are in neither the "
      + "outer training nor the outer test, the inner fitting the lambda scales by included; "
      + "every truth and reference is a whole number of business days")
  void businessDayPopulation() {
    final List<RowCache.Entry> raw = new ArrayList<>();
    int k = 0;
    for (final RowCache.Entry e : rawCorpus()) {
      final IssueUnit u = e.unit();
      // every seventh issue resolved an hour after its start, on the same date: 0 business days
      raw.add(k++ % 7 != 3 ? e : new RowCache.Entry(new IssueUnit(u.issueId(), u.key(),
          u.project(), u.reporter(), u.executor(), u.createdAt(), u.startedAt().plusSeconds(3600),
          u.reason(), u.moved(), u.startedAt()), e.row(), e.parts()));
    }
    final BusinessDays.Population p = BusinessDays.population(BusinessDayFixtures.bounded(raw),
        Augmentation.Variant.OUTER);
    assertTrue(p.kept().train() < p.all().train(), "some training issues lasted no business day");
    assertTrue(p.kept().test() < p.all().test(), "some test issues lasted no business day");
    assertTrue(p.kept().innerFit() < p.all().innerFit());
    final SimilarityScoring.Report r =
        SimilarityScoring.run(p.entries(), choices(0.2), forests(), RESERVED);
    assertEquals(p.kept().train(), r.fitRows());
    assertEquals(p.kept().test(), r.scoredRows());
    assertEquals(p.kept().innerFit(), r.innerFitRows());
    for (final double o : r.observed()) {
      assertTrue(o >= 1 && o == Math.rint(o), "observed " + o);
    }
    final double global = model(r, "global_median").predicted()[0];
    assertEquals(global * 2, Math.rint(global * 2), "a median of whole numbers");
  }

  @Test
  @DisplayName("an entry whose business days are not set, as one that did not go through the "
      + "population, refuses the run: no silent fallback to the hours")
  void refusesEntriesWithoutBusinessDays() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(BusinessDayFixtures.bounded(rawCorpus()), choices(0.2),
            forests(), RESERVED));
    assertTrue(e.getMessage().contains("business days"), e.getMessage());
  }

  /** The corpus's outer training rows only, as the inner report reads them. */
  private static List<RowCache.Entry> training() {
    return corpus().stream().filter(e -> "TRAIN".equals(e.outer(Population.IN_PROGRESS)))
        .toList();
  }

  /** Every model, comparison and ordering interval of an inner run, as bits, by name. */
  private static Map<String, String> innerBits(final SimilarityScoring.Report r) {
    final Map<String, String> out = new LinkedHashMap<>();
    for (final SimilarityScoring.Scored m : r.models()) {
      out.put(m.name(), hex(m.score().mae()) + " "
          + Integer.toHexString(java.util.Arrays.hashCode(m.predicted())));
    }
    for (final var g : r.comparisons()) {
      out.put(g.a() + " | " + g.b(), hex(g.gap()) + " " + hex(g.low()) + " " + hex(g.high()));
    }
    for (final var o : r.orderings()) {
      out.put("ordering " + o.a() + " | " + o.b(), hex(o.spearman().gap()) + " "
          + hex(o.spearman().low()) + " " + hex(o.spearman().high()) + " "
          + hex(o.pairwise().gap()) + " " + hex(o.pairwise().low()) + " "
          + hex(o.pairwise().high()));
    }
    return out;
  }

  @Test
  @DisplayName("in the inner scope the nineteen models before the ninth Elastic Net, their "
      + "comparisons and ordering intervals score, bit for bit, what they scored before it")
  void innerAsBeforeTheNinth() {
    final Map<String, String> now = innerBits(
        SimilarityScoring.run(training(), choices(0.2), forests(), INNER, 1, 1));
    final List<String> models = new ArrayList<>();
    for (final String name : MODELS) {
      models.add(name + " " + now.get(name));
    }
    assertEquals(INNER_BEFORE_NINTH, models);
    final List<String> pairs = new ArrayList<>();
    now.forEach((k, v) -> {
      if (k.contains(" | ") && !k.contains("own_retrospective_before_pressure")
          && !k.contains(TENTH)) {
        pairs.add(k + " " + v);
      }
    });
    assertEquals(PAIRS_BEFORE_NINTH, pairs.size() + " " + Integer.toHexString(pairs.hashCode()));
  }

  @Test
  @DisplayName("in the inner scope every model is fitted on the inner fitting rows at the lambda "
      + "chosen, unscaled, reads the inner variant, and only the inner validation is scored")
  void innerScope() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(training(), choices(0.2), forests(), INNER, 1, 1);
    assertEquals(INNER, r.scope());
    assertEquals(6 * 20, r.fitRows());
    assertEquals(6 * 20, r.innerFitRows());
    assertEquals(6 * 10, r.scoredRows());
    final Set<String> validation = new HashSet<>();
    for (final RowCache.Entry e : corpus()) {
      if ("TRAIN".equals(e.outer(Population.IN_PROGRESS))
          && "TEST".equals(e.inner(Population.IN_PROGRESS))) {
        validation.add(e.unit().issueId());
      }
    }
    assertEquals(validation,
        new HashSet<>(r.scoredUnits().stream().map(IssueUnit::issueId).toList()));
    for (final String name : ELASTIC_NET) {
      final Fitting.Fitted f = model(r, name).fitted();
      assertEquals(Double.doubleToRawLongBits(0.2), Double.doubleToRawLongBits(f.lambda()), name);
      assertTrue(f.columns().stream().noneMatch(c -> c.endsWith("_outer")), name);
    }
    assertTrue(model(r, "own_with_similarity").fitted().columns()
        .contains("jaccard_median_10_inner"));
    assertTrue(model(r, "own_with_similarity").removed().stream()
        .allMatch(x -> x.reason().contains("inner fitting")));
    // the outer test given or not, and the outer variant changed, every prediction is the same
    final SimilarityScoring.Report all = SimilarityScoring.run(
        changed(corpus(), k -> k.endsWith("_outer")), choices(0.2), forests(), INNER, 1, 1);
    assertEquals(SimilarityScoring.predictions(r), SimilarityScoring.predictions(all));
  }

  @Test
  @DisplayName("the inner scope's Elastic Net and forest errors are, bit for bit, the selection's "
      + "inner validation errors at the choices it wrote")
  void innerErrorsAreTheSelections() {
    final List<RowCache.Entry> training = training();
    final ElasticNetSelection.Result chosen = ElasticNetSelection.run(training, 1);
    final List<ElasticNetSelection.Parameter> parameters =
        ElasticNetSelection.read(ElasticNetSelection.parameters(chosen, "sha"));
    final SimilarityScoring.Report r = SimilarityScoring.run(training,
        ElasticNetSelection.choices(parameters), ElasticNetSelection.forests(parameters), INNER,
        1, 1);
    for (final ElasticNetSelection.Model m : chosen.models()) {
      final String name = m.cell().label();
      assertEquals(Double.doubleToRawLongBits(m.fitted().score().mae()),
          Double.doubleToRawLongBits(model(r, name).score().mae()), name);
      assertEquals(java.util.Arrays.toString(m.fitted().predicted()),
          java.util.Arrays.toString(model(r, name).predicted()), name);
      assertEquals(Double.doubleToRawLongBits(m.forest().score().mae()),
          Double.doubleToRawLongBits(model(r, SimilarityScoring.forest(name)).score().mae()),
          name);
    }
    assertEquals(Double.doubleToRawLongBits(chosen.projectMae()),
        Double.doubleToRawLongBits(model(r, "project_median").score().mae()));
    assertEquals(Double.doubleToRawLongBits(chosen.constantMae()),
        Double.doubleToRawLongBits(model(r, "global_median").score().mae()));
  }

  @Test
  @DisplayName("the inner report scores the ninth and the tenth Elastic Net and their forests "
      + "beside the others, each compared with the retrospective model's, and against the global "
      + "and the project median, in the error comparisons and the ordering intervals")
  void ninthAndTenthInTheInnerScope() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(training(), choices(0.2), forests(), INNER, 1, 1);
    final List<String> linear = new ArrayList<>(LINEAR);
    linear.add(NINTH);
    linear.add(TENTH);
    final List<String> trees = new ArrayList<>(FORESTS);
    trees.add(NINTH + "_forest");
    trees.add(TENTH + "_forest");
    assertEquals(concat(linear, trees),
        r.models().stream().map(SimilarityScoring.Scored::name).toList());
    final List<String> expected = new ArrayList<>(List.of(
        "own_with_similarity | own_without_similarity",
        "own_retrospective | own_with_similarity",
        "own_with_similarity | own_with_similarity_before_pressure",
        "own_retrospective | own_retrospective_before_pressure",
        "own_retrospective | kikas_retrospective"));
    for (final String m : linear) {
      if (!m.equals("global_median")) {
        expected.add(m + " | global_median");
      }
    }
    for (final String m : linear) {
      if (!m.equals("global_median") && !m.equals("project_median")) {
        expected.add(m + " | project_median");
      }
    }
    for (final String m : CHOSEN) {
      expected.add(m + "_forest | " + m);
    }
    expected.add("own_with_similarity_forest | own_without_similarity_forest");
    expected.add("own_retrospective_forest | own_with_similarity_forest");
    expected.add("own_with_similarity_forest | own_with_similarity_before_pressure_forest");
    expected.add("own_retrospective_forest | own_retrospective_before_pressure_forest");
    expected.add("own_retrospective_forest | kikas_retrospective_forest");
    for (final String f : trees) {
      expected.add(f + " | global_median");
    }
    for (final String f : trees) {
      expected.add(f + " | project_median");
    }
    assertEquals(expected, r.comparisons().stream().map(g -> g.a() + " | " + g.b()).toList());
    assertEquals(expected.stream().filter(p -> !p.endsWith(" | global_median")).toList(),
        r.orderings().stream().map(o -> o.a() + " | " + o.b()).toList());
    assertTrue(r.orderings().stream().anyMatch(o -> o.a().equals("own_retrospective_forest")
        && o.b().equals(NINTH + "_forest")));
    assertTrue(r.orderings().stream().anyMatch(o -> o.a().equals("own_retrospective")
        && o.b().equals(TENTH)));
    // the tenth reads Kikas's columns only: no similarity median, no own column
    assertTrue(model(r, TENTH).fitted().columns().stream().allMatch(c -> c.startsWith("K")
        || c.startsWith("LBL_")), model(r, TENTH).fitted().columns().toString());
    assertTrue(model(r, TENTH).fitted().columns().containsAll(List.of("K03_comments",
        "K10_mean_comment_words", "K21_participant_committers")),
        model(r, TENTH).fitted().columns().toString());
    assertEquals(model(r, TENTH).fitted().columns(), model(r, TENTH + "_forest").forest()
        .columns());
    // the ninth reads none of ticket 19's five columns, of either variant, nor their indicators;
    // the retrospective model all of them, the Blocker pace in the inner variant
    final List<String> five = List.of("START_fix_version", "START_release_cycle_days",
        "START_blocker_pace_days", "RETRO_blocker_wait", "RETRO_urgency_days");
    assertTrue(model(r, NINTH).fitted().columns().stream().noneMatch(c -> five.stream()
        .anyMatch(c::startsWith)), model(r, NINTH).fitted().columns().toString());
    assertTrue(model(r, "own_retrospective").fitted().columns().containsAll(List.of(
        "START_fix_version", "START_release_cycle_days", "START_blocker_pace_days_inner",
        "RETRO_blocker_wait", "RETRO_urgency_days")));
    assertEquals(model(r, NINTH).fitted().columns(), model(r, NINTH + "_forest").forest()
        .columns());
    // the test scores every model but the ninth and the tenth, the inner report every one
    assertEquals(ELASTIC_NET,
        CHOSEN.stream().filter(SimilarityScoring.Scope.RESERVED::scores).toList());
    assertEquals(CHOSEN, CHOSEN.stream().filter(INNER::scores).toList());
  }

  @Test
  @DisplayName("ordering intervals for every agreed pair but those against the global median, "
      + "which orders nothing, by the project bootstrap on the scored rows")
  void orderingIntervals() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED, 1, 1);
    final List<String> expected = r.comparisons().stream()
        .filter(g -> !g.b().equals("global_median")).map(g -> g.a() + " | " + g.b()).toList();
    assertEquals(expected, r.orderings().stream().map(o -> o.a() + " | " + o.b()).toList());
    assertTrue(expected.contains("own_with_similarity | own_without_similarity"));
    assertTrue(expected.contains("own_retrospective_forest | project_median"));
    final List<String> projects = r.scoredUnits().stream().map(IssueUnit::project).toList();
    for (final var o : r.orderings()) {
      assertEquals(ProjectBootstrap.ordering(projects, o.a(), model(r, o.a()).predicted(), o.b(),
          model(r, o.b()).predicted(), r.observed(), 1), o, o.a() + " | " + o.b());
      assertEquals(model(r, o.a()).score().pairwise() - model(r, o.b()).score().pairwise(),
          o.pairwise().gap());
    }
  }

  @Test
  @DisplayName("the side reading is also given on the scored issues without an implementation "
      + "commit, and each scored issue says whether it has one")
  void sideReadingWithoutCommits() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED, 1, 1);
    final List<Integer> without = new ArrayList<>();
    for (int i = 0; i < r.scoredRows(); i++) {
      final int n = Integer.parseInt(r.scoredUnits().get(i).issueId().substring(1));
      assertEquals(n % 5 != 2, r.implementationCommit()[i], r.scoredUnits().get(i).issueId());
      if (n % 5 == 2) {
        without.add(i);
      }
    }
    assertEquals(r.scoredRows() - r.withCommitRows(), without.size());
    assertEquals(List.of("own_retrospective | own_with_similarity",
        "own_retrospective_forest | own_with_similarity_forest"),
        r.withoutCommits().stream().map(g -> g.a() + " | " + g.b()).toList());
    for (final var g : r.withoutCommits()) {
      double gap = 0;
      for (final int i : without) {
        gap += Math.abs(model(r, g.a()).predicted()[i] - r.observed()[i])
            - Math.abs(model(r, g.b()).predicted()[i] - r.observed()[i]);
      }
      assertEquals(gap / without.size(), g.gap(), 1e-9, g.toString());
    }
  }

  /**
   * The entries with a tail on both fitting sides: every seventh outer training issue lasts 60
   * business days more, so the low fences cut it.
   */
  private static List<RowCache.Entry> withTail(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      final RowCache.Entry e = entries.get(i);
      final IssueUnit u = e.unit();
      out.add("TRAIN".equals(e.outer(Population.IN_PROGRESS)) && i % 7 == 0
          ? new RowCache.Entry(u.withBusinessDays(u.businessDays() + 60), e.row(), e.parts())
          : e);
    }
    return out;
  }

  /** The units of the entries on the given side of the given split level. */
  private static List<IssueUnit> side(final List<RowCache.Entry> entries, final boolean inner,
      final String part) {
    return entries.stream().filter(e -> "TRAIN".equals(e.outer(Population.IN_PROGRESS))
        && (!inner || part.equals(e.inner(Population.IN_PROGRESS))))
        .map(RowCache.Entry::unit).toList();
  }

  /**
   * The same entries with every other unit {@code which} selects, by id, lasting 500 business
   * days more: the others keep the usual flow from being empty.
   */
  private static List<RowCache.Entry> longer(final List<RowCache.Entry> entries,
      final java.util.function.Predicate<RowCache.Entry> which) {
    return entries.stream().map(e -> which.test(e) && e.unit().issueId().hashCode() % 2 == 0
        ? new RowCache.Entry(e.unit().withBusinessDays(e.unit().businessDays() + 500), e.row(),
            e.parts())
        : e).toList();
  }

  @Test
  @DisplayName("tail cut, inner scope: both fences are taken on the inner fitting set only; "
      + "changing every validation label moves neither them nor any prediction")
  void innerFencesOnTheFittingSetOnly() {
    final List<RowCache.Entry> entries = withTail(training());
    final SimilarityScoring.Readings r =
        SimilarityScoring.readings(entries, choices(0.2), forests(), 1.5, INNER, 1, 1);
    final List<IssueUnit> fitting = side(entries, true, "TRAIN");
    for (final SimilarityScoring.Report reading : List.of(r.main(), r.complementary())) {
      assertEquals(TailCut.fence(fitting, 1.5), reading.fences().fence());
      assertEquals(TailCut.fence(fitting, 3), reading.fences().usualFence());
      assertEquals(fitting.size(), reading.fences().uncutFitRows());
    }
    final SimilarityScoring.Readings changed = SimilarityScoring.readings(longer(entries,
        e -> "TEST".equals(e.inner(Population.IN_PROGRESS))), choices(0.2), forests(), 1.5,
        INNER, 1, 1);
    assertEquals(r.main().fences().fence(), changed.main().fences().fence());
    assertEquals(r.main().fences().usualFence(), changed.main().fences().usualFence());
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r.complementary(), name).predicted()),
          java.util.Arrays.toString(model(changed.complementary(), name).predicted()), name);
    }
  }

  @Test
  @DisplayName("tail cut, outer scope: both fences are taken on the outer training set only; "
      + "changing every outer test label moves neither them nor any prediction")
  void outerFencesOnTheTrainingSetOnly() {
    final List<RowCache.Entry> entries = withTail(corpus());
    final SimilarityScoring.Readings r =
        SimilarityScoring.readings(entries, choices(0.2), forests(), 1.5, RESERVED, 1, 1);
    final List<IssueUnit> training = side(entries, false, "");
    final List<IssueUnit> innerFitting = side(entries, true, "TRAIN");
    assertEquals(TailCut.fence(training, 1.5), r.main().fences().fence());
    assertEquals(TailCut.fence(training, 3), r.main().fences().usualFence());
    final double fence = TailCut.fence(training, 1.5);
    assertEquals(training.stream().filter(u -> u.duration() <= fence).count(),
        r.main().fitRows());
    assertTrue(r.main().fitRows() < training.size(), "the fixture's tail is cut");
    // the final lambda's ratio reads the inner fitting rows the same k leaves there
    final double innerFence = TailCut.fence(innerFitting, 1.5);
    assertEquals(innerFitting.stream().filter(u -> u.duration() <= innerFence).count(),
        r.main().innerFitRows());
    final SimilarityScoring.Readings changed = SimilarityScoring.readings(longer(entries,
        e -> "TEST".equals(e.outer(Population.IN_PROGRESS))), choices(0.2), forests(), 1.5,
        RESERVED, 1, 1);
    assertEquals(r.main().fences().fence(), changed.main().fences().fence());
    assertEquals(r.main().fences().usualFence(), changed.main().fences().usualFence());
    assertEquals(r.main().fitRows(), changed.main().fitRows());
    for (final String name : MODELS) {
      assertEquals(java.util.Arrays.toString(model(r.complementary(), name).predicted()),
          java.util.Arrays.toString(model(changed.complementary(), name).predicted()), name);
    }
  }

  @Test
  @DisplayName("tail cut: every model and reference is fitted on exactly the fitting rows at or "
      + "below the fence, bit for bit as if those above were not there; the usual-flow reading "
      + "holds exactly the validation issues at or below the k = 3 fence, the complementary one "
      + "all, with the same predictions")
  void cutRowsAndTwoReadings() {
    final List<RowCache.Entry> entries = withTail(training());
    final SimilarityScoring.Readings r =
        SimilarityScoring.readings(entries, choices(0.2), forests(), 1.5, INNER, 1, 1);
    final double fence = r.main().fences().fence();
    final List<RowCache.Entry> kept = entries.stream().filter(e ->
        !"TRAIN".equals(e.inner(Population.IN_PROGRESS)) || e.unit().duration() <= fence)
        .toList();
    assertTrue(kept.size() < entries.size(), "the fixture's tail is cut");
    final SimilarityScoring.Report alone = SimilarityScoring.run(kept, choices(0.2), forests(),
        INNER, 1, 1);
    assertEquals(innerBits(alone), innerBits(r.complementary()));
    assertEquals(alone.fitRows(), r.complementary().fitRows());

    final double usual = r.main().fences().usualFence();
    final List<String> all = side(entries, true, "TEST").stream().map(IssueUnit::issueId)
        .toList();
    final List<String> flow = side(entries, true, "TEST").stream()
        .filter(u -> u.duration() <= usual).map(IssueUnit::issueId).toList();
    assertTrue(flow.size() < all.size(), "the fixture has validation issues above the fence");
    assertEquals(all, r.complementary().scoredUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(flow, r.main().scoredUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(flow.size(), r.main().fences().usualRows());
    assertEquals(all.size(), r.main().fences().allRows());
    assertEquals(TailCut.Reading.USUAL_FLOW, r.main().reading());
    assertEquals(TailCut.Reading.ALL, r.complementary().reading());
    // the same fit: each prediction of the usual flow is the complementary reading's
    for (final String name : MODELS) {
      final double[] every = model(r.complementary(), name).predicted();
      final double[] main = model(r.main(), name).predicted();
      int k = 0;
      for (int i = 0; i < all.size(); i++) {
        if (flow.contains(all.get(i))) {
          assertEquals(Double.doubleToRawLongBits(every[i]), Double.doubleToRawLongBits(main[k++]),
              name);
        }
      }
      assertEquals(main.length, k);
    }
    // every table is the usual flow's own: comparisons and orderings too
    assertEquals(r.complementary().comparisons().size(), r.main().comparisons().size());
    assertEquals(r.complementary().orderings().size(), r.main().orderings().size());
    assertNotEquals(innerBits(r.complementary()), innerBits(r.main()));
  }

  @Test
  @DisplayName("tail cut: no cut reproduces ticket 22's inner run bit for bit in the "
      + "complementary reading, every model, comparison and ordering interval")
  void noCutIsTicket22() {
    final SimilarityScoring.Readings r = SimilarityScoring.readings(training(), choices(0.2),
        forests(), TailCut.NO_CUT, INNER, 1, 1);
    final List<String> all = new ArrayList<>();
    innerBits(r.complementary()).forEach((k, v) -> {
      if (!k.contains(TENTH)) {
        all.add(k + " " + v);
      }
    });
    assertEquals(INNER_AT_TICKET_22, all.size() + " " + Integer.toHexString(all.hashCode()));
    assertEquals(6 * 20, r.complementary().fitRows());
  }

  @Test
  @DisplayName("tail cut: in both readings every model, comparison and ordering interval before "
      + "ticket 26 scores bit for bit what it scored at ticket 24, the fences and bands too")
  void innerAsAtTicket24() {
    final SimilarityScoring.Readings r = SimilarityScoring.readings(withTail(training()),
        choices(0.2), forests(), 1.5, INNER, 1, 1);
    final List<String> now = new ArrayList<>();
    for (final SimilarityScoring.Report reading : List.of(r.main(), r.complementary())) {
      final List<String> all = new ArrayList<>();
      innerBits(reading).forEach((k, v) -> {
        if (!k.contains("kikas_retrospective")) {
          all.add(k + " " + v);
        }
      });
      all.add(reading.fences() + " " + java.util.Arrays.toString(reading.bandTops()));
      now.add(all.size() + " " + Integer.toHexString(all.hashCode()));
    }
    assertEquals(INNER_AT_TICKET_24, now);
  }

  @Test
  @DisplayName("the error bands are cut at the quartiles of the observed business days of the cut "
      + "fitting rows, in each scope; changing every validation or outer test label moves them "
      + "not (ticket 24)")
  void bandsAtTheFittingQuartiles() {
    final List<RowCache.Entry> inner = withTail(training());
    final SimilarityScoring.Readings r =
        SimilarityScoring.readings(inner, choices(0.2), forests(), 1.5, INNER, 1, 1);
    final double fence = r.main().fences().fence();
    final double[] cut = side(inner, true, "TRAIN").stream().mapToDouble(IssueUnit::duration)
        .filter(d -> d <= fence).toArray();
    for (final SimilarityScoring.Report reading : List.of(r.main(), r.complementary())) {
      assertEquals(java.util.Arrays.toString(TailCut.quartiles(cut)),
          java.util.Arrays.toString(reading.bandTops()));
      for (final SimilarityScoring.Scored m : reading.models()) {
        assertEquals(List.of(BusinessDays.bandLabels(reading.bandTops())),
            m.bands().stream().map(b -> b.label()).toList(), m.name());
        assertEquals(reading.scoredRows(), m.bands().stream().mapToInt(b -> b.n()).sum());
      }
    }
    final SimilarityScoring.Readings changed = SimilarityScoring.readings(longer(inner,
        e -> "TEST".equals(e.inner(Population.IN_PROGRESS))), choices(0.2), forests(), 1.5,
        INNER, 1, 1);
    assertEquals(java.util.Arrays.toString(r.main().bandTops()),
        java.util.Arrays.toString(changed.complementary().bandTops()));

    final List<RowCache.Entry> outer = withTail(corpus());
    final SimilarityScoring.Readings o =
        SimilarityScoring.readings(outer, choices(0.2), forests(), 1.5, RESERVED, 1, 1);
    final double outerFence = o.main().fences().fence();
    assertEquals(java.util.Arrays.toString(TailCut.quartiles(side(outer, false, "").stream()
        .mapToDouble(IssueUnit::duration).filter(d -> d <= outerFence).toArray())),
        java.util.Arrays.toString(o.main().bandTops()));
    final SimilarityScoring.Readings moved = SimilarityScoring.readings(longer(outer,
        e -> "TEST".equals(e.outer(Population.IN_PROGRESS))), choices(0.2), forests(), 1.5,
        RESERVED, 1, 1);
    assertEquals(java.util.Arrays.toString(o.main().bandTops()),
        java.util.Arrays.toString(moved.complementary().bandTops()));
  }

  @Test
  @DisplayName("the outer test's calibration is the inner report's usual-flow reading, bit for "
      + "bit, with no comparison drawn; changing every outer test label moves none of it "
      + "(ticket 24)")
  void calibrationIsTheInnerUsualFlow() {
    final List<RowCache.Entry> entries = withTail(corpus());
    final SimilarityScoring.Report c =
        SimilarityScoring.calibration(entries, choices(0.2), forests(), 1.5);
    final SimilarityScoring.Readings inner = SimilarityScoring.readings(entries, choices(0.2),
        forests(), 1.5, INNER, 1, 1);
    assertEquals(INNER, c.scope());
    assertEquals(TailCut.Reading.USUAL_FLOW, c.reading());
    assertEquals(inner.main().scoredUnits(), c.scoredUnits());
    assertEquals(concat(concat(LINEAR, List.of(NINTH, TENTH)), concat(FORESTS,
        List.of(SimilarityScoring.forest(NINTH), SimilarityScoring.forest(TENTH)))),
        c.models().stream().map(SimilarityScoring.Scored::name).toList());
    for (final SimilarityScoring.Scored m : c.models()) {
      assertEquals(java.util.Arrays.toString(model(inner.main(), m.name()).predicted()),
          java.util.Arrays.toString(m.predicted()), m.name());
    }
    assertTrue(c.comparisons().isEmpty() && c.orderings().isEmpty()
        && c.withCommits().isEmpty() && c.withoutCommits().isEmpty());
    final SimilarityScoring.Report moved = SimilarityScoring.calibration(longer(entries,
        e -> "TEST".equals(e.outer(Population.IN_PROGRESS))), choices(0.2), forests(), 1.5);
    assertEquals(java.util.Arrays.toString(c.observed()),
        java.util.Arrays.toString(moved.observed()));
    for (final SimilarityScoring.Scored m : c.models()) {
      assertEquals(java.util.Arrays.toString(m.predicted()),
          java.util.Arrays.toString(model(moved, m.name()).predicted()), m.name());
    }
  }

  @Test
  @DisplayName("the outer test's prediction intervals, end to end on the synthetic corpus: the "
      + "inner calibration applied to the outer models, every model of both readings read, the "
      + "global median's widths finite (ticket 24; code only, the seal holds)")
  void outerIntervalsEndToEnd() {
    final List<RowCache.Entry> entries = withTail(corpus());
    final SimilarityScoring.Readings outer =
        SimilarityScoring.readings(entries, choices(0.2), forests(), 1.5, RESERVED, 1, 1);
    final Map<String, PredictionIntervals.Calibration> calibrated =
        PredictionIntervals.calibrated(PredictionIntervals.Sample.of(
            SimilarityScoring.calibration(entries, choices(0.2), forests(), 1.5)));
    // the constant global median gets a finite factor at every level, inner
    for (final double factor : calibrated.get(SimilarityScoring.GLOBAL).factors()) {
      assertTrue(Double.isFinite(factor), Double.toString(factor));
    }
    final List<PredictionIntervals.Read> reads = PredictionIntervals.outer(calibrated,
        PredictionIntervals.Sample.of(outer.main()),
        PredictionIntervals.Sample.of(outer.complementary()), 1);
    assertEquals(2 * MODELS.size() * PredictionIntervals.LEVELS.size(), reads.size());
    for (final PredictionIntervals.Read r : reads) {
      final int n = r.reading() == TailCut.Reading.USUAL_FLOW ? outer.main().scoredRows()
          : outer.complementary().scoredRows();
      assertEquals(n, r.overall().n(), r.model());
      if (r.model().equals(SimilarityScoring.GLOBAL)) {
        assertTrue(Double.isFinite(r.overall().medianWidth()), r.toString());
        assertEquals(1, r.overall().relativeWidth(), 1e-12);
      }
    }
  }
}
