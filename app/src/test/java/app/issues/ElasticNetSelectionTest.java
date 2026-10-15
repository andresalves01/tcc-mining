package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.issues.model.Cell;
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

/** The Elastic Net models of the description-similarity chain, chosen on the inner validation. */
class ElasticNetSelectionTest {

  private static final Map<String, Integer> CHOSEN =
      Map.of("jaccard_median", 10, "embedding_median", 5, "cluster_median", 32);

  private static final List<String> NINE_INNER = List.of("A01_desc_length", "TEAM_loc_median",
      "TEAM_loc_sd", "TEAM_mccc_median", "TEAM_mccc_sd", "reporter_median_inner",
      "A09_exec_loc_tot", "A10_exec_mccc_tot", "A13_exec_open_load");

  private static final List<String> SIMILARITY_INNER = List.of("jaccard_median_10_inner",
      "embedding_median_5_inner", "cluster_median_32_inner");

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

  /**
   * Forty inner fitting and fifteen inner validation rows, every model with columns to read: the
   * similarity medians follow the duration, the rest is noise; the reporter median is missing on
   * every third row, the cluster median of the chosen k on every fitting row.
   */
  private void inner() {
    final Random random = new Random(7);
    for (int i = 0; i < 55; i++) {
      final double hours = Math.exp(1 + 5 * random.nextDouble());
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
      v.put("reporter_median_inner", i % 3 == 0 ? null : 500 * random.nextDouble());
      v.put("reporter_median_outer", 500 * random.nextDouble());
      v.put("jaccard_median_10_inner", hours * (0.8 + 0.4 * random.nextDouble()));
      v.put("embedding_median_5_inner", hours * (0.8 + 0.4 * random.nextDouble()));
      v.put("jaccard_median_10_outer", 1.0);
      v.put("embedding_median_5_outer", 1.0);
      v.put("cluster_median_32_outer", 1.0);
      final boolean fitting = i < 40;
      v.put("cluster_median_32_inner", fitting ? null : hours);
      row("i" + i, hours, "TRAIN", fitting ? "TRAIN" : "TEST", v);
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

  @Test
  @DisplayName("each model reads exactly the spec's columns, the split-dependent ones in the inner "
      + "variant and the similarity medians at the values the direct predictors chose")
  void columnsOfEachModel() {
    final List<Cell> cells = ElasticNetSelection.cells(CHOSEN, Augmentation.Variant.INNER);
    assertEquals(List.of("previous_own_start", "revised_nine", "similarity_alone",
        "revised_twelve", "alzubaidi_start", "kikas_start", "union_revised"),
        cells.stream().map(Cell::label).toList());
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
    final List<Cell> cells = ElasticNetSelection.cells(CHOSEN, Augmentation.Variant.OUTER);
    assertEquals(List.of("A01_desc_length", "TEAM_loc_median", "TEAM_loc_sd",
        "TEAM_mccc_median", "TEAM_mccc_sd", "reporter_median_outer", "A09_exec_loc_tot",
        "A10_exec_mccc_tot", "A13_exec_open_load", "jaccard_median_10_outer",
        "embedding_median_5_outer", "cluster_median_32_outer"), names(cells.get(3)));
  }

  @Test
  @DisplayName("without a chosen value for each of the three methods the models are refused")
  void refusesWithoutTheChoices() {
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.cells(
        Map.of("jaccard_median", 10, "embedding_median", 5), Augmentation.Variant.INNER));
  }

  @Test
  @DisplayName("a column missing on every inner fitting row is removed from each model reading it, "
      + "with the reason recorded, and its validation values are never imputed from")
  void removesAColumnMissingInTheFittingSet() {
    inner();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries, CHOSEN);
    assertEquals(40, r.fitRows());
    assertEquals(15, r.validationRows());
    final ElasticNetSelection.Removed cluster = new ElasticNetSelection.Removed(
        "cluster_median_32_inner", "missing on all 40 inner fitting rows");
    for (final String name : List.of("similarity_alone", "revised_twelve", "union_revised")) {
      final ElasticNetSelection.Model m = model(r, name);
      assertTrue(m.removed().contains(cluster), name + " removed " + m.removed());
      assertFalse(m.fitted().columns().stream().anyMatch(c -> c.startsWith("cluster_")));
    }
    assertEquals(List.of(cluster), model(r, "revised_twelve").removed());
    assertEquals(List.of(), model(r, "revised_nine").removed());
    assertTrue(model(r, "revised_twelve").fitted().columns().contains("jaccard_median_10_inner"));
  }

  @Test
  @DisplayName("each model's sweep on several threads chooses and reports, bit for bit, what the "
      + "sweep on one thread does, the models in the same order")
  void parallelIsSequential() {
    inner();
    final ElasticNetSelection.Result one = ElasticNetSelection.run(entries, CHOSEN, 1);
    final ElasticNetSelection.Result many = ElasticNetSelection.run(entries, CHOSEN, 6);
    assertEquals(one.models().size(), many.models().size());
    assertEquals(Double.doubleToRawLongBits(one.projectMaeHours()),
        Double.doubleToRawLongBits(many.projectMaeHours()));
    for (int m = 0; m < one.models().size(); m++) {
      assertEquals(one.models().get(m).cell(), many.models().get(m).cell());
      assertEquals(one.models().get(m).removed(), many.models().get(m).removed());
      ParallelSweepTest.assertIdentical(one.models().get(m).fitted(),
          many.models().get(m).fitted());
    }
    assertEquals(ElasticNetSelection.parameters(one, "m", "s"),
        ElasticNetSelection.parameters(many, "m", "s"));
  }

  /** What a choice is: per model, the columns read, alpha, lambda and the validation error. */
  private static List<String> choices(final ElasticNetSelection.Result r) {
    return r.models().stream().map(m -> m.cell().label() + " " + m.fitted().columns() + " "
        + m.fitted().alpha() + " " + m.fitted().lambda() + " " + m.fitted().score().maeHours())
        .toList();
  }

  @Test
  @DisplayName("in inner mode the outer variant is never read: changing every outer column "
      + "changes no choice")
  void readsTheInnerVariant() {
    inner();
    final List<String> before = choices(ElasticNetSelection.run(entries, CHOSEN));
    final List<RowCache.Entry> changed = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.replaceAll((k, v) -> k.endsWith("_outer") ? v * 1000 + 7 : v);
      changed.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    assertEquals(before, choices(ElasticNetSelection.run(changed, CHOSEN)));
    assertTrue(model(ElasticNetSelection.run(entries, CHOSEN), "revised_twelve").fitted()
        .columns().stream().noneMatch(c -> c.contains("_outer")));
  }

  @Test
  @DisplayName("the outer test rows are never read: neither their durations nor their values, "
      + "not even values that would refuse the run")
  void neverReadsTheOuterTest() {
    inner();
    final List<String> before = choices(ElasticNetSelection.run(entries, CHOSEN));
    final Map<String, Double> poison = new HashMap<>();
    for (final String column : List.of("jaccard_median_10_inner", "reporter_median_inner",
        "TEAM_loc_median", "cluster_median_32_inner")) {
      poison.put(column, Double.NaN);
    }
    for (int k = 0; k < 10; k++) {
      row("t" + k, 90_000, "TEST", "", poison);
      row("x" + k, 90_000, "CROSSES", "", poison);
    }
    assertEquals(before, choices(ElasticNetSelection.run(entries, CHOSEN)));
  }

  @Test
  @DisplayName("a missing value of a new column is handed on as missing, with its indicator "
      + "beside it; a value that is not a number refuses")
  void missingIsHandedOnWithItsIndicator() {
    inner();
    final List<String> columns =
        model(ElasticNetSelection.run(entries, CHOSEN), "revised_nine").fitted().columns();
    assertTrue(columns.contains("reporter_median_inner"));
    assertTrue(columns.contains("reporter_median_inner_ausente"));
    // observed on every fitting row: the indicator is constant there, and the preparation drops it
    assertFalse(columns.contains("TEAM_loc_median_ausente"));

    row("nan", 10, "TRAIN", "TRAIN", Map.of("TEAM_loc_sd", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> ElasticNetSelection.run(entries, CHOSEN));
  }

  @Test
  @DisplayName("the three registered cells choose the alpha and lambda the registered grid does")
  void registeredCellsAsTheGrid() {
    inner();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries, CHOSEN);
    final app.pipeline.IssuesGridTask.Report grid =
        new app.pipeline.IssuesGridTask(app.pipeline.port.Progress.silent()).run(entries,
            Population.IN_PROGRESS);
    for (final String[] pair : new String[][] {{"previous_own_start", "OWN / C1"},
        {"alzubaidi_start", "ALZUBAIDI / C1"}, {"kikas_start", "KIKAS / C1"}}) {
      final app.issues.model.Fitting.Fitted registered = grid.fits().stream()
          .map(app.pipeline.IssuesGridTask.Timed::fitted)
          .filter(f -> f.cell().label().equals(pair[1])).findFirst().orElseThrow();
      final app.issues.model.Fitting.Fitted mine = model(r, pair[0]).fitted();
      assertEquals(registered.alpha(), mine.alpha(), pair[0]);
      assertEquals(registered.lambda(), mine.lambda(), pair[0]);
      assertEquals(registered.columns(), mine.columns(), pair[0]);
      assertEquals(registered.score().maeHours(), mine.score().maeHours(), pair[0]);
    }
  }

  @Test
  @DisplayName("the parameters file holds each model's alpha and lambda, the matrix and the "
      + "direct predictors' file it was chosen with, read back exactly as chosen")
  void parametersFile() {
    inner();
    final ElasticNetSelection.Result r = ElasticNetSelection.run(entries, CHOSEN);
    final List<String> lines = ElasticNetSelection.parameters(r, "cd34", "ab12");
    assertEquals(ElasticNetSelection.HEADER, lines.get(0));
    final List<String> data = lines.stream().filter(l -> !l.startsWith("#")).toList();
    assertEquals(List.of("elastic-net\tinputs\tmatrix_sha256\tcd34",
        "elastic-net\tinputs\tsimilarity_parameters_sha256\tab12"), data.subList(0, 2));
    assertEquals(2 + 2 * 7, data.size());
    final ElasticNetSelection.Model twelve = model(r, "revised_twelve");
    assertTrue(data.contains("elastic-net\trevised_twelve\talpha\t" + twelve.fitted().alpha()));

    final Map<String, ElasticNetSelection.Choice> back =
        ElasticNetSelection.choices(Selection.read(lines));
    assertEquals(7, back.size());
    for (final ElasticNetSelection.Model m : r.models()) {
      assertEquals(new ElasticNetSelection.Choice(m.fitted().alpha(), m.fitted().lambda()),
          back.get(m.cell().label()));
    }
    assertEquals("ab12", ElasticNetSelection.similaritySha(Selection.read(lines)));
    assertEquals("cd34", ElasticNetSelection.matrixSha(Selection.read(lines)));
  }
}
