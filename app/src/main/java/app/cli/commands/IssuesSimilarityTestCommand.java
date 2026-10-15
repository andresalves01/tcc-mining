package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Selection;
import app.issues.SimilarityScoring;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.pipeline.embedding.EmbeddingModel;
import app.study.model.Breakdown;
import app.study.model.Score;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The test step of the description-similarity chain, outer mode only (see {@link
 * SimilarityScoring}): every model refitted on the outer training set with the parameters the two
 * selection steps wrote, the outer test scored once, every comparison the handout names.
 *
 * <p>Reads MATRIX, SIMILARITY_PARAMETERS and ELASTIC_NET_PARAMETERS, and chooses nothing. Refuses,
 * scoring, writing and recording nothing, unless both parameters files exist and are each step's
 * own, the Elastic Net choices were made with this very direct predictors' file and on this very
 * matrix (by SHA-256), the four split sizes are the registered study's, and the matrix has every
 * column the models read. Writes the per-issue predictions and the models' diagnostics to
 * REPORT_DIR; records the run, with the SHA-256 of every input and output, the time taken, the
 * hardware and the versions, in ISSUES_DB under its own stage name.
 */
public final class IssuesSimilarityTestCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-test";

  /** One line per scored issue, one column per model. */
  public static final String PREDICTIONS = "similarity-test-predictions.tsv";

  /** Per model, each column it was declared with: its weight, or why it was left out. */
  public static final String DIAGNOSTICS = "similarity-test-diagnostics.tsv";

  /**
   * The SHA-256 of the three inputs the predictions beside it came from: a rerun is compared with
   * the previous predictions only when these are the same.
   */
  public static final String INPUTS = "similarity-test-inputs.tsv";

  private static final String EXAMINED = "the outer test is the registered in-progress study's, "
      + "which the earlier study already scored and examined; this run reads it again and is not "
      + "a test untouched by the author";

  private static final String EFFORT = "selection effort differs between families: the three "
      + "registered cells (previous own specification, Al-Zubaidi, Kikas) chose only alpha and "
      + "lambda on the 3 x 12 grid; the revised models chose the same, and every model reading a "
      + "similarity median carries also the choice of its number of neighbours or clusters among "
      + "three values; the references and the direct predictors chose nothing else";

  /** Where each setting is read from: the environment, then the system properties. */
  private final Function<String, String> settings;

  public IssuesSimilarityTestCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /**
   * @param settings the settings by name; the tests hand their own, so a real MATRIX in the
   *     environment of whoever runs them can never be read, let alone scored
   */
  IssuesSimilarityTestCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = path("MATRIX");
    final Path similarity = path("SIMILARITY_PARAMETERS");
    final Path elasticNet = path("ELASTIC_NET_PARAMETERS");
    final Path reports = path("REPORT_DIR");
    final Path predictionsFile = reports.resolve(PREDICTIONS);
    final Path diagnosticsFile = reports.resolve(DIAGNOSTICS);
    final Path inputsFile = reports.resolve(INPUTS);
    final long began = System.nanoTime();
    for (final Path out : List.of(predictionsFile, diagnosticsFile, inputsFile)) {
      for (final Path in : List.of(matrix, similarity, elasticNet)) {
        if (same(out, in)) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was scored, written or "
              + "recorded: " + out + " is an input of this step and is never written"));
        }
      }
    }

    final String matrixSha;
    final String similaritySha;
    final String elasticNetSha;
    final Map<String, Integer> chosen;
    final Map<String, ElasticNetSelection.Choice> choices;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      for (final Path p : List.of(similarity, elasticNet)) {
        if (!Files.exists(p)) {
          throw refused(p + " does not exist; run issues-in-progress-similarity-selection and "
              + "issues-in-progress-similarity-elastic-net first");
        }
      }
      // each file parsed and hashed from the same bytes, so the hash recorded is of what was read
      final byte[] similarityBytes = Files.readAllBytes(similarity);
      final List<String> similarityLines =
          new String(similarityBytes, StandardCharsets.UTF_8).lines().toList();
      if (!IssuesSimilaritySelectionCommand.onlyThisStep(similarityLines)) {
        throw refused(similarity + " is not the direct predictors' parameters file");
      }
      final byte[] elasticNetBytes = Files.readAllBytes(elasticNet);
      final List<String> elasticNetLines =
          new String(elasticNetBytes, StandardCharsets.UTF_8).lines().toList();
      if (!IssuesSimilarityElasticNetCommand.onlyThisStep(elasticNetLines)) {
        throw refused(elasticNet + " is not the Elastic Net step's parameters file");
      }
      final List<Selection.Parameter> enParameters;
      try {
        chosen = Selection.chosen(Selection.read(similarityLines));
        enParameters = Selection.read(elasticNetLines);
        choices = ElasticNetSelection.choices(enParameters);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      similaritySha = sha256(similarityBytes);
      elasticNetSha = sha256(elasticNetBytes);
      if (!similaritySha.equals(ElasticNetSelection.similaritySha(enParameters))) {
        throw refused("the choices in " + elasticNet + " were chosen with another direct "
            + "predictors' file (sha256 " + ElasticNetSelection.similaritySha(enParameters)
            + "), not " + similarity + " (sha256 " + similaritySha + ")");
      }
      matrixSha = IssuesReservedCommand.sha256(matrix);
      if (!matrixSha.equals(ElasticNetSelection.matrixSha(enParameters))) {
        throw refused("the choices in " + elasticNet + " were chosen on another matrix (sha256 "
            + ElasticNetSelection.matrixSha(enParameters) + "), not " + matrix + " (sha256 "
            + matrixSha + ")");
      }
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY -- OUTER TEST%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());
    System.out.printf("  direct predictors' choices %s  sha256 %s: %s%n", similarity,
        similaritySha, chosen);
    System.out.printf("  Elastic Net choices %s  sha256 %s (%d models)%n", elasticNet,
        elasticNetSha, choices.size());
    final RegistrationInProgress.Sizes s = Selection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!RegistrationInProgress.SIZES.equals(s)) {
      throw refused("the split sizes are not the registered study's, "
          + RegistrationInProgress.SIZES);
    }
    final List<String> missing;
    try {
      // the direct predictors' columns are the revised twelve's, so this covers them too
      missing = IssuesSimilarityElasticNetCommand.missing(
          ElasticNetSelection.cells(chosen, Augmentation.Variant.OUTER), columns);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    if (!missing.isEmpty()) {
      throw refused(matrix + " has no column " + missing);
    }

    System.out.printf("%n  NOTE: %s.%n  NOTE: %s.%n", EXAMINED, EFFORT);
    System.out.printf("  the parameters are read, never chosen; the outer test is scored now%n");
    final long fitting = System.nanoTime();
    final SimilarityScoring.Report r;
    try {
      r = SimilarityScoring.run(entries, chosen, choices);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final double scoringSeconds = (System.nanoTime() - fitting) / 1e9;

    System.out.printf("%n  refit on the outer training set (%,d rows); scored %,d rows of the "
        + "outer test%n", r.fitRows(), r.scoredRows());
    System.out.printf("  final lambda = chosen lambda x %,d / %,d, alpha as chosen; no "
        + "recalibration%n", r.fitRows(), r.innerFitRows());
    System.out.printf("  a direct predictor's missing value reads the outer training median, "
        + "%.1f h%n", r.fallbackHours());
    System.out.printf("  test projects with no issue in training (there the project median is "
        + "the global one): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

    final List<Map<String, Object>> rows = new ArrayList<>();
    System.out.printf("%n  %-24s %9s %9s %9s %9s %9s%n", "model", "MAE h", "MAE d", "Spearman",
        "pairwise", "slow10% d");
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score sc = m.score();
      System.out.printf("  %-24s %9.2f %9.2f %9.3f %9.3f %9.2f%n", m.name(), sc.maeHours(),
          sc.maeDays(), sc.spearman(), sc.pairwise(), m.bands().get(4).mae() / 24);
      rows.add(modelRow(m));
    }
    System.out.printf("%n  error by band of the true duration, MAE in days%n");
    for (final SimilarityScoring.Scored m : r.models()) {
      final StringBuilder line = new StringBuilder(String.format("  %-24s", m.name()));
      for (final Breakdown.Band b : m.bands()) {
        line.append(String.format("  %s %.2f", b.label(), b.mae() / 24));
      }
      System.out.println(line);
    }
    System.out.printf("%n  direct predictors' coverage on the outer test%n");
    for (final SimilarityScoring.Direct d : r.directs()) {
      System.out.printf("  %-24s %s: %,d of %,d observed (%.1f%%), MAE on those %.2f h%n",
          d.name(), d.column(), d.observed(), r.scoredRows(), 100.0 * d.observed()
              / r.scoredRows(), d.maeObservedHours());
      rows.add(directRow(d, r.scoredRows()));
    }
    System.out.printf("%n  paired comparisons: MAE(a) - MAE(b) in hours, 95%% interval resampling "
        + "whole projects (%d draws); an interval containing zero is a tie%n",
        ProjectBootstrap.DRAWS);
    for (final ProjectBootstrap.Gap g : r.comparisons()) {
      System.out.printf("  %-24s vs %-24s %10.2f [%10.2f, %10.2f]%s%n", g.a(), g.b(),
          g.gapHours(), g.low(), g.high(), g.tied() ? "  tie" : "");
      rows.add(comparisonRow(g));
    }

    final List<String> predictions = SimilarityScoring.predictions(r);
    final Double rerun;
    final String predictionsSha;
    final String diagnosticsSha;
    try {
      Files.createDirectories(reports);
      final List<String> inputs = List.of("matrix_sha256\t" + matrixSha,
          "similarity_parameters_sha256\t" + similaritySha,
          "elastic_net_parameters_sha256\t" + elasticNetSha);
      rerun = rerun(predictionsFile, inputsFile, inputs, predictions);
      write(inputsFile, inputs);
      write(predictionsFile, predictions);
      write(diagnosticsFile, diagnostics(r));
      predictionsSha = IssuesReservedCommand.sha256(predictionsFile);
      diagnosticsSha = IssuesReservedCommand.sha256(diagnosticsFile);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + reports + ": " + e));
    }
    System.out.printf("%n  predictions written to %s  sha256 %s%n", predictionsFile,
        predictionsSha);
    System.out.printf("  diagnostics written to %s  sha256 %s%n", diagnosticsFile,
        diagnosticsSha);
    if (rerun != null) {
      System.out.printf("  against the predictions of the previous run there: largest "
          + "difference %.3g h, tolerance %.0e h: %s%n", rerun,
          SimilarityScoring.RERUN_TOLERANCE_HOURS,
          rerun <= SimilarityScoring.RERUN_TOLERANCE_HOURS ? "REPRODUCED" : "NOT REPRODUCED");
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("mode", "outer");
    run.put("outer_test", "scored");
    run.putAll(statements());
    run.put("matrix_sha256", matrixSha);
    run.put("similarity_parameters_sha256", similaritySha);
    run.put("elastic_net_parameters_sha256", elasticNetSha);
    run.put("predictions_sha256", predictionsSha);
    run.put("diagnostics_sha256", diagnosticsSha);
    run.put("candidates", new LinkedHashMap<>(chosen));
    run.put("fit_rows", r.fitRows());
    run.put("scored_rows", r.scoredRows());
    run.put("inner_fit_rows", r.innerFitRows());
    run.put("final_lambda", "chosen lambda x fit_rows / inner_fit_rows; alpha as chosen");
    run.put("fallback_hours", r.fallbackHours());
    run.put("bootstrap_draws", ProjectBootstrap.DRAWS);
    run.put("bootstrap_seed", ProjectBootstrap.SEED);
    run.put("rerun_tolerance_hours", SimilarityScoring.RERUN_TOLERANCE_HOURS);
    run.put("rerun_max_difference_hours", rerun);
    run.put("scoring_seconds", scoringSeconds);
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    run.put("java_vendor", System.getProperty("java.vendor"));
    run.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
        + System.getProperty("os.arch"));
    run.put("processors", Runtime.getRuntime().availableProcessors());
    run.put("max_memory_bytes", Runtime.getRuntime().maxMemory());
    run.put("model", EmbeddingModel.REPOSITORY);
    run.put("model_revision", EmbeddingModel.REVISION);
    run.put("cleaner", DescriptionCleaner.VERSION);
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /** What the run record states about the test it scores. */
  static Map<String, Object> statements() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("outer_test_examined_before", EXAMINED);
    out.put("selection_effort", EFFORT);
    return out;
  }

  /**
   * Against a previous run's predictions from the same three inputs, the largest difference; null
   * when there is none, or when its inputs are unknown or differ.
   */
  private static Double rerun(final Path file, final Path inputsFile, final List<String> inputs,
      final List<String> predictions) throws IOException {
    if (!Files.exists(file)) {
      return null;
    }
    if (!Files.exists(inputsFile) || !Files.readAllLines(inputsFile).equals(inputs)) {
      System.out.printf("  the previous predictions in %s came from other or unknown inputs; "
          + "no rerun comparison%n", file);
      return null;
    }
    try {
      return SimilarityScoring.maxDifference(Files.readAllLines(file), predictions);
    } catch (final IllegalArgumentException e) {
      System.out.printf("  the previous predictions in %s are not of the same issues and models: "
          + "%s%n", file, e.getMessage());
      return null;
    }
  }

  /** Per model, each declared column: its weight, its removal or its drop for being constant. */
  static List<String> diagnostics(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("model\tcolumn\tstatus\tcoefficient\tselected\treason");
    for (final SimilarityScoring.Scored m : r.models()) {
      if (m.fitted() == null) {
        continue;
      }
      for (final ElasticNetSelection.Removed x : m.removed()) {
        out.add(String.join("\t", m.name(), x.column(), "removed", "", "", x.reason()));
      }
      for (final String d : m.fitted().dropped()) {
        out.add(String.join("\t", m.name(), d, "dropped", "", "",
            "constant on the outer training rows"));
      }
      for (final Fitting.Contribution c : m.fitted().contributions()) {
        out.add(String.join("\t", m.name(), c.column(), "fitted",
            Double.toString(c.coefficient()), Boolean.toString(c.selected()), ""));
      }
    }
    return out;
  }

  private static Map<String, Object> modelRow(final SimilarityScoring.Scored m) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", "model");
    row.put("model", m.name());
    row.put("family", m.kind());
    final Score s = m.score();
    row.put("n", s.n());
    row.put("mae_hours", s.maeHours());
    row.put("mae_days", s.maeDays());
    row.put("gain_over_project_percent", s.gainPercent());
    row.put("gain_over_global_percent", s.gainOverConstantPercent());
    row.put("spearman", s.spearman());
    row.put("pairwise", s.pairwise());
    final Breakdown.Errors e = m.errors();
    row.put("abs_error_p50_hours", e.p50());
    row.put("abs_error_p90_hours", e.p90());
    row.put("mae_without_slowest_tenth_hours", e.trimmedMae());
    final List<Map<String, Object>> bands = new ArrayList<>();
    for (final Breakdown.Band b : m.bands()) {
      final Map<String, Object> band = new LinkedHashMap<>();
      band.put("band", b.label());
      band.put("n", b.n());
      band.put("median_truth_hours", b.medianTruth());
      band.put("mae_hours", b.mae());
      band.put("median_error_hours", b.medianError());
      bands.add(band);
    }
    row.put("bands", bands);
    if (m.fitted() != null) {
      row.put("alpha", m.fitted().alpha());
      row.put("final_lambda", m.fitted().lambda());
      row.put("columns", m.fitted().columns());
      row.put("selected", m.fitted().selected());
      row.put("removed", m.removed().stream().map(x -> x.column() + ": " + x.reason()).toList());
      row.put("dropped_constant", m.fitted().dropped());
    }
    return row;
  }

  private static Map<String, Object> directRow(final SimilarityScoring.Direct d,
      final int rows) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", "coverage");
    row.put("model", d.name());
    row.put("column", d.column());
    row.put("rows", rows);
    row.put("observed", d.observed());
    row.put("mae_observed_hours", Double.isNaN(d.maeObservedHours()) ? null
        : d.maeObservedHours());
    return row;
  }

  private static Map<String, Object> comparisonRow(final ProjectBootstrap.Gap g) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", "comparison");
    row.put("a", g.a());
    row.put("b", g.b());
    row.put("mae_diff_hours", g.gapHours());
    row.put("ci_projects", List.of(g.low(), g.high()));
    row.put("tie", g.tied());
    return row;
  }

  private static void write(final Path file, final List<String> lines) throws IOException {
    final Path partial = file.resolveSibling(file.getFileName() + ".partial");
    Files.write(partial, lines);
    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was scored, written or "
        + "recorded: " + why));
  }

  private static String sha256(final byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private Path path(final String name) throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX, SIMILARITY_PARAMETERS, "
          + "ELASTIC_NET_PARAMETERS and REPORT_DIR are required; missing " + name));
    }
    return Path.of(value);
  }
}
