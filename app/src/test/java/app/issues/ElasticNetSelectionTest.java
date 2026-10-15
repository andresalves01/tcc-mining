package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.ElasticNetSelection.Combination;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ParallelSweepTest;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Elastic Net models of the description-similarity chain, each one's alpha and lambda and,
 * for the three reading the similarity medians, their n, n and k, chosen on the inner validation.
 */
class ElasticNetSelectionTest {

  private static final Combination TEN_FIVE_32 = new Combination(10, 5, 32);

  private static final List<String> NINE_INNER = List.of("A01_desc_length", "TEAM_loc_median",
      "TEAM_loc_sd", "TEAM_mccc_median", "TEAM_mccc_sd", "reporter_median_inner",
      "A09_exec_loc_tot", "A10_exec_mccc_tot", "A13_exec_open_load");

  private static final List<String> SIMILARITY_INNER = List.of("jaccard_median_10_inner",
      "embedding_median_5_inner", "cluster_median_32_inner");

  private static final List<String> WITHOUT = List.of("previous_own_start", "revised_nine",
      "alzubaidi_start", "kikas_start");

  private static final List<String> WITH = List.of("similarity_alone", "revised_twelve",
      "union_revised");

  /**
   * The four models without the similarity medians as the code before ticket 11 chose them on
   * {@link #full}, with the direct predictors' values 10, 5 and 32 (which those models never
   * read): columns, alpha, lambda and error as raw bits, the sweep's step, and a hash of every
   * prediction's bits. Captured from commit 793002d.
   */
  private static final List<String> BEFORE = List.of(
      "previous_own_start [A01_desc_length, A03_team_size, A09_exec_loc_tot, A10_exec_mccc_tot, "
          + "A13_exec_open_load] 3fee666666666666 3f627a0bd85c04e7 11 4053bd0aa481744b fd91daf0",
      "revised_nine [A01_desc_length, TEAM_loc_median, TEAM_loc_sd, TEAM_mccc_median, "
          + "TEAM_mccc_sd, reporter_median_inner, reporter_median_inner_ausente, "
          + "A09_exec_loc_tot, A10_exec_mccc_tot, A13_exec_open_load] 3fee666666666666 "
          + "3fa8262ebc34ffce 9 404b8031bd17190e 7b93c7c5",
      "alzubaidi_start [Z08_priority] 3fee666666666666 3f7f2bbb8f5b3bab 7 405558e882b32458 "
          + "11104a89",
      "kikas_start [K14_project_issues_90d] 3fee666666666666 3f607dc01af1707f 11 "
          + "405553055084efd2 e71ee1be");

  /**
   * The three models with the similarity medians as the code before ticket 11 chose them on
   * {@link #full} at Jaccard 10, embedding 5 and cluster 32: alpha, lambda and error as raw bits.
   * Captured from commit 793002d.
   */
  private static final Map<String, String> BEFORE_AT_TEN_FIVE_32 = Map.of(
      "similarity_alone", "3fee666666666666 3f84f7c0911875b7 401a2edcd0c015b6",
      "revised_twelve", "3fee666666666666 3ff8e5d537535666 401e862eb0f33d7d",
      "union_revised", "3fee666666666666 3ff8e5d537535666 401ea4a255a91da0");

  private static final Instant T = Instant.parse("2016-01-01T00:00:00Z");

  private final List<RowCache.Entry> entries = new ArrayList<>();

  /** An issue of {@code hours} in the given split; a null value is a missing one. */
  private void row(final String id, final double hours, final String outer, final String inner,
      final Map<String, Double> values) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant start = T.plusSeconds(3600L * entries.size());
    final Map<String, Double> present = new HashMap<>();
    final Set<String> absent = new HashSet<>();
    values.forEach((k, v) -> {
      if (v == null) {
        absent.add(k);
      } else {
        present.put(k, v);
      }
    });
    entries.add(new RowCache.Entry(new IssueUnit(id, "P-" + id, "p" + id.hashCode() % 3, "r",
        "d", T, start.plusMillis((long) (hours * 3_600_000)), "Fixed", false, start),
        new Row(id, present, absent), parts));
  }

  /** The columns every model but the similarity medians reads, noise around nothing. */
  private static Map<String, Double> base(final Random random, final double hours, final int i) {
    final Map<String, Double> v = new HashMap<>();
    v.put("A01_desc_length", (double) random.nextInt(500));
    v.put("A01_desc_length_ausente", 0.0);
    v.put("A03_team_size", (double) random.nextInt(20));
    v.put("Z08_priority", (double) random.nextInt(5));
    v.put("Z08_priority_ausente", 0.0);
    v.put("K14_project_issues_90d", (double) random.nextInt(100));
    for (final String executor : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
        "A13_exec_open_load")) {
      v.put(executor, (double) random.nextInt(1000));
      v.put(executor + "_ausente", 0.0);
    }
    for (final String team : Extractor.TEAM_REVISED) {
      v.put(team, 1000 * random.nextDouble());
    }
    v.put("reporter_median_inner", i % 5 == 0 ? null : hours * (0.5 + random.nextDouble()));
    v.put("reporter_median_outer", 500 * random.nextDouble());
    return v;
  }

  /**
   * Forty inner fitting and twenty inner validation rows with every candidate column of both
   * variants: each similarity median follows the duration with its own noise, wider the larger
   * its n or k, so the combinations differ; the reporter median is missing on every fifth row.
   */
  private void full() {
    final Random random = new Random(13);
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      for (final Augmentation.Variant variant : Augmentation.Variant.values()) {
        for (final int n : Augmentation.JACCARD_N) {
          v.put(Augmentation.jaccard(n, variant), hours * (1 + n / 10.0 * random.nextDouble()));
        }
        for (final int n : Augmentation.EMBEDDING_N) {
          v.put(Augmentation.embedding(n, variant),
              hours * (1 + n / 8.0 * random.nextDouble()));
        }
        for (final int k : Augmentation.CLUSTER_K) {
          v.put(Augmentation.cluster(k, variant), hours * (1 + k / 16.0 * random.nextDouble()));
        }
      }
      row("f" + i, hours, "TRAIN", i < 40 ? "TRAIN" : "TEST", v);
    }
  }

  /**
   * As {@link #full}, but every candidate value of a method has the same value on a row: all 27
   * combinations read the same numbers under different names, so their errors tie exactly.
   */
  private void tied() {
    final Random random = new Random(17);
    for (int i = 0; i < 60; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
      final Map<String, Double> v = base(random, hours, i);
      final double jaccard = hours * (1 + random.nextDouble());
      final double embedding = hours * (1 + random.nextDouble());
      final double cluster = hours * (1 + random.nextDouble());
      for (final Augmentation.Variant variant : Augmentation.Variant.values()) {
        Augmentation.JACCARD_N.forEach(n -> v.put(Augmentation.jaccard(n, variant), jaccard));
        Augmentation.EMBEDDING_N.forEach(n -> v.put(Augmentation.embedding(n, variant),
            embedding));
        Augmentation.CLUSTER_K.forEach(k -> v.put(Augmentation.cluster(k, variant), cluster));
      }
      row("t" + i, hours, "TRAIN", i < 40 ? "TRAIN" : "TEST", v);
    }
  }

  private static ElasticNetSelection.Model model(final ElasticNetSelection.Result r,
      final String name) {
    return r.models().stream().filter(m -> m.cell().label().equals(name)).findFirst()
        .orElseThrow();
  }

  private static List<String> names(final Cell cell) {
    return cell.attributes().stream().map(Attribute::name).toList();
  }

  private static List<String> registered(final String label) {
    return names(Cell.byLabel(label, Population.IN_PROGRESS));
  }

  private static List<String> concat(final List<String> a, final List<String> b) {
    final List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static long bits(final double d) {
    return Double.doubleToRawLongBits(d);
  }

  private static String hex(final double d) {
    return Long.toHexString(bits(d));
  }

  /** A swept model as bits: columns, alpha, lambda, step, error and every prediction. */
  private static String fingerprint(final ElasticNetSelection.Model m) {
    final Fitting.Fitted f = m.fitted();
    return m.cell().label() + " " + f.columns() + " " + hex(f.alpha()) + " " + hex(f.lambda())
        + " " + f.step() + " " + hex(f.score().maeHours()) + " "
        + Integer.toHexString(java.util.Arrays.hashCode(f.predictedHours()));
  }

  @Test
  @DisplayName("the 27 combinations are every Jaccard n, embedding n and cluster k the stage "
      + "computes, smaller values first in the order Jaccard, embedding, cluster")
  void combinationsInTheirOrder() {
    final List<Combination> all = Combination.all();
    assertEquals(Augmentation.JACCARD_N.size() * Augmentation.EMBEDDING_N.size()
        * Augmentation.CLUSTER_K.size(), all.size());
    assertEquals(27, all.size());
    assertEquals(new Combination(5, 5, 8), all.get(0));
    assertEquals(new Combination(5, 5, 16), all.get(1));
    assertEquals(new Combination(5, 10, 8), all.get(3));
    assertEquals(new Combination(10, 5, 8), all.get(9));
    assertEquals(new Combination(20, 20, 32), all.get(26));
    assertEquals(27, new HashSet<>(all).size());
  }

  @Test
  @DisplayName("each model reads exactly the spec's columns, the split-dependent ones in the inner "
      + "variant and the similarity medians at the combination given")
  void columnsOfEachModel() {
    final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.INNER);
    assertEquals(List.of("previous_own_start", "revised_nine", "similarity_alone",
        "revised_twelve", "alzubaidi_start", "kikas_start", "union_revised"),
        cells.stream().map(Cell::label).toList());
    assertEquals(ElasticNetSelection.MODELS, cells.stream().map(Cell::label).toList());
    assertEquals(registered("OWN / C1"), names(cells.get(0)));
    assertEquals(NINE_INNER, names(cells.get(1)));
    assertEquals(SIMILARITY_INNER, names(cells.get(2)));
    final List<String> twelve = concat(NINE_INNER, SIMILARITY_INNER);
    assertEquals(twelve, names(cells.get(3)));
    assertEquals(registered("ALZUBAIDI / C1"), names(cells.get(4)));
    assertEquals(registered("KIKAS / C1"), names(cells.get(5)));
    // the literature families as the registered union reads them -- Kikas's body length enters
    // once, as Al-Zubaidi's title and description words -- with the revised twelve
    final List<String> literature = concat(registered("ALZUBAIDI / C1"),
        registered("KIKAS / C1").stream().filter(n -> !n.equals("K02_body_len")).toList());
    assertEquals(concat(literature, twelve), names(cells.get(6)));
  }

  @Test
  @DisplayName("the outer variant reads the outer columns, for the test that refits on them")
  void outerVariant() {
    final List<Cell> cells = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.OUTER);
    assertEquals(List.of("A01_desc_length", "TEAM_loc_median", "TEAM_loc_sd",
        "TEAM_mccc_median", "TEAM_mccc_sd", "reporter_median_outer", "A09_exec_loc_tot",
        "A10_exec_mccc_tot", "A13_exec_open_load", "jaccard_median_10_outer",
        "embedding_median_5_outer", "cluster_median_32_outer"), names(cells.get(3)));
  }

  @Test
  @DisplayName("given one combination per model, each model with the similarity medians reads its "
      + "own; one missing, or one given to a model without them, is refused")
  void cellsOfOneCombinationPerModel() {
    final Map<String, Combination> each = Map.of("similarity_alone", new Combination(5, 20, 8),
        "revised_twelve", TEN_FIVE_32, "union_revised", new Combination(20, 10, 16));
    final List<Cell> cells = ElasticNetSelection.cells(each, Augmentation.Variant.OUTER);
    final List<Cell> atTen = ElasticNetSelection.cells(TEN_FIVE_32, Augmentation.Variant.OUTER);
    assertEquals(List.of("jaccard_median_5_outer", "embedding_median_20_outer",
        "cluster_median_8_outer"), names(cells.get(2)));
    assertEquals(atTen.get(3), cells.get(3));
    final List<String> union = names(cells.get(6));
    assertEquals(List.of("jaccard_median_20_outer", "embedding_median_10_outer",
        "cluster_median_16_outer"), union.subList(union.size() - 3, union.size()));
    for (final int m : List.of(0, 1, 4, 5)) {
      assertEquals(atTen.get(m), cells.get(m));
    }

    final Map<String, Combination> missing = new HashMap<>(each);
    missing.remove("union_revised");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(missing, Augmentation.Variant.INNER));
    final Map<String, Combination> extra = new HashMap<>(each);
    extra.put("revised_nine", TEN_FIVE_32);
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.cells(extra, Augmentation.Variant.INNER));
  }

  @Test
  @DisplayName("the four models without the similarity medians choose, bit for bit, what they "
      + "chose before ticket 11, and the other three's sweep at Jaccard 10, embedding 5 and "
      + "cluster 32 is the one the earlier code ran at those values")
  void unchangedModelsAsBefore() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries, 1);
    assertEquals(BEFORE, WITHOUT.stream().map(name -> fingerprint(model(r, name))).toList());
    for (final String name : WITHOUT) {
      assertNull(model(r, name).combination(), name);
      assertEquals(List.of(), model(r, name).combinations(), name);
    }
    for (final String name : WITH) {
      final ElasticNetSelection.Swept at = model(r, name).combinations().stream()
          .filter(s -> s.combination().equals(TEN_FIVE_32)).findFirst().orElseThrow();
      assertEquals(BEFORE_AT_TEN_FIVE_32.get(name),
          hex(at.alpha()) + " " + hex(at.lambda()) + " " + hex(at.maeHours()), name);
    }
  }

  @Test
  @DisplayName("each model with the similarity medians keeps, of the 27 combinations each swept "
      + "over alpha and lambda, the one with the lowest inner validation error")
  void keepsTheLowestCombination() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries);
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      assertEquals(Combination.all(),
          m.combinations().stream().map(ElasticNetSelection.Swept::combination).toList(), name);
      final double lowest = m.combinations().stream()
          .mapToDouble(ElasticNetSelection.Swept::maeHours).min().orElseThrow();
      assertEquals(bits(lowest), bits(m.fitted().score().maeHours()), name);
      final ElasticNetSelection.Swept chosen = m.combinations().stream()
          .filter(s -> s.combination().equals(m.combination())).findFirst().orElseThrow();
      assertEquals(bits(lowest), bits(chosen.maeHours()), name);
      assertEquals(bits(chosen.alpha()), bits(m.fitted().alpha()), name);
      assertEquals(bits(chosen.lambda()), bits(m.fitted().lambda()), name);
      final Combination c = m.combination();
      assertTrue(m.fitted().columns().containsAll(List.of(
          Augmentation.jaccard(c.jaccardN(), Augmentation.Variant.INNER),
          Augmentation.embedding(c.embeddingN(), Augmentation.Variant.INNER),
          Augmentation.cluster(c.clusterK(), Augmentation.Variant.INNER))), name);
      assertEquals(ElasticNetSelection.cells(c, Augmentation.Variant.INNER).stream()
          .filter(x -> x.label().equals(name)).findFirst().orElseThrow(), m.cell(), name);
    }
    // the fixture makes the combinations differ, so a choice is not the first by default
    assertTrue(WITH.stream().allMatch(name -> model(r, name).combinations().stream()
        .mapToLong(s -> bits(s.maeHours())).distinct().count() > 1));
  }

  @Test
  @DisplayName("combinations whose lowest errors tie exactly go to the smaller values, Jaccard n "
      + "first, then embedding n, then cluster k")
  void tiesGoToTheSmallerValues() {
    tied();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries);
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      assertEquals(1, m.combinations().stream().mapToLong(s -> bits(s.maeHours())).distinct()
          .count(), name + " does not tie: " + m.combinations());
      assertEquals(new Combination(5, 5, 8), m.combination(), name);
    }
  }

  @Test
  @DisplayName("a column missing on every inner fitting row is removed from each model reading it, "
      + "with the reason recorded, and its validation values are never imputed from")
  void removesAColumnMissingInTheFittingSet() {
    full();
    final List<RowCache.Entry> blanked = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final boolean fitting = "TRAIN".equals(e.inner(Population.IN_PROGRESS));
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      if (fitting) {
        for (final int k : Augmentation.CLUSTER_K) {
          values.remove(Augmentation.cluster(k, Augmentation.Variant.INNER));
          absent.add(Augmentation.cluster(k, Augmentation.Variant.INNER));
        }
      }
      blanked.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent),
          e.parts()));
    }
    final ElasticNetSelection.Result r = ElasticNetSelection.run(blanked);
    assertEquals(40, r.fitRows());
    assertEquals(20, r.validationRows());
    for (final String name : WITH) {
      final ElasticNetSelection.Model m = model(r, name);
      // the union also loses the literature columns this fixture leaves out
      assertEquals(List.of(new ElasticNetSelection.Removed(
          Augmentation.cluster(m.combination().clusterK(), Augmentation.Variant.INNER),
          "missing on all 40 inner fitting rows")), m.removed().stream()
              .filter(x -> x.column().contains("_median_")).toList(), name);
      assertFalse(m.fitted().columns().stream().anyMatch(c -> c.startsWith("cluster_")));
    }
    assertEquals(List.of(), model(r, "revised_nine").removed());
    assertEquals(1, model(r, "revised_twelve").removed().size());
  }

  @Test
  @DisplayName("on several threads every choice, sweep and error is, bit for bit, what one thread "
      + "gives, the models and combinations in the same order")
  void parallelIsSequential() {
    full();
    final ElasticNetSelection.Result one = ElasticNetSelection.run(entries, 1);
    final ElasticNetSelection.Result many = ElasticNetSelection.run(entries, 6);
    assertEquals(one.models().size(), many.models().size());
    assertEquals(bits(one.projectMaeHours()), bits(many.projectMaeHours()));
    for (int m = 0; m < one.models().size(); m++) {
      assertEquals(one.models().get(m).cell(), many.models().get(m).cell());
      assertEquals(one.models().get(m).combination(), many.models().get(m).combination());
      assertEquals(one.models().get(m).removed(), many.models().get(m).removed());
      assertEquals(one.models().get(m).combinations(), many.models().get(m).combinations());
      ParallelSweepTest.assertIdentical(one.models().get(m).fitted(),
          many.models().get(m).fitted());
    }
    assertEquals(ElasticNetSelection.parameters(one, "m"),
        ElasticNetSelection.parameters(many, "m"));
  }

  /** What a choice is: per model, its combination, columns, alpha, lambda and error. */
  private static List<String> choices(final ElasticNetSelection.Result r) {
    return r.models().stream().map(m -> m.cell().label() + " " + m.combination() + " "
        + m.fitted().columns() + " " + m.fitted().alpha() + " " + m.fitted().lambda() + " "
        + m.fitted().score().maeHours() + " " + m.combinations()).toList();
  }

  @Test
  @DisplayName("in inner mode the outer variant is never read: changing every outer column "
      + "changes no choice")
  void readsTheInnerVariant() {
    full();
    final List<String> before = choices(ElasticNetSelection.run(entries));
    final List<RowCache.Entry> changed = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.replaceAll((k, v) -> k.endsWith("_outer") ? v * 1000 + 7 : v);
      changed.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    final ElasticNetSelection.Result r = ElasticNetSelection.run(changed);
    assertEquals(before, choices(r));
    assertTrue(model(r, "revised_twelve").fitted().columns().stream()
        .noneMatch(c -> c.contains("_outer")));
  }

  @Test
  @DisplayName("the outer test rows are never read: neither their durations nor their values, "
      + "not even values that would refuse the run")
  void neverReadsTheOuterTest() {
    full();
    final List<String> before = choices(ElasticNetSelection.run(entries));
    final Map<String, Double> poison = new HashMap<>();
    for (final String column : List.of("reporter_median_inner", "TEAM_loc_median")) {
      poison.put(column, Double.NaN);
    }
    for (final String column : Augmentation.columns()) {
      poison.put(column, Double.NaN);
    }
    for (int k = 0; k < 10; k++) {
      row("t" + k, 90_000, "TEST", "", poison);
      row("x" + k, 90_000, "CROSSES", "", poison);
    }
    assertEquals(before, choices(ElasticNetSelection.run(entries)));
  }

  @Test
  @DisplayName("a missing value of a new column is handed on as missing, with its indicator "
      + "beside it; a value that is not a number refuses")
  void missingIsHandedOnWithItsIndicator() {
    full();
    final List<String> columns =
        model(ElasticNetSelection.run(entries), "revised_nine").fitted().columns();
    assertTrue(columns.contains("reporter_median_inner"));
    assertTrue(columns.contains("reporter_median_inner_ausente"));
    // observed on every fitting row: the indicator is constant there, and the preparation drops it
    assertFalse(columns.contains("TEAM_loc_median_ausente"));

    row("nan", 10, "TRAIN", "TRAIN", Map.of("TEAM_loc_sd", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.run(entries));
  }

  @Test
  @DisplayName("the three registered cells choose the alpha and lambda the registered grid does")
  void registeredCellsAsTheGrid() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries);
    final app.pipeline.IssuesGridTask.Report grid =
        new app.pipeline.IssuesGridTask(app.pipeline.port.Progress.silent()).run(entries,
            Population.IN_PROGRESS);
    for (final String[] pair : new String[][] {{"previous_own_start", "OWN / C1"},
        {"alzubaidi_start", "ALZUBAIDI / C1"}, {"kikas_start", "KIKAS / C1"}}) {
      final Fitting.Fitted registered = grid.fits().stream()
          .map(app.pipeline.IssuesGridTask.Timed::fitted)
          .filter(f -> f.cell().label().equals(pair[1])).findFirst().orElseThrow();
      final Fitting.Fitted mine = model(r, pair[0]).fitted();
      assertEquals(registered.alpha(), mine.alpha(), pair[0]);
      assertEquals(registered.lambda(), mine.lambda(), pair[0]);
      assertEquals(registered.columns(), mine.columns(), pair[0]);
      assertEquals(registered.score().maeHours(), mine.score().maeHours(), pair[0]);
    }
  }

  @Test
  @DisplayName("the parameters file holds the matrix's hash, each model's alpha and lambda, and "
      + "the chosen n, n and k of the three with the similarity medians, read back exactly")
  void parametersFile() {
    full();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries);
    final List<String> lines = ElasticNetSelection.parameters(r, "cd34");
    assertEquals(ElasticNetSelection.HEADER, lines.get(0));
    final List<String> data = lines.stream().filter(l -> !l.startsWith("#")).toList();
    assertEquals("elastic-net\tinputs\tmatrix_sha256\tcd34", data.get(0));
    assertEquals(1 + 2 * 7 + 3 * 3, data.size());
    assertTrue(data.stream().noneMatch(l -> l.contains("similarity_parameters")));
    final ElasticNetSelection.Model twelve = model(r, "revised_twelve");
    assertTrue(data.contains("elastic-net\trevised_twelve\talpha\t" + twelve.fitted().alpha()));
    assertTrue(data.contains("elastic-net\trevised_twelve\tjaccard_n\t"
        + twelve.combination().jaccardN()));
    assertTrue(data.contains("elastic-net\trevised_twelve\tembedding_n\t"
        + twelve.combination().embeddingN()));
    assertTrue(data.contains("elastic-net\trevised_twelve\tcluster_k\t"
        + twelve.combination().clusterK()));
    assertTrue(data.stream().noneMatch(l -> l.startsWith("elastic-net\trevised_nine\tjaccard")));

    final List<ElasticNetSelection.Parameter> read = ElasticNetSelection.read(lines);
    final Map<String, ElasticNetSelection.Choice> back = ElasticNetSelection.choices(read);
    assertEquals(ElasticNetSelection.MODELS, List.copyOf(back.keySet()));
    for (final ElasticNetSelection.Model m : r.models()) {
      assertEquals(new ElasticNetSelection.Choice(m.fitted().alpha(), m.fitted().lambda(),
          m.combination()), back.get(m.cell().label()));
    }
    assertEquals("cd34", ElasticNetSelection.matrixSha(read));
  }

  @Test
  @DisplayName("a model with only part of its n, n and k, or a value the stage does not compute, "
      + "is refused; so is a malformed or repeated line")
  void refusesIncompleteChoices() {
    final List<String> head = List.of(ElasticNetSelection.HEADER,
        "elastic-net\tinputs\tmatrix_sha256\tcd34", "elastic-net\tsimilarity_alone\talpha\t0.5",
        "elastic-net\tsimilarity_alone\tlambda\t0.1",
        "elastic-net\tsimilarity_alone\tjaccard_n\t10",
        "elastic-net\tsimilarity_alone\tembedding_n\t5");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(head)));
    final List<String> odd = new ArrayList<>(head);
    odd.add("elastic-net\tsimilarity_alone\tcluster_k\t12");
    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.choices(ElasticNetSelection.read(odd)));
    final List<String> whole = new ArrayList<>(head);
    whole.add("elastic-net\tsimilarity_alone\tcluster_k\t16");
    assertEquals(new Combination(10, 5, 16), ElasticNetSelection.choices(
        ElasticNetSelection.read(whole)).get("similarity_alone").combination());

    assertThrows(IllegalArgumentException.class,
        () -> ElasticNetSelection.read(List.of("elastic-net\tx")));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.read(List.of(
        "elastic-net\tx\talpha\t0.5", "elastic-net\tx\talpha\t0.1")));
  }

  @Test
  @DisplayName("the four split sizes are counted from the split labels alone")
  void sizes() {
    row("a", 10, "TRAIN", "TRAIN", Map.of());
    row("b", 20, "TRAIN", "TRAIN", Map.of());
    row("c", 30, "TRAIN", "TRAIN", Map.of());
    row("v", 100, "TRAIN", "TEST", Map.of());
    row("t", 1, "TEST", "", Map.of());
    row("u", 1, "TEST", "", Map.of());
    row("x", 1, "CROSSES", "", Map.of());
    assertEquals(new RegistrationInProgress.Sizes(4, 2, 3, 1),
        ElasticNetSelection.sizes(entries));
  }
}
