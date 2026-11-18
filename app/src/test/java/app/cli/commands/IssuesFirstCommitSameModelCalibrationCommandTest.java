package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Augmentation;
import app.issues.PredictionIntervals;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.port.ResultStore;
import app.pipeline.port.SilentProgress;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same-model calibration step (the revised design's ticket 06), end to end on the synthetic
 * first-commit matrix at the revised design's verdicts (the temporal sides, the issues crossing a
 * cut marked), read under the revised rule: its outer test rows
 * carry the inner variant's columns, as the revised similarity stage writes them. A project,
 * {@code p4}, has inner validation and outer test issues and no inner fitting row: new to the
 * inner model alone, so the readings without new projects are drawn. The selection and the test
 * step run once, for every test here.
 */
class IssuesFirstCommitSameModelCalibrationCommandTest {

  @TempDir static Path shared;

  @TempDir Path dir;

  private static Path matrix;
  private static Path parameters;
  private static Path testDir;
  /** The revised design's runs the selection and the test step recorded: the opening among them. */
  private static final List<Map<String, Object>> OPENED = new ArrayList<>();
  /** The rows the test step recorded beside its run: its prediction intervals among them. */
  private static final List<Map<String, Object>> TEST_ROWS = new ArrayList<>();
  /** The revised design's earlier stages for the matrix, which the test step reads to open. */
  private static final List<Map<String, Object>> UPSTREAM = new ArrayList<>();

  /** A run store of the revised design's collection and the random one's, both in memory. */
  static final class Store implements ResultStore {
    final List<Map<String, Object>> random = new ArrayList<>();
    final List<Map<String, Object>> revised;

    Store(final List<Map<String, Object>> revised) {
      this.revised = revised;
    }

    @Override
    public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
      random.add(run);
      return "run";
    }

    @Override
    public Optional<Boolean> recorded(final String stage) {
      return Optional.of(random.stream().anyMatch(r -> stage.equals(r.get("stage"))));
    }

    @Override
    public Optional<Set<SplitRule>> designs() {
      return Optional.of(random.stream().map(SplitRule::recordedBy).collect(Collectors.toSet()));
    }

    @Override
    public ResultStore design(final SplitRule rule) {
      if (rule == SplitRule.RANDOM) {
        return this;
      }
      if (rule != SplitRule.REVISED) {
        throw new UnsupportedOperationException(rule.setting());
      }
      return new ResultStore() {
        @Override
        public String record(final Map<String, Object> run,
            final List<Map<String, Object>> rows) {
          revised.add(run);
          if (IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE.equals(run.get("stage"))) {
            TEST_ROWS.addAll(rows);
          }
          return "revised-run";
        }

        @Override
        public Optional<Boolean> recorded(final String stage) {
          return Optional.of(revised.stream().anyMatch(r -> stage.equals(r.get("stage"))));
        }

        @Override
        public Optional<Set<SplitRule>> designs() {
          return Optional.of(revised.stream().map(SplitRule::recordedBy)
              .collect(Collectors.toSet()));
        }

        @Override
        public Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
          final List<Map<String, Object>> all = new ArrayList<>(UPSTREAM);
          all.addAll(revised);
          return RevisedUpstream.runs(all, stage);
        }
      };
    }
  }

  private static Context context(final Store store) {
    final PipelineConfig own = new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
    return new Context(own, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  /** The selection, then the test step's opening, under the revised rule, once for the class. */
  @BeforeAll
  static void openTheTest() throws Exception {
    matrix = SelectionFixture.revised(shared.resolve("matrix.tsv"), 3, 3);
    UPSTREAM.addAll(RevisedUpstream.of(matrix));
    parameters = shared.resolve("elastic-net.tsv");
    testDir = shared.resolve("test");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put(SplitRule.SETTING, "revised");
    settings.put("REPORT_DIR", testDir.toString());
    final Store store = new Store(OPENED);
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(store));
    new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT).run(context(store));
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE,
        OPENED.getLast().get("stage"));
  }

  /** A store whose revised collection holds the opening, and what this step records. */
  private static Store opened() {
    return new Store(new ArrayList<>(OPENED));
  }

  private Map<String, String> settings(final Path report) {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put("TEST_PREDICTIONS",
        testDir.resolve(IssuesSimilarityTestCommand.PREDICTIONS).toString());
    settings.put("REPORT_DIR", report.toString());
    settings.put(SplitRule.SETTING, "revised");
    return settings;
  }

  /** Every file of a folder and its bytes, by path. */
  private static Map<String, String> snapshot(final Path folder) throws IOException {
    final Map<String, String> out = new TreeMap<>();
    try (Stream<Path> files = Files.walk(folder)) {
      for (final Path f : files.filter(Files::isRegularFile).toList()) {
        out.put(folder.relativize(f).toString(), Sha256.of(f));
      }
    }
    return out;
  }

  private static String refused(final Map<String, String> settings, final Store store) {
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitSameModelCalibrationCommand(settings::get)
            .run(context(store)));
    return String.join("\n", e.lines());
  }

  @Test
  @DisplayName("writes the sensitivity's report and table, every outer test issue first, then "
      + "the usual flow, both designs per model and level, and records its run under a stage "
      + "of its own citing the same-model intervals; nothing in the test step's folder moves")
  void writesBothDesigns() throws Exception {
    final Map<String, String> before = snapshot(testDir);
    final Path report = dir.resolve("same-model");
    final Store store = opened();
    new IssuesFirstCommitSameModelCalibrationCommand(settings(report)::get).run(context(store));
    assertEquals(before, snapshot(testDir));

    final List<String> md =
        Files.readAllLines(report.resolve(IssuesFirstCommitSameModelCalibrationCommand.REPORT));
    assertTrue(md.get(2).startsWith("**SENSITIVITY, after the opening"), md.get(2));
    assertTrue(md.get(2).contains("it chooses nothing"), md.get(2));
    final List<String> table =
        Files.readAllLines(report.resolve(IssuesFirstCommitSameModelCalibrationCommand.TABLE));
    // the nature first, a comment line, then the header
    assertEquals("# " + IssuesFirstCommitSameModelCalibrationCommand.NATURE, table.getFirst());
    assertEquals(String.join("\t", IssuesFirstCommitSameModelCalibrationCommand.COLUMNS),
        table.get(1));
    final List<String[]> rows = table.subList(2, table.size()).stream()
        .map(l -> l.split("\t", -1)).toList();
    final List<String> readings = rows.stream().map(r -> r[0]).distinct().toList();
    // p4 is new to the inner model: the readings without new projects are drawn
    assertEquals(List.of("all", "usual_flow", "all_without_new_projects",
        "usual_flow_without_new_projects"), readings);
    assertEquals("all", rows.getFirst()[0]);
    final List<String> models = OPENED.getLast().get("models") instanceof List<?> l
        ? l.stream().map(Object::toString).toList() : List.of();
    assertEquals(15, models.size());
    for (final String reading : readings) {
      for (final String model : models) {
        for (final double level : PredictionIntervals.LEVELS) {
          final List<String> designs = rows.stream().filter(r -> r[0].equals(reading)
              && r[1].equals(model) && Double.parseDouble(r[2]) == level).map(r -> r[3])
              .toList();
          assertEquals(List.of(IssuesFirstCommitSameModelCalibrationCommand.OUTER_DESIGN,
              IssuesFirstCommitSameModelCalibrationCommand.SAME_DESIGN), designs,
              reading + " " + model + " " + level);
        }
      }
    }
    // both designs share the factors and their calibration; their predictions differ
    for (int i = 0; i + 1 < rows.size(); i += 2) {
      assertEquals(rows.get(i)[10], rows.get(i + 1)[10]);
      assertEquals(rows.get(i)[11], rows.get(i + 1)[11]);
      if (!rows.get(i)[0].endsWith("_without_new_projects")) {
        assertEquals(rows.get(i)[4], rows.get(i + 1)[4], "the same issues in both designs");
      }
    }
    assertTrue(rows.stream().filter(r -> r[1].equals("own_retrospective") && r[0].equals("all"))
        .map(r -> r[12]).distinct().count() > 1, "the inner model's MAE is its own");

    // without new projects: the same factors, from the one fit, on fewer inner-model issues
    final Map<String, Object> recorded = store.revised.getLast();
    final int newRows = (int) recorded.get("new_project_rows_inner_model");
    assertEquals(List.of("p4"), recorded.get("new_projects_inner_model"));
    assertEquals(List.of(), recorded.get("new_projects_outer_model"));
    assertTrue(newRows > 0);
    for (final String[] r : rows) {
      if (!r[0].endsWith("_without_new_projects")) {
        continue;
      }
      final String base = r[0].substring(0, r[0].length() - "_without_new_projects".length());
      final String[] whole = rows.stream().filter(x -> x[0].equals(base) && x[1].equals(r[1])
          && x[2].equals(r[2]) && x[3].equals(r[3])).findFirst().orElseThrow();
      assertEquals(whole[10], r[10], "the factor of the one calibration: " + r[1]);
      assertEquals(whole[11], r[11]);
      if (r[3].equals(IssuesFirstCommitSameModelCalibrationCommand.OUTER_DESIGN)) {
        assertEquals(whole[4], r[4], "no new project to the outer model");
      } else if (base.equals("all")) {
        assertEquals(Integer.parseInt(whole[4]) - newRows, Integer.parseInt(r[4]));
      } else {
        assertTrue(Integer.parseInt(r[4]) < Integer.parseInt(whole[4]), r[1]);
      }
    }

    // the test step's design reproduces the test step's own coverages and factors
    int matched = 0;
    for (final String[] r : rows) {
      if (!r[3].equals(IssuesFirstCommitSameModelCalibrationCommand.OUTER_DESIGN)
          || r[0].endsWith("_without_new_projects")) {
        continue;
      }
      final Map<String, Object> written = TEST_ROWS.stream()
          .filter(x -> "prediction_interval".equals(x.get("kind")) && r[0].equals(x.get("reading"))
              && r[1].equals(x.get("model")) && Double.parseDouble(r[2]) == (double) x.get("level")
              && "all".equals(x.get("observed_band"))).findFirst().orElseThrow();
      assertEquals((int) written.get("n"), Integer.parseInt(r[4]), r[0] + " " + r[1]);
      if (written.containsKey("coverage")) {
        assertEquals((double) written.get("coverage"), Double.parseDouble(r[5]), 0.0, r[1]);
        assertEquals(written.get("coverage_ci_projects"),
            List.of(Double.parseDouble(r[6]), Double.parseDouble(r[7])), r[1]);
        assertEquals((double) written.get("median_width_business_days"),
            Double.parseDouble(r[8]), 0.0, r[1]);
        assertEquals((double) written.get("mae_business_days"), Double.parseDouble(r[12]), 0.0,
            r[1]);
        matched++;
      }
    }
    assertEquals(2 * 15 * PredictionIntervals.LEVELS.size(), matched);
    // the factors, read back off the test step's prediction intervals around its estimates
    final List<String[]> percentiles = Files.readAllLines(
        testDir.resolve(IssuesSimilarityTestCommand.PERCENTILES)).stream().skip(1)
        .map(x -> x.split("\t", -1)).toList();
    int factors = 0;
    for (final String[] p : percentiles) {
      final double estimate = Double.parseDouble(p[4]);
      for (int level = 0; level < PredictionIntervals.LEVELS.size(); level++) {
        final double high = Double.parseDouble(p[6 + 2 * level]);
        if (!(estimate > 0) || Double.isInfinite(high)) {
          continue;
        }
        final double lvl = PredictionIntervals.LEVELS.get(level);
        final String[] row = rows.stream().filter(x -> x[0].equals("all") && x[1].equals(p[1])
            && Double.parseDouble(x[2]) == lvl
            && x[3].equals(IssuesFirstCommitSameModelCalibrationCommand.OUTER_DESIGN))
            .findFirst().orElseThrow();
        assertEquals(Double.parseDouble(row[10]), Math.log1p(high) - Math.log1p(estimate), 1e-9,
            p[1] + " " + lvl);
        factors++;
      }
    }
    assertTrue(factors > 0, "some prediction interval to read a factor from");

    assertEquals(OPENED.size() + 1, store.revised.size(), "one run, in the revised collection");
    assertTrue(store.random.isEmpty());
    final Map<String, Object> run = store.revised.getLast();
    assertEquals(IssuesFirstCommitSameModelCalibrationCommand.STAGE, run.get("stage"));
    assertEquals("issues-first-commit-same-model-calibration", run.get("stage"));
    assertEquals("revised", run.get(SplitRule.DESIGN_FIELD));
    assertTrue(run.containsValue(PredictionIntervals.SAME_MODEL));
    assertTrue(run.containsValue(PredictionIntervals.OUTER));
    assertTrue(((String) run.get("nature")).contains("chooses nothing"));
    assertEquals(Sha256.of(testDir.resolve(IssuesSimilarityTestCommand.PREDICTIONS)),
        run.get("test_predictions_sha256"));
    assertTrue((double) run.get("rerun_max_difference_business_days")
        <= app.issues.SimilarityScoring.RERUN_TOLERANCE);
  }

  @Test
  @DisplayName("refuses, reading no label and writing nothing, without the revised opening in "
      + "ISSUES_DB: it refuses before it even looks for the matrix or the predictions")
  void refusesWithoutTheOpening() throws Exception {
    final Map<String, String> settings = settings(dir.resolve("same-model"));
    settings.put("MATRIX", dir.resolve("no-matrix.tsv").toString());
    settings.put("TEST_PREDICTIONS", dir.resolve("none").resolve("p.tsv").toString());
    final Store empty = new Store(new ArrayList<>());
    final String why = refused(settings, empty);
    assertTrue(why.contains("holds no run of "
        + IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE), why);
    assertTrue(empty.revised.isEmpty() && empty.random.isEmpty());
    assertFalse(Files.exists(dir.resolve("same-model")));
  }

  @Test
  @DisplayName("refuses, writing and recording nothing: a REPORT_DIR that exists or is or lies "
      + "in the test step's folder, a rule other than the revised one, a matrix the parameters "
      + "were not made on")
  void refusals() throws Exception {
    final Map<String, String> before = snapshot(testDir);
    final Store store = opened();
    final int runs = store.revised.size();

    Files.createDirectories(dir.resolve("exists"));
    assertTrue(refused(settings(dir.resolve("exists")), store).contains("exists"));
    assertTrue(refused(settings(testDir), store).contains("the test step's REPORT_DIR"));
    assertTrue(refused(settings(testDir.resolve("inside")), store)
        .contains("the test step's REPORT_DIR"));

    for (final String rule : List.of("temporal", "random")) {
      final Map<String, String> s = settings(dir.resolve("out-" + rule));
      s.put(SplitRule.SETTING, rule);
      assertTrue(refused(s, store).contains("the revised design alone"), rule);
    }
    final Map<String, String> unset = settings(dir.resolve("out-unset"));
    unset.remove(SplitRule.SETTING);
    assertTrue(refused(unset, store).contains("the revised design alone"));

    final Map<String, String> other = settings(dir.resolve("out-other"));
    other.put("MATRIX", SelectionFixture.temporal(dir.resolve("other.tsv"), 1).toString());
    assertTrue(refused(other, store).contains("chosen on another matrix"));

    assertEquals(runs, store.revised.size());
    try (Stream<Path> left = Files.list(dir)) {
      assertEquals(Set.of("exists", "other.tsv"), left.map(p -> p.getFileName().toString())
          .collect(Collectors.toSet()));
    }
    assertEquals(before, snapshot(testDir));
  }

  @Test
  @DisplayName("refuses when the outer models refitted here do not reproduce the test step's "
      + "predictions, writing and recording nothing")
  void refusesAnUnreproducedTest() throws Exception {
    final Path copy = dir.resolve("copy");
    Files.createDirectories(copy);
    for (final String name : List.of(IssuesSimilarityTestCommand.PREDICTIONS,
        IssuesSimilarityTestCommand.INPUTS)) {
      Files.copy(testDir.resolve(name), copy.resolve(name));
    }
    final Path predictions = copy.resolve(IssuesSimilarityTestCommand.PREDICTIONS);
    final List<String> lines = new ArrayList<>(Files.readAllLines(predictions));
    final String[] f = lines.get(1).split("\t", -1);
    f[f.length - 1] = Double.toString(Double.parseDouble(f[f.length - 1]) + 1);
    lines.set(1, String.join("\t", f));
    Files.write(predictions, lines);
    final Map<String, String> settings = settings(dir.resolve("same-model"));
    settings.put("TEST_PREDICTIONS", predictions.toString());
    final Store store = opened();
    final String why = refused(settings, store);
    assertTrue(why.contains("do not reproduce the test step's predictions"), why);
    assertFalse(Files.exists(dir.resolve("same-model")));
    assertEquals(OPENED.size(), store.revised.size());

    // an inputs file naming other inputs is refused before any label is read
    Files.write(copy.resolve(IssuesSimilarityTestCommand.INPUTS), List.of("matrix_sha256\tx",
        "elastic_net_parameters_sha256\ty"));
    assertTrue(refused(settings, store).contains("does not name this matrix"));
  }

  @Test
  @DisplayName("a matrix whose outer test rows hold no value of an inner column the models read "
      + "is refused: the revised similarity stage writes them there")
  void innerColumnsOnTheTest() throws Exception {
    final List<RowCache.Entry> entries = RowCache.read(matrix);
    final List<RowCache.Entry> stripped = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      if (!Verdict.scored(Augmentation.Variant.OUTER, e, Population.FIRST_COMMIT)) {
        stripped.add(e);
        continue;
      }
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.keySet().removeIf(n -> n.startsWith("jaccard_median_")
          && n.endsWith(Augmentation.Variant.INNER.suffix()));
      stripped.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    final List<app.issues.model.Cell> inner = app.issues.ElasticNetSelection.cells(
        app.issues.ElasticNetSelection.combinations(
            IssuesSimilarityTestCommand.chosen(parameters, matrix).choices()),
        Augmentation.Variant.INNER, Population.FIRST_COMMIT);
    assertEquals(List.of(), IssuesFirstCommitSameModelCalibrationCommand.innerOnTest(entries,
        inner, null));
    final List<String> absent =
        IssuesFirstCommitSameModelCalibrationCommand.innerOnTest(stripped, inner, null);
    assertFalse(absent.isEmpty());
    assertTrue(absent.stream().allMatch(n -> n.startsWith("jaccard_median_")), absent.toString());
    assertEquals(List.of("embedding_median_3_inner"), IssuesFirstCommitSameModelCalibrationCommand
        .innerOnTest(stripped.stream().map(e -> {
          final Map<String, Double> v = new HashMap<>(e.row().values());
          v.remove("embedding_median_3_inner");
          return new RowCache.Entry(e.unit(), new Row(e.row().unitId(), v, e.row().absent()),
              e.parts());
        }).toList(), List.of(), "embedding_median_3"));
  }
}
