package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.AdjustedDuration;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.PredictionIntervals;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.SplitRule;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.model.Cell;
import app.issues.model.ProjectBootstrap;
import app.issues.port.ResultStore;
import app.model.Parallel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * SENSITIVITY, after the opening of the revised design's outer test (its ticket 06, review
 * comment 8): it chooses nothing. The prediction intervals of the test step take the factors the
 * inner models calibrated on the usual-flow inner validation and apply them to the outer models'
 * predictions, an approximation ({@link PredictionIntervals#OUTER}). Here, beside them, the inner
 * models themselves predict the outer test, from the one fit whose residuals gave the factors
 * ({@link SimilarityScoring#sameModel}, {@link PredictionIntervals#SAME_MODEL}): per model and
 * level, the coverage with its interval resampling whole projects, the median width, the width
 * over the global median's, the factor and its calibration issues, and the model's MAE on the
 * outer test, context and never a competitor of the outer model's. Overall figures only, no
 * band.
 *
 * <p>Readings: every outer test issue first, then the usual flow at the outer training's fence,
 * the test step's issues; then both without the issues of new projects, when either design has
 * any, each design's new projects those with no row on its own fitting side (the outer training,
 * or the inner fitting).
 *
 * <p>Refuses, before any label is read: a split rule other than the revised one; a REPORT_DIR
 * that exists, or is or lies in the test step's folder (the folder of TEST_PREDICTIONS); an
 * ISSUES_DB without the revised design's opening, a run of {@link
 * IssuesSimilarityTestCommand#FIRST_COMMIT_REVISED_STAGE}, since this step never opens the outer
 * test; a parameters file not made on this matrix (by SHA-256) or of another design; a test
 * step's inputs file naming other inputs. Then refuses a matrix whose outer test rows carry none
 * of a column of the inner variant the models read (the revised similarity stage writes them),
 * and outer models that, refitted here, do not reproduce the test step's predictions within
 * {@link SimilarityScoring#RERUN_TOLERANCE}. Reads MATRIX, ELASTIC_NET_PARAMETERS and
 * TEST_PREDICTIONS; writes {@link #REPORT} and {@link #TABLE} to REPORT_DIR, nothing to the test
 * step's folder; records the run in ISSUES_DB, in the revised design's collection, under {@link
 * #STAGE}.
 */
public final class IssuesFirstCommitSameModelCalibrationCommand implements CommandRunner {

  public static final String STAGE = "issues-first-commit-same-model-calibration";

  public static final String REPORT = "same-model-calibration.md";

  public static final String TABLE = "same-model-calibration.tsv";

  /** What this step is, at the top of its report and in its record. */
  public static final String LABEL = "SENSITIVITY, after the opening of the outer test (the "
      + "revised design's ticket 06, review comment 8): it chooses nothing";

  /** The test step's intervals: the outer models' predictions, the inner models' factors. */
  public static final String OUTER_DESIGN = "outer_model_inner_factors";

  /** The same-model intervals: the inner models' predictions and their own factors. */
  public static final String SAME_DESIGN = "inner_model_same_factors";

  public static final List<String> COLUMNS = List.of("reading", "model", "level", "design", "n",
      "coverage", "ci_low", "ci_high", "median_width_bd", "relative_width", "factor",
      "calibration_n", "test_mae_bd");

  private final Function<String, String> settings;

  public IssuesFirstCommitSameModelCalibrationCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /** @param settings the settings by name; the tests hand their own */
  IssuesFirstCommitSameModelCalibrationCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /**
   * One reading of both designs.
   *
   * @param name the reading's name in the table: {@code all}, {@code usual_flow}, and each with
   *     {@code _without_new_projects}
   * @param outer the test step's reads of this reading: the outer models with the inner factors
   * @param same the same-model reads of this reading
   */
  record Reading(String name, TailCut.Reading reading, SimilarityScoring.Report outerReport,
      List<PredictionIntervals.Read> outer, SimilarityScoring.Report sameReport,
      List<PredictionIntervals.Read> same) {}

  @Override
  public void run(final Context context) throws CommandFailedException {
    final long began = System.nanoTime();
    final Path matrix = path("MATRIX");
    final Path elasticNet = path("ELASTIC_NET_PARAMETERS");
    final Path testPredictions = path("TEST_PREDICTIONS");
    final Path dir = path("REPORT_DIR");
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), Population.FIRST_COMMIT);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    if (rule != SplitRule.REVISED) {
      throw refused("the same-model calibration reads the revised design alone; "
          + SplitRule.SETTING + " declares " + rule.setting());
    }
    final Path testDir = testPredictions.toAbsolutePath().normalize().getParent();
    if (dir.toAbsolutePath().normalize().startsWith(testDir)) {
      throw refused(dir + " is the test step's REPORT_DIR, or lies in it (" + testDir
          + "); this sensitivity writes nothing there");
    }
    if (Files.exists(dir)) {
      throw refused(dir + " exists; this sensitivity writes to a folder of its own");
    }
    final ResultStore store =
        IssuesInProgressExtractCommand.runStore(context, Population.FIRST_COMMIT, rule);
    final Optional<Boolean> opened =
        store.recorded(IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE);
    if (opened.isEmpty() || !opened.get()) {
      throw refused((opened.isEmpty() ? "ISSUES_DB cannot tell whether a run of "
          : "ISSUES_DB holds no run of ") + IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE
          + ": the revised design's outer test has not been opened, and this sensitivity never "
          + "opens it");
    }

    final IssuesSimilarityTestCommand.Chosen chosen;
    try {
      if (!Files.exists(matrix) || RowCache.splitPopulation(matrix) != Population.FIRST_COMMIT) {
        throw refused(matrix + " is not a matrix of the first-commit population's split");
      }
      chosen = IssuesSimilarityTestCommand.chosen(elasticNet, matrix);
      if (chosen.population() != Population.FIRST_COMMIT) {
        throw refused(elasticNet + " was written for " + chosen.population());
      }
      final String design = IssuesSimilarityTestCommand.otherDesign(chosen, rule, elasticNet);
      if (design != null) {
        throw refused(design);
      }
      // the test step's own record of its inputs, beside its predictions: no label in it
      final Path inputs = testDir.resolve(IssuesSimilarityTestCommand.INPUTS);
      final List<String> expected = List.of("matrix_sha256\t" + chosen.matrixSha(),
          "elastic_net_parameters_sha256\t" + chosen.elasticNetSha());
      if (!Files.exists(inputs) || !Files.readAllLines(inputs).equals(expected)) {
        throw refused(inputs + " does not name this matrix and parameters file: the "
            + "predictions beside it were not made from them");
      }
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    // every refusal that needs no label is behind us: the matrix's rows and the predictions
    final List<RowCache.Entry> entries;
    final List<String> columns;
    final List<String> lines;
    final String predictionsSha;
    try {
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
      lines = Files.readAllLines(testPredictions);
      predictionsSha = Sha256.of(testPredictions);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }
    final List<String> split = rule.differences(entries, Population.FIRST_COMMIT);
    if (!split.isEmpty()) {
      throw refused(split.get(0));
    }
    final List<Cell> innerCells;
    final List<String> missing;
    try {
      innerCells = ElasticNetSelection.cells(ElasticNetSelection.combinations(chosen.choices()),
          Augmentation.Variant.INNER, Population.FIRST_COMMIT);
      final List<Cell> both = new ArrayList<>(innerCells);
      both.addAll(ElasticNetSelection.cells(ElasticNetSelection.combinations(chosen.choices()),
          Augmentation.Variant.OUTER, Population.FIRST_COMMIT));
      missing = IssuesSimilarityElasticNetCommand.missing(both, columns);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    if (!missing.isEmpty()) {
      throw refused(matrix + " has no column " + missing);
    }
    final String unwritten = BusinessDays.unwritten(columns, Population.FIRST_COMMIT);
    if (unwritten != null) {
      throw refused(matrix + ": " + unwritten);
    }
    final List<String> absent = innerOnTest(entries, innerCells,
        chosen.analogy() == null ? null : chosen.analogy().column());
    if (!absent.isEmpty()) {
      throw refused(matrix + " holds no value of " + absent + " on any outer test row: the "
          + "inner models read their columns of the inner variant there, which the revised "
          + "similarity stage writes for the outer test rows too");
    }
    final BusinessDays.Population population;
    try {
      population = BusinessDays.population(entries, Augmentation.Variant.OUTER);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }

    final double k = chosen.cut().k();
    final int floor = AdjustedDuration.marked(entries) ? 0 : 1;
    final int threads = Parallel.threads(Parallel.BOOTSTRAP_THREADS);
    final SimilarityScoring.Readings outer;
    final double rerun;
    final List<Reading> readings = new ArrayList<>();
    final SimilarityScoring.SameModel joint;
    final PredictionIntervals.Joint own;
    final long fitting = System.nanoTime();
    try {
      outer = SimilarityScoring.readings(population.entries(), chosen.choices(),
          chosen.forests(), k, SimilarityScoring.Scope.RESERVED, Population.FIRST_COMMIT,
          chosen.design());
      rerun = SimilarityScoring.maxDifference(lines,
          SimilarityScoring.predictions(outer.complementary()));
      if (!(rerun <= SimilarityScoring.RERUN_TOLERANCE)) {
        throw refused(String.format(Locale.ROOT, "the outer models refitted here do not "
            + "reproduce the test step's predictions in %s: largest difference %.3g business "
            + "days, tolerance %.0e", testPredictions, rerun, SimilarityScoring.RERUN_TOLERANCE));
      }
      joint = SimilarityScoring.sameModel(population.entries(), chosen.choices(),
          chosen.forests(), k, Population.FIRST_COMMIT, chosen.design(), false);
      own = PredictionIntervals.sameModel(joint, floor, threads);
      // the factors are the joint calibration's, bit for bit the test step's inner calibration
      final List<PredictionIntervals.Read> outerReads = PredictionIntervals.outer(
          own.calibrated(), PredictionIntervals.Sample.of(outer.main(), floor),
          PredictionIntervals.Sample.of(outer.complementary(), floor), threads);
      readings.add(reading("all", TailCut.Reading.ALL, outer.complementary(), outerReads,
          joint.allTest(), own.reads()));
      readings.add(reading("usual_flow", TailCut.Reading.USUAL_FLOW, outer.main(), outerReads,
          joint.usualTest(), own.reads()));
      final boolean outerNew = outer.complementary().newProjectRows() > 0;
      final boolean innerNew = joint.allTest().newProjectRows() > 0;
      if (outerNew || innerNew) {
        final SimilarityScoring.Readings outerSeen = !outerNew ? outer
            : SimilarityScoring.withoutNewProjects(population.entries(), chosen.choices(),
                chosen.forests(), k, SimilarityScoring.Scope.RESERVED, Population.FIRST_COMMIT,
                chosen.design());
        final List<PredictionIntervals.Read> outerSeenReads = !outerNew ? outerReads
            : PredictionIntervals.outer(own.calibrated(),
                PredictionIntervals.Sample.of(outerSeen.main(), floor),
                PredictionIntervals.Sample.of(outerSeen.complementary(), floor), threads);
        final SimilarityScoring.SameModel jointSeen = !innerNew ? joint
            : SimilarityScoring.sameModel(population.entries(), chosen.choices(),
                chosen.forests(), k, Population.FIRST_COMMIT, chosen.design(), true);
        final List<PredictionIntervals.Read> ownSeen = !innerNew ? own.reads()
            : PredictionIntervals.sameModel(jointSeen, floor, threads).reads();
        readings.add(reading("all_without_new_projects", TailCut.Reading.ALL,
            outerSeen.complementary(), outerSeenReads, jointSeen.allTest(), ownSeen));
        readings.add(reading("usual_flow_without_new_projects", TailCut.Reading.USUAL_FLOW,
            outerSeen.main(), outerSeenReads, jointSeen.usualTest(), ownSeen));
      }
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final double scoringSeconds = (System.nanoTime() - fitting) / 1e9;

    final List<String> table = new ArrayList<>();
    table.add(String.join("\t", COLUMNS));
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Reading r : readings) {
      for (final Map<String, Object> row : rows(r, own.calibrated())) {
        rows.add(row);
        table.add(String.join("\t", row.values().stream().map(String::valueOf).toList()));
      }
    }
    final List<String> md = report(matrix, elasticNet, testPredictions, chosen, predictionsSha,
        rerun, k, outer, joint, readings, own.calibrated());
    md.forEach(line -> System.out.println("  " + line));
    final String reportSha;
    final String tableSha;
    try {
      Files.createDirectories(dir);
      IssuesSimilarityTestCommand.write(dir.resolve(REPORT), md);
      IssuesSimilarityTestCommand.write(dir.resolve(TABLE), table);
      reportSha = Sha256.of(dir.resolve(REPORT));
      tableSha = Sha256.of(dir.resolve(TABLE));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + dir + ": " + e));
    }
    System.out.printf("%n  report written to %s  sha256 %s%n", dir.resolve(REPORT), reportSha);
    System.out.printf("  table written to %s  sha256 %s%n", dir.resolve(TABLE), tableSha);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put(SplitRule.RULE_FIELD, rule.rule());
    run.put(SplitRule.DESIGN_FIELD, rule.setting());
    run.put("nature", LABEL);
    run.put("outer_test", "read again after its opening ("
        + IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE + "), for a sensitivity that "
        + "chooses nothing");
    run.put("prediction_intervals", PredictionIntervals.rule(floor));
    run.put("prediction_interval_factor", PredictionIntervals.FACTOR);
    run.put("design_" + OUTER_DESIGN, PredictionIntervals.OUTER);
    run.put("design_" + SAME_DESIGN, PredictionIntervals.SAME_MODEL);
    run.put("readings", readings.stream().map(Reading::name).toList());
    run.put("models", joint.allTest().models().stream().map(SimilarityScoring.Scored::name)
        .toList());
    run.put("tail_cut_k", k);
    run.put("inner_fit_rows", joint.allTest().fitRows());
    run.put("outer_fit_rows", outer.complementary().fitRows());
    run.put("calibration_rows", joint.calibration().scoredRows());
    run.put("outer_test_rows", joint.allTest().scoredRows());
    run.put("usual_flow_outer_test_rows", joint.usualTest().scoredRows());
    run.put("usual_flow_fence_outer_training", joint.usualTest().fences().usualFence());
    run.put("new_projects_outer_model", List.copyOf(outer.complementary().newProjects()));
    run.put("new_project_rows_outer_model", outer.complementary().newProjectRows());
    run.put("new_projects_inner_model", List.copyOf(joint.allTest().newProjects()));
    run.put("new_project_rows_inner_model", joint.allTest().newProjectRows());
    run.put("matrix_sha256", chosen.matrixSha());
    run.put("elastic_net_parameters_sha256", chosen.elasticNetSha());
    run.put("test_predictions_sha256", predictionsSha);
    run.put("report_sha256", reportSha);
    run.put("table_sha256", tableSha);
    run.put("rerun_tolerance_business_days", SimilarityScoring.RERUN_TOLERANCE);
    run.put("rerun_max_difference_business_days", rerun);
    run.put("forest_trees", chosen.trees());
    run.put("forest_permutation_importance", "not read: it would read the outer test's labels");
    run.put("bootstrap_draws", ProjectBootstrap.DRAWS);
    run.put("bootstrap_seed", ProjectBootstrap.SEED);
    run.put("scoring_seconds", scoringSeconds);
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    run.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
        + System.getProperty("os.arch"));
    run.put("processors", Runtime.getRuntime().availableProcessors());
    System.out.printf("  run recorded in ISSUES_DB: %s%n", store.record(run, rows));
  }

  /**
   * The columns of the inner variant the inner models read, and the analogy estimate's, that no
   * outer test row holds a value of, in the models' order.
   */
  static List<String> innerOnTest(final List<RowCache.Entry> entries, final List<Cell> innerCells,
      final String analogyColumn) {
    final Set<String> needed = new LinkedHashSet<>();
    for (final Cell cell : innerCells) {
      cell.attributes().stream().map(a -> a.name())
          .filter(n -> n.endsWith(Augmentation.Variant.INNER.suffix())).forEach(needed::add);
    }
    if (analogyColumn != null) {
      needed.add(analogyColumn + Augmentation.Variant.INNER.suffix());
    }
    final Set<String> held = new LinkedHashSet<>();
    for (final RowCache.Entry e : entries) {
      if (Verdict.scored(Augmentation.Variant.OUTER, e, Population.FIRST_COMMIT)) {
        held.addAll(e.row().values().keySet());
      }
    }
    return needed.stream().filter(n -> !held.contains(n)).toList();
  }

  /** One reading of both designs, each design's reads of that reading alone. */
  private static Reading reading(final String name, final TailCut.Reading reading,
      final SimilarityScoring.Report outerReport, final List<PredictionIntervals.Read> outer,
      final SimilarityScoring.Report sameReport, final List<PredictionIntervals.Read> same) {
    return new Reading(name, reading, outerReport,
        outer.stream().filter(r -> r.reading() == reading).toList(), sameReport,
        same.stream().filter(r -> r.reading() == reading).toList());
  }

  /**
   * The table's rows of one reading: per model, in the report's order, per level, the test step's
   * design, then the same-model design.
   */
  static List<Map<String, Object>> rows(final Reading r,
      final Map<String, PredictionIntervals.Calibration> calibrated) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final SimilarityScoring.Scored m : r.sameReport().models()) {
      for (int l = 0; l < PredictionIntervals.LEVELS.size(); l++) {
        final double level = PredictionIntervals.LEVELS.get(l);
        for (final String design : List.of(OUTER_DESIGN, SAME_DESIGN)) {
          final PredictionIntervals.Read read = (design.equals(OUTER_DESIGN) ? r.outer()
              : r.same()).stream().filter(x -> x.model().equals(m.name()) && x.level() == level)
              .findFirst().orElseThrow();
          final PredictionIntervals.Group g = read.overall();
          final PredictionIntervals.Calibration c = calibrated.get(m.name());
          final Map<String, Object> row = new LinkedHashMap<>();
          row.put("reading", r.name());
          row.put("model", m.name());
          row.put("level", level);
          row.put("design", design);
          row.put("n", g.n());
          row.put("coverage", g.coverage() == null ? Double.NaN : g.coverage().value());
          row.put("ci_low", g.coverage() == null ? Double.NaN : g.coverage().low());
          row.put("ci_high", g.coverage() == null ? Double.NaN : g.coverage().high());
          row.put("median_width_bd", g.medianWidth());
          row.put("relative_width", g.relativeWidth());
          row.put("factor", c.factors()[l]);
          row.put("calibration_n", c.n());
          row.put("test_mae_bd", g.mae());
          out.add(row);
        }
      }
    }
    return out;
  }

  private static List<String> report(final Path matrix, final Path elasticNet,
      final Path testPredictions, final IssuesSimilarityTestCommand.Chosen chosen,
      final String predictionsSha, final double rerun, final double k,
      final SimilarityScoring.Readings outer, final SimilarityScoring.SameModel joint,
      final List<Reading> readings,
      final Map<String, PredictionIntervals.Calibration> calibrated) {
    final List<String> md = new ArrayList<>();
    md.add("# Issue duration from the first commit: same-model calibration (SENSITIVITY)");
    md.add("");
    md.add("**" + LABEL + ".** The revised design's outer test had been opened by "
        + IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE + "; this reading adds a "
        + "second design of its prediction intervals beside the test step's, and changes none "
        + "of its results.");
    md.add("");
    md.add("- matrix: " + matrix + " (sha256 " + chosen.matrixSha() + ")");
    md.add("- parameters: " + elasticNet + " (sha256 " + chosen.elasticNetSha() + ")");
    md.add("- test predictions: " + testPredictions + " (sha256 " + predictionsSha + ")");
    md.add(f("- the outer models, refitted here, reproduce those predictions: largest difference "
        + "%.3g business days, tolerance %.0e", rerun, SimilarityScoring.RERUN_TOLERANCE));
    md.add(f("- tail cut: k %s, as the selection chose it", TailCut.label(k)));
    md.add("");
    md.add("## The two designs");
    md.add("");
    md.add("- " + OUTER_DESIGN + " (the test step's): " + PredictionIntervals.OUTER + ".");
    md.add("- " + SAME_DESIGN + ": " + PredictionIntervals.SAME_MODEL + ".");
    md.add("- Both designs use the same factors, one per model and level: "
        + PredictionIntervals.FACTOR + ". They differ only in the predictions the factors "
        + "widen.");
    md.add("- The MAE on the outer test is each design's model's: the outer model's beside the "
        + "inner model's, for context; the inner models are fitted on older issues and compete "
        + "with nothing here.");
    md.add("");
    md.add(f("Inner fitting rows fitted (at or below the fence of k): %d. Outer training rows "
        + "fitted: %d. Calibration issues (usual-flow inner validation): %d. Outer test issues: "
        + "%d, of which %d in the usual flow (at or below %.3f business days, the outer "
        + "training's fence, the test step's usual flow).", joint.allTest().fitRows(),
        outer.complementary().fitRows(), joint.calibration().scoredRows(),
        joint.allTest().scoredRows(), joint.usualTest().scoredRows(),
        joint.usualTest().fences().usualFence()));
    md.add(f("New projects, with outer test issues and no row on the model's own fitting side: "
        + "%d for the outer models, %s, with %d issues; %d for the inner models, %s, with %d "
        + "issues.", outer.complementary().newProjects().size(),
        outer.complementary().newProjects(), outer.complementary().newProjectRows(),
        joint.allTest().newProjects().size(), joint.allTest().newProjects(),
        joint.allTest().newProjectRows()));
    for (final Reading r : readings) {
      md.add("");
      md.add("## " + title(r));
      md.add("");
      md.add("| model | level | design | n | coverage [95%] | median width (bd) | width / "
          + "global median's | factor | calibration n | MAE on the outer test (bd) |");
      md.add("|---|---:|---|---:|---|---:|---:|---:|---:|---:|");
      for (final Map<String, Object> row : rows(r, calibrated)) {
        md.add(f("| %s | %.0f%% | %s | %d | %s | %.3f | %.3f | %.4f | %d | %.3f |",
            row.get("model"), 100 * (double) row.get("level"), row.get("design"),
            (int) row.get("n"), coverage(row), (double) row.get("median_width_bd"),
            (double) row.get("relative_width"), (double) row.get("factor"),
            (int) row.get("calibration_n"), (double) row.get("test_mae_bd")));
      }
    }
    return md;
  }

  private static String title(final Reading r) {
    final String base = r.reading() == TailCut.Reading.ALL ? "All issues of the outer test"
        : "Usual flow of the outer test";
    return r.name().endsWith("_without_new_projects")
        ? f("%s, without new projects (outer models %d issues, inner models %d)", base,
            r.outerReport().scoredRows(), r.sameReport().scoredRows())
        : f("%s: %d issues", base, r.sameReport().scoredRows());
  }

  private static String coverage(final Map<String, Object> row) {
    final double value = (double) row.get("coverage");
    return Double.isNaN(value) ? "—" : f("%.3f [%.3f, %.3f]", value, (double) row.get("ci_low"),
        (double) row.get("ci_high"));
  }

  private static String f(final String format, final Object... args) {
    return String.format(Locale.ROOT, format, args);
  }

  /** A required path setting, by name; a missing one refuses the run. */
  private Path path(final String name) throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX, ELASTIC_NET_PARAMETERS, "
          + "TEST_PREDICTIONS and REPORT_DIR are required; missing " + name));
    }
    return Path.of(value);
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
        + why));
  }
}
