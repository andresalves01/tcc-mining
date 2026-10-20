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

/** The study's test run, on a synthetic corpus checked by hand. */
class SimilarityScoringTest {

  /** Each model with the similarity medians at a combination of its own. */
  private static final Map<String, ElasticNetSelection.Combination> COMBINATIONS = Map.of(
      "own_retrospective", new ElasticNetSelection.Combination(5, 10, 32),
      "own_with_similarity_before_pressure", new ElasticNetSelection.Combination(10, 10, 16),
      "own_retrospective_with_discussion", new ElasticNetSelection.Combination(10, 20, 16));

  /** The own start model. */
  private static final String OWN = "own_with_similarity_before_pressure";

  /** Kikas's retrospective model. */
  private static final String KIKAS_RETRO = "kikas_retrospective";

  /** The ceiling, the retrospective model with the comments and the participants. */
  private static final String CEILING = "own_retrospective_with_discussion";

  /** The six Elastic Net models, in their order. */
  private static final List<String> ELASTIC_NET = List.of(OWN, "alzubaidi_start", "kikas_start",
      "own_retrospective", CEILING, KIKAS_RETRO);

  /** The references and the Elastic Nets. */
  private static final List<String> LINEAR = concat(List.of("global_median", "project_median"),
      ELASTIC_NET);

  private static final List<String> FORESTS = ELASTIC_NET.stream().map(m -> m + "_forest")
      .toList();

  /** The models the fourteen leave out, with their forests. */
  private static final List<String> LEFT_OUT = List.of("own_without_similarity",
      "similarity_alone", "own_with_similarity", "union_own",
      "own_retrospective_before_pressure", "executor_project_median");

  /** The Elastic Net models the parameters hold: the six. */
  private static final List<String> CHOSEN = ELASTIC_NET;

  /**
   * The index each model had among the eleven before the fourteen were settled, which picks its
   * forest's point in {@link #forests}, so every model is refitted at the point it was pinned at.
   */
  private static final Map<String, Integer> INDEX_BEFORE = Map.of("alzubaidi_start", 3,
      "kikas_start", 4, "own_retrospective", 6, OWN, 7, KIKAS_RETRO, 9, CEILING, 10);

  /** The references, the Elastic Nets, then the forests. */
  private static final List<String> MODELS = concat(LINEAR, FORESTS);

  /**
   * Every model and comparison before ticket 13 that the fourteen keep, as the code scored them on
   * {@link #corpus} with {@code choices(0.2)} on one thread: per model the error and a hash of
   * every prediction, per comparison the gap and both ends of its interval, as bits. First
   * captured from 0869f6d on the hours; regenerated once by ticket 17, when the corpus moved
   * onto business days through the population, by the code of 11d6710 (main after ticket 15).
   */
  private static final List<String> BEFORE_FORESTS = List.of(
      "global_median 3ff6aaaaaaaaaaab ba75a081",
      "project_median 3ff6666666666666 a8f5a081",
      "alzubaidi_start 3ff7870131e7d2b6 a779127f",
      "kikas_start 3ff6a80b84fdcf1d 4e0d4d23",
      "project_median | global_median bf91111111111111 bfb999999999999a 3fb1111111111111",
      "alzubaidi_start | global_median 3fab8ad0e7a50107 3f51c0787b242b55 3fbb43cf05b8705b",
      "kikas_start | global_median bf44f92d66dc7377 bfaec40b253451e6 3fae1c41b9fd6e4b",
      "alzubaidi_start | project_median 3fb209acb816c4c7 bfa08e7f23ac8e2d 3fc6e03f9097c2c5",
      "kikas_start | project_median 3f906947a5da2d75 bfb4e35018b6d15a 3fbbc13abb7c51a9");

  /**
   * The start models before ticket 22 in the inner scope that the fourteen keep, as the code
   * scored them on {@link #training} with {@code choices(0.2)} on one thread: per model the error
   * and a hash of every prediction, as bits. Captured from 8edd07b (main after ticket 21).
   */
  private static final List<String> INNER_BEFORE_NINTH = List.of(
      "global_median 3ff599999999999a ba75a081",
      "project_median 3ff7bbbbbbbbbbbc 9c75a081",
      "alzubaidi_start 3ff783ccc9c612e5 6c6d99d3",
      "kikas_start 3ff599cb67cd5555 2145ca3c",
      "own_with_similarity_before_pressure 3fddbe5526d3eb72 5abad1d",
      "alzubaidi_start_forest 3ff68d75bff9c77a 43e55399",
      "kikas_start_forest 3ff5dfa9abc1278b 86e19cff",
      "own_with_similarity_before_pressure_forest 3fef018dfd2a94b9 1a143cac");

  private static List<String> concat(final List<String> a, final List<String> b) {
    final List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return List.copyOf(out);
  }

  /**
   * Each model's forest at a point of the declared grid, the leaves differing, picked by the
   * model's index among the eleven before the fourteen ({@link #INDEX_BEFORE}).
   */
  private static Map<String, Forest.Choice> forests() {
    final Map<String, Forest.Choice> out = new LinkedHashMap<>();
    for (final String name : CHOSEN) {
      final int m = INDEX_BEFORE.get(name);
      out.put(name, new Forest.Choice(Forest.MTRY_SHARES[m % 3], 1 + m % 3,
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
      + "by the ratio of rows declared by the study; nothing is swept or chosen")
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
    assertNotEquals(model(r, OWN).predicted()[0], model(other, OWN).predicted()[0]);
  }

  @Test
  @DisplayName("a model without its alpha and lambda, one the chain does not have, a model with "
      + "the similarity medians without its combination or one without them given one, refuses")
  void refusesIncompleteParameters() {
    final Map<String, ElasticNetSelection.Choice> missing = new LinkedHashMap<>(choices(0.2));
    missing.remove(OWN);
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), missing, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> extra = new LinkedHashMap<>(choices(0.2));
    extra.put("previous_own_start", new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), extra, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> noCombination =
        new LinkedHashMap<>(choices(0.2));
    noCombination.put(OWN, new ElasticNetSelection.Choice(0.5, 0.2, null));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), noCombination, forests(), RESERVED));
    final Map<String, ElasticNetSelection.Choice> oneTooMany = new LinkedHashMap<>(choices(0.2));
    oneTooMany.put("kikas_start", new ElasticNetSelection.Choice(0.5, 0.2,
        new ElasticNetSelection.Combination(5, 5, 8)));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), oneTooMany, forests(), RESERVED));
    // a parameters file of the eleven models before the fourteen: refused, in both scopes
    for (final String name : LEFT_OUT.subList(0, 5)) {
      final Map<String, ElasticNetSelection.Choice> old = new LinkedHashMap<>(choices(0.2));
      old.put(name, new ElasticNetSelection.Choice(0.5, 0.2, null));
      for (final SimilarityScoring.Scope scope : SimilarityScoring.Scope.values()) {
        assertThrows(IllegalArgumentException.class,
            () -> SimilarityScoring.run(corpus(), old, forests(), scope), name);
      }
    }
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
    assertTrue(model(r, OWN).fitted().columns().contains("jaccard_median_10_outer"));
  }

  @Test
  @DisplayName("each model with the similarity medians reads them, outer variant, at the Jaccard "
      + "n, embedding n and cluster k chosen for it; the other models read none")
  void eachModelAtItsOwnCombination() {
    final SimilarityScoring.Report r =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED);
    assertTrue(model(r, OWN).fitted().columns().containsAll(List.of(
        "jaccard_median_10_outer", "embedding_median_10_outer", "cluster_median_16_outer")));
    assertTrue(model(r, "own_retrospective").fitted().columns().containsAll(List.of(
        "jaccard_median_5_outer", "embedding_median_10_outer", "cluster_median_32_outer")));
    assertTrue(model(r, CEILING).fitted().columns().containsAll(List.of(
        "jaccard_median_10_outer", "embedding_median_20_outer", "cluster_median_16_outer")));
    for (final String name : List.of("alzubaidi_start", "kikas_start", KIKAS_RETRO)) {
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

  /**
   * The agreed comparisons, in their order: the pairs of the six Elastic Nets, each model against
   * the global median, each but the global median against the project median; then the forests
   * of the same pairs, each forest against the global median, each against the project median.
   */
  private static List<String> agreedPairs() {
    final List<String[]> six = List.of(new String[] {OWN, "alzubaidi_start"},
        new String[] {OWN, "kikas_start"}, new String[] {"own_retrospective", OWN},
        new String[] {CEILING, "own_retrospective"}, new String[] {CEILING, KIKAS_RETRO},
        new String[] {"own_retrospective", KIKAS_RETRO});
    final List<String> out = new ArrayList<>();
    six.forEach(p -> out.add(p[0] + " | " + p[1]));
    for (final String m : LINEAR) {
      if (!m.equals("global_median")) {
        out.add(m + " | global_median");
      }
    }
    for (final String m : LINEAR) {
      if (!m.equals("global_median") && !m.equals("project_median")) {
        out.add(m + " | project_median");
      }
    }
    six.forEach(p -> out.add(p[0] + "_forest | " + p[1] + "_forest"));
    for (final String f : FORESTS) {
      out.add(f + " | global_median");
    }
    for (final String f : FORESTS) {
      out.add(f + " | project_median");
    }
    return List.copyOf(out);
  }

  @Test
  @DisplayName("both scopes score exactly the fourteen models, in their order, and exactly the "
      + "agreed comparisons and ordering intervals, Elastic Net and forest, with and without a "
      + "tail cut, in both readings; no model the fourteen leave out, nor its name in a pair")
  void exactlyTheFourteenAndTheAgreedPairs() {
    final List<String> pairs = agreedPairs();
    assertEquals(6 + 7 + 6 + 6 + 6 + 6, pairs.size());
    assertEquals(pairs.size(), new HashSet<>(pairs).size());
    final List<String> ordering = pairs.stream().filter(p -> !p.endsWith(" | global_median"))
        .toList();
    assertEquals(14, MODELS.size());
    final List<SimilarityScoring.Report> reports = new ArrayList<>();
    for (final SimilarityScoring.Scope scope : SimilarityScoring.Scope.values()) {
      final List<RowCache.Entry> entries = scope == INNER ? training() : corpus();
      reports.add(SimilarityScoring.run(entries, choices(0.2), forests(), scope, 1, 1));
      final SimilarityScoring.Readings cut = SimilarityScoring.readings(withTail(entries),
          choices(0.2), forests(), 1.5, scope, 1, 1);
      reports.add(cut.main());
      reports.add(cut.complementary());
    }
    for (final SimilarityScoring.Report r : reports) {
      final String where = r.scope() + " " + r.reading();
      assertEquals(MODELS, r.models().stream().map(SimilarityScoring.Scored::name).toList(),
          where);
      assertEquals(pairs, r.comparisons().stream().map(g -> g.a() + " | " + g.b()).toList(),
          where);
      assertEquals(ordering, r.orderings().stream().map(o -> o.a() + " | " + o.b()).toList(),
          where);
      for (final var g : r.comparisons()) {
        assertTrue(g.low() <= g.gap() && g.gap() <= g.high(), g.toString());
      }
      for (final String name : LEFT_OUT) {
        for (final String n : List.of(name, name + "_forest")) {
          assertTrue(r.models().stream().noneMatch(m -> m.name().equals(n)), where + n);
          assertTrue(r.comparisons().stream().noneMatch(g -> g.a().equals(n)
              || g.b().equals(n)), where + n);
          assertTrue(r.orderings().stream().noneMatch(o -> o.a().equals(n)
              || o.b().equals(n)), where + n);
        }
      }
      assertEquals(List.of("own_retrospective | " + OWN,
          "own_retrospective_forest | " + OWN + "_forest"),
          r.withCommits().stream().map(g -> g.a() + " | " + g.b()).toList(), where);
    }
    // the outer predictions file names exactly the fourteen
    final List<String> header = List.of(SimilarityScoring.predictions(reports.get(3)).get(0)
        .split("\t"));
    assertEquals(MODELS, header.subList(4, header.size()));
  }

  @Test
  @DisplayName("Al-Zubaidi and Kikas at the start and their comparisons with the references score, "
      + "bit for bit, what they scored before ticket 13, when the matrix lacks the start "
      + "pressure columns: error, predictions, gap and interval")
  void unchangedModelsAsBefore() {
    final SimilarityScoring.Report r = SimilarityScoring.run(without(corpus(), Pressure.COLUMNS),
        choices(0.2), forests(), RESERVED, 1, 1);
    final List<String> now = new ArrayList<>();
    for (final String name : List.of("global_median", "project_median", "alzubaidi_start",
        "kikas_start")) {
      final SimilarityScoring.Scored m = model(r, name);
      now.add(m.name() + " " + hex(m.score().mae()) + " "
          + Integer.toHexString(java.util.Arrays.hashCode(m.predicted())));
    }
    for (final String pair : List.of("project_median | global_median",
        "alzubaidi_start | global_median", "kikas_start | global_median",
        "alzubaidi_start | project_median", "kikas_start | project_median")) {
      final var g = r.comparisons().stream().filter(x -> (x.a() + " | " + x.b()).equals(pair))
          .findFirst().orElseThrow();
      now.add(g.a() + " | " + g.b() + " " + hex(g.gap()) + " " + hex(g.low()) + " "
          + hex(g.high()));
    }
    assertEquals(BEFORE_FORESTS, now);
    // no model reads the start pressure columns: with them, every figure the same
    final SimilarityScoring.Report with =
        SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED, 1, 1);
    assertEquals(innerBits(r), innerBits(with));
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
  @DisplayName("the side reading compares the main reading of the retrospective model with the own "
      + "start model it builds on, Elastic Net and forest, on the scored issues with an "
      + "implementation commit only, resampling their projects")
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
    assertEquals(List.of("own_retrospective | own_with_similarity_before_pressure",
        "own_retrospective_forest | own_with_similarity_before_pressure_forest"),
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
      // no permutation importance on the outer test: it would read its labels
      assertEquals(List.of(), forest.forest().importance(), name);
    }
    assertTrue(model(r, OWN + "_forest").forest().columns().contains("jaccard_median_10_outer"));
    // the leaf is used, not ignored: another one gives other predictions
    final Map<String, Forest.Choice> other = new LinkedHashMap<>(given);
    assertEquals(25, given.get(OWN).nodeSize());
    other.put(OWN, new Forest.Choice(given.get(OWN).share(), given.get(OWN).mtry(), 5));
    assertNotEquals(java.util.Arrays.toString(model(r, OWN + "_forest").predicted()),
        java.util.Arrays.toString(model(SimilarityScoring.run(corpus(), choices(0.2), other,
            RESERVED), OWN + "_forest").predicted()));
    // the inner report reads it: per forest, each design column's, every repeat
    final SimilarityScoring.Report inner =
        SimilarityScoring.run(training(), choices(0.2), given, INNER, 1, 1);
    for (final String name : ELASTIC_NET) {
      final Forest.Frozen f = model(inner, name + "_forest").forest();
      assertEquals(f.columns(), f.importance().stream().map(Forest.Importance::column).toList(),
          name);
      f.importance().forEach(i -> assertEquals(Forest.PERMUTATION_REPEATS,
          i.increases().length));
    }
  }

  @Test
  @DisplayName("the outer test reads no forest's permutation importance: its scores are, bit for "
      + "bit, the same whatever the outer test's labels say beyond the scores themselves")
  void noImportanceOnTheOuterTest() {
    for (final SimilarityScoring.Scope scope : SimilarityScoring.Scope.values()) {
      assertEquals(scope == INNER, scope.importance(), scope.toString());
    }
    final SimilarityScoring.Readings r = SimilarityScoring.readings(corpus(), choices(0.2),
        forests(), 3, RESERVED, 1, 1);
    for (final SimilarityScoring.Report reading : List.of(r.main(), r.complementary())) {
      for (final SimilarityScoring.Scored m : reading.models()) {
        if (m.forest() != null) {
          assertEquals(List.of(), m.forest().importance(), m.name());
        }
      }
    }
  }

  @Test
  @DisplayName("relevance, in both scopes: per Elastic Net model in its order, each standardised "
      + "column of the refit the scores read, its coefficient and selection bit for bit, and its "
      + "interval from resampling the fitting side's whole projects; the same on any number of "
      + "threads, and nothing of it moves with the outer test's durations")
  void relevance() {
    for (final SimilarityScoring.Scope scope : SimilarityScoring.Scope.values()) {
      final List<RowCache.Entry> entries = withTail(scope == INNER ? training() : corpus());
      final SimilarityScoring.Report main = SimilarityScoring.readings(entries, choices(0.2),
          forests(), 1.5, scope, 1, 1).main();
      final List<SimilarityScoring.Relevance> relevance =
          SimilarityScoring.relevance(entries, choices(0.2), 1.5, scope, 1);
      assertEquals(ELASTIC_NET, relevance.stream().map(SimilarityScoring.Relevance::model)
          .toList(), scope.toString());
      boolean spread = false;
      for (final SimilarityScoring.Relevance r : relevance) {
        final Fitting.Fitted f = model(main, r.model()).fitted();
        assertEquals(main.fitRows(), r.fitRows(), r.model());
        assertEquals(6, r.projects(), r.model());
        assertEquals(f.columns(), r.coefficients().stream()
            .map(SimilarityScoring.Coefficient::column).toList(), r.model());
        for (int j = 0; j < f.columns().size(); j++) {
          final Fitting.Contribution x = f.contributions().get(j);
          final SimilarityScoring.Coefficient c = r.coefficients().get(j);
          assertEquals(hex(x.coefficient()), hex(c.coefficient()), r.model() + " " + x.column());
          assertEquals(x.selected(), c.selected(), r.model() + " " + x.column());
          assertTrue(c.low() <= c.high(), c.toString());
          spread |= c.low() < c.high();
        }
      }
      assertTrue(spread, "the draws move some coefficient");
      assertEquals(relevance, SimilarityScoring.relevance(entries, choices(0.2), 1.5, scope, 4));
      if (scope == RESERVED) {
        assertEquals(relevance, SimilarityScoring.relevance(longer(entries,
            e -> "TEST".equals(e.outer(Population.IN_PROGRESS))), choices(0.2), 1.5, scope, 1));
      }
    }
  }

  @Test
  @DisplayName("relevance resamples whole projects: renaming every project moves nothing; one "
      + "project's rows split into two projects moves the intervals, the draws now picking the "
      + "halves apart, but not the refit's coefficients")
  void relevanceDrawsWholeProjects() {
    final List<RowCache.Entry> entries = training();
    final List<SimilarityScoring.Relevance> once =
        SimilarityScoring.relevance(entries, choices(0.2), TailCut.NO_CUT, INNER, 1);
    // every project renamed, its rows kept together: nothing the draws pick changes
    final List<RowCache.Entry> renamed = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final IssueUnit u = e.unit();
      renamed.add(new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), "renamed-" + u.project(),
          u.reporter(), u.executor(), u.createdAt(), u.resolvedAt(), u.reason(), u.moved(),
          u.startedAt()).withBusinessDays(u.businessDays()), e.row(), e.parts()));
    }
    assertEquals(once, SimilarityScoring.relevance(renamed, choices(0.2), TailCut.NO_CUT, INNER,
        1));
    // one project split in two by its rows: the draws now pick its halves apart, and the
    // intervals move
    final List<RowCache.Entry> split = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      final RowCache.Entry e = entries.get(i);
      final IssueUnit u = e.unit();
      split.add(!u.project().equals("p0") || i % 2 == 0 ? e : new RowCache.Entry(new IssueUnit(
          u.issueId(), u.key(), "p0-half", u.reporter(), u.executor(), u.createdAt(),
          u.resolvedAt(), u.reason(), u.moved(), u.startedAt()).withBusinessDays(
              u.businessDays()), e.row(), e.parts()));
    }
    final List<SimilarityScoring.Relevance> halves =
        SimilarityScoring.relevance(split, choices(0.2), TailCut.NO_CUT, INNER, 1);
    assertEquals(7, halves.get(0).projects());
    assertNotEquals(once, halves);
    for (int m = 0; m < once.size(); m++) {
      for (int j = 0; j < once.get(m).coefficients().size(); j++) {
        assertEquals(hex(once.get(m).coefficients().get(j).coefficient()),
            hex(halves.get(m).coefficients().get(j).coefficient()), "the refit is the same");
      }
    }
  }

  @Test
  @DisplayName("forest parameters missing for a model, or given for one the chain does not have, "
      + "refuse the run")
  void refusesIncompleteForests() {
    final Map<String, Forest.Choice> missing = new LinkedHashMap<>(forests());
    missing.remove(CEILING);
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), missing, RESERVED));
    final Map<String, Forest.Choice> extra = new LinkedHashMap<>(forests());
    extra.put("previous_own_start", new Forest.Choice(1.0 / 3, 1, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), extra, RESERVED));
    final Map<String, Forest.Choice> old = new LinkedHashMap<>(forests());
    old.put("union_own", new Forest.Choice(1.0 / 3, 1, 5));
    assertThrows(IllegalArgumentException.class,
        () -> SimilarityScoring.run(corpus(), choices(0.2), old, INNER));
    // more columns per split than the outer design has refuses rather than being cut down
    final Map<String, Forest.Choice> wide = new LinkedHashMap<>(forests());
    wide.put("kikas_start", new Forest.Choice(2.0 / 3, 40, 5));
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
  @DisplayName("in the inner scope the start models and the references score, bit for bit, what "
      + "they scored before the main reading without urgency was added")
  void innerAsBeforeTheNinth() {
    final Map<String, String> now = innerBits(
        SimilarityScoring.run(training(), choices(0.2), forests(), INNER, 1, 1));
    final List<String> models = new ArrayList<>();
    for (final String line : INNER_BEFORE_NINTH) {
      final String name = line.substring(0, line.indexOf(' '));
      models.add(name + " " + now.get(name));
    }
    assertEquals(INNER_BEFORE_NINTH, models);
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
    assertTrue(model(r, OWN).fitted().columns().contains("jaccard_median_10_inner"));
    assertTrue(model(r, OWN).removed().stream()
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
  @DisplayName("in both scopes the retrospective models read their own columns: Kikas's only "
      + "Kikas's, the main reading urgency but no comments, participants, blocking wait or start "
      + "pressure column, the ceiling the main reading's and the comments and the participants; "
      + "each forest its Elastic Net's")
  void retrospectiveModelsInBothScopes() {
    for (final SimilarityScoring.Scope scope : SimilarityScoring.Scope.values()) {
      final SimilarityScoring.Report r = SimilarityScoring.run(scope == INNER ? training()
          : corpus(), choices(0.2), forests(), scope, 1, 1);
      final List<String> kikas = model(r, KIKAS_RETRO).fitted().columns();
      assertTrue(kikas.stream().allMatch(c -> c.startsWith("K") || c.startsWith("LBL_")),
          kikas.toString());
      assertTrue(kikas.containsAll(List.of("K03_comments", "K10_mean_comment_words",
          "K21_participant_committers")), kikas.toString());
      assertEquals(kikas, model(r, KIKAS_RETRO + "_forest").forest().columns());
      final List<String> main = model(r, "own_retrospective").fitted().columns();
      assertTrue(main.contains("RETRO_urgency_days"), main.toString());
      assertTrue(main.stream().noneMatch(c -> c.startsWith("START_")
          || c.startsWith("RETRO_blocker_wait") || c.startsWith("RETRO_comments")
          || c.startsWith("RETRO_participants")), main.toString());
      final List<String> ceiling = new ArrayList<>(main.stream()
          .filter(c -> c.startsWith("RETRO_")).toList());
      ceiling.addAll(List.of("RETRO_comments", "RETRO_participants"));
      assertEquals(ceiling, model(r, CEILING).fitted().columns().stream()
          .filter(c -> c.startsWith("RETRO_")).toList());
      for (final String name : ELASTIC_NET) {
        assertEquals(model(r, name).fitted().columns(),
            model(r, name + "_forest").forest().columns(), name);
      }
    }
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
    assertTrue(expected.contains("own_with_similarity_before_pressure | alzubaidi_start"));
    assertTrue(expected.contains("own_retrospective_with_discussion | kikas_retrospective"));
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
    assertEquals(List.of("own_retrospective | own_with_similarity_before_pressure",
        "own_retrospective_forest | own_with_similarity_before_pressure_forest"),
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

  /**
   * The six attribute sets the scope of fourteen models keeps, each with its forest beside it:
   * the own start model, Al-Zubaidi and Kikas at the start, both readings of the own
   * retrospective model and Kikas's retrospective one.
   */
  private static final List<String> SIX_SETS = List.of("own_with_similarity_before_pressure",
      "alzubaidi_start", "kikas_start", "own_retrospective", "own_retrospective_with_discussion",
      "kikas_retrospective");

  /** The sets the outer test did not score before the fourteen models: the ceiling and Kikas's. */
  private static final List<String> NOT_OUTER_BEFORE = List.of(
      "own_retrospective_with_discussion", "kikas_retrospective");

  /**
   * Whether a model is one of the fourteen and was scored in the scope before they were settled:
   * a reference median but the executor-project one, or one of {@link #SIX_SETS} or its forest,
   * the outer test without the ceiling and Kikas's retrospective one.
   */
  private static boolean ofTheFourteen(final String name, final SimilarityScoring.Scope scope) {
    final String set = name.endsWith("_forest")
        ? name.substring(0, name.length() - "_forest".length()) : name;
    if (name.equals("global_median") || name.equals("project_median")) {
      return true;
    }
    return SIX_SETS.contains(set) && (scope == INNER || !NOT_OUTER_BEFORE.contains(set));
  }

  /**
   * Whether a comparison or ordering key ({@code a | b}, perhaps behind a word) is of two of the
   * fourteen and existed before they were settled: not a forest against its own Elastic Net, nor
   * the own start model against a literature start model, which the fourteen add.
   */
  private static boolean pairOfTheFourteen(final String key,
      final SimilarityScoring.Scope scope) {
    final String[] ab = key.substring(key.lastIndexOf(' ', key.indexOf(" | ") - 1) + 1)
        .split(" \\| ");
    final boolean literature = ab[0].startsWith("own_with_similarity_before_pressure")
        && (ab[1].startsWith("alzubaidi_start") || ab[1].startsWith("kikas_start"));
    return ofTheFourteen(ab[0], scope) && ofTheFourteen(ab[1], scope)
        && !ab[0].equals(ab[1] + "_forest") && !literature;
  }

  /**
   * One reading's bits of the fourteen models as they were before they were settled: the models,
   * comparisons and ordering intervals of {@link #innerBits} of the fourteen and their pairs, the
   * side reading on the issues with and without an implementation commit, the fences and the
   * band limits; their count and a hash of them, sorted, so the models' order does not count.
   */
  private static String fourteenBits(final SimilarityScoring.Report r) {
    final List<String> all = new ArrayList<>();
    innerBits(r).forEach((k, v) -> {
      if (k.contains(" | ") ? pairOfTheFourteen(k, r.scope()) : ofTheFourteen(k, r.scope())) {
        all.add(k + " " + v);
      }
    });
    for (final var g : r.withCommits()) {
      all.add("with " + g.a() + " | " + g.b() + " " + hex(g.gap()) + " " + hex(g.low()) + " "
          + hex(g.high()));
    }
    for (final var g : r.withoutCommits()) {
      all.add("without " + g.a() + " | " + g.b() + " " + hex(g.gap()) + " " + hex(g.low()) + " "
          + hex(g.high()));
    }
    all.add(r.fences() + " " + java.util.Arrays.toString(r.bandTops()));
    java.util.Collections.sort(all);
    return all.size() + " " + Integer.toHexString(all.hashCode());
  }

  /**
   * The fourteen models as the code scored them before they were settled: per scope (inner, then
   * outer), with a tail cut at k = 1.5 in each reading (main, then complementary), then without a
   * cut on every scored issue, inner then outer, {@link #fourteenBits}. Captured from e67f85f
   * (ticket 29), where the pins of the inner run without a cut (ticket 22) and of the fixture
   * with a tail (ticket 27), which named models the fourteen leave out, held: it carries them for
   * the fourteen.
   */
  private static final List<String> FOURTEEN_AT_TICKET_29 = List.of("72 8a61ae68",
      "72 77f3d859", "44 d8d7924d", "44 d8d7924d", "72 97fa0fdb", "44 b9607449");

  @Test
  @DisplayName("the fourteen models, their comparisons, ordering intervals and side reading score, "
      + "bit for bit, what they scored at ticket 29, in both scopes, with and without a tail cut")
  void fourteenAsAtTicket29() {
    final List<String> now = new ArrayList<>();
    for (final SimilarityScoring.Readings r : List.of(
        SimilarityScoring.readings(withTail(training()), choices(0.2), forests(), 1.5, INNER, 1,
            1),
        SimilarityScoring.readings(withTail(corpus()), choices(0.2), forests(), 1.5, RESERVED, 1,
            1))) {
      now.add(fourteenBits(r.main()));
      now.add(fourteenBits(r.complementary()));
    }
    now.add(fourteenBits(SimilarityScoring.run(training(), choices(0.2), forests(), INNER, 1, 1)));
    now.add(fourteenBits(SimilarityScoring.run(corpus(), choices(0.2), forests(), RESERVED, 1,
        1)));
    assertEquals(FOURTEEN_AT_TICKET_29, now);
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
    assertEquals(MODELS, c.models().stream().map(SimilarityScoring.Scored::name).toList());
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
    // the calibration covers exactly the fourteen models the outer test scores
    assertEquals(Set.copyOf(MODELS), calibrated.keySet());
    // the constant global median gets a finite factor at every level, inner
    for (final double factor : calibrated.get(SimilarityScoring.GLOBAL).factors()) {
      assertTrue(Double.isFinite(factor), Double.toString(factor));
    }
    final List<PredictionIntervals.Read> reads = PredictionIntervals.outer(calibrated,
        PredictionIntervals.Sample.of(outer.main()),
        PredictionIntervals.Sample.of(outer.complementary()), 1);
    assertEquals(2 * MODELS.size() * PredictionIntervals.LEVELS.size(), reads.size());
    assertEquals(Set.copyOf(MODELS), reads.stream().map(PredictionIntervals.Read::model)
        .collect(java.util.stream.Collectors.toSet()));
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
