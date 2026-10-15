package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
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
import java.util.Set;
import java.util.function.Function;

/**
 * The test step of the description-similarity chain, outer mode only (see {@link
 * SimilarityScoring}): ten models -- the global, project and executor-project medians and the
 * seven Elastic Nets -- refitted on the outer training set with the parameters the selection step
 * wrote, the outer test scored once, every agreed comparison.
 *
 * <p>Reads MATRIX and ELASTIC_NET_PARAMETERS, which gives per model its alpha and lambda and, for
 * the three with the similarity medians, the Jaccard n, embedding n and cluster k they read; it
 * chooses nothing. Refuses, scoring, writing and recording nothing, unless the parameters file
 * exists and is the step's own, its choices were made on this very matrix (by SHA-256), the four
 * split sizes are the registered study's, and the matrix has every column the models read.
 * Writes the per-issue predictions and the models' diagnostics to REPORT_DIR; records the run,
 * with the SHA-256 of every input and output, the time taken, the hardware and the versions, in
 * ISSUES_DB under its own stage name.
 */
public final class IssuesSimilarityTestCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-test";

  /** One line per scored issue, one column per model. */
  public static final String PREDICTIONS = "similarity-test-predictions.tsv";

  /** Per model, each column it was declared with: its weight, or why it was left out. */
  public static final String DIAGNOSTICS = "similarity-test-diagnostics.tsv";

  /**
   * The SHA-256 of the two inputs the predictions beside it came from: a rerun is compared with
   * the previous predictions only when these are the same.
   */
  public static final String INPUTS = "similarity-test-inputs.tsv";

  private static final String EXAMINED = "the outer test is the registered in-progress study's, "
      + "which the earlier study already scored and examined; this run reads it again and is not "
      + "a test untouched by the author";

  private static final String EFFORT = "selection effort differs between families: the three "
      + "registered cells (previous own specification, Al-Zubaidi, Kikas) chose only alpha and "
      + "lambda on the 3 x 12 grid; the revised nine chose the same; the three models reading the "
      + "similarity medians chose also their Jaccard n, embedding n and cluster k among "
      + ElasticNetSelection.Combination.all().size() + " "
      + "combinations, each swept on the same grid; the references chose nothing";

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
    final Path elasticNet = path("ELASTIC_NET_PARAMETERS");
    final Path reports = path("REPORT_DIR");
    final Path predictionsFile = reports.resolve(PREDICTIONS);
    final Path diagnosticsFile = reports.resolve(DIAGNOSTICS);
    final Path inputsFile = reports.resolve(INPUTS);
    final long began = System.nanoTime();
    for (final Path out : List.of(predictionsFile, diagnosticsFile, inputsFile)) {
      for (final Path in : List.of(matrix, elasticNet)) {
        if (same(out, in)) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was scored, written or "
              + "recorded: " + out + " is an input of this step and is never written"));
        }
      }
    }

    final String matrixSha;
    final String elasticNetSha;
    final Map<String, ElasticNetSelection.Choice> choices;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      if (!Files.exists(elasticNet)) {
        throw refused(elasticNet + " does not exist; run "
            + "issues-in-progress-similarity-elastic-net first");
      }
      // parsed and hashed from the same bytes, so the hash recorded is of what was read
      final byte[] elasticNetBytes = Files.readAllBytes(elasticNet);
      final List<String> elasticNetLines =
          new String(elasticNetBytes, StandardCharsets.UTF_8).lines().toList();
      if (!IssuesSimilarityElasticNetCommand.onlyThisStep(elasticNetLines)) {
        throw refused(elasticNet + " is not the Elastic Net step's parameters file");
      }
      final List<ElasticNetSelection.Parameter> enParameters;
      try {
        enParameters = ElasticNetSelection.read(elasticNetLines);
        choices = ElasticNetSelection.choices(enParameters);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      if (!Set.copyOf(ElasticNetSelection.MODELS).equals(choices.keySet())) {
        throw refused(elasticNet + " holds alpha and lambda for " + choices.keySet()
            + "; the models are " + ElasticNetSelection.MODELS);
      }
      elasticNetSha = sha256(elasticNetBytes);
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
    System.out.printf("  Elastic Net choices %s  sha256 %s (%d models)%n", elasticNet,
        elasticNetSha, choices.size());
    final RegistrationInProgress.Sizes s = ElasticNetSelection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!RegistrationInProgress.SIZES.equals(s)) {
      throw refused("the split sizes are not the registered study's, "
          + RegistrationInProgress.SIZES);
    }
    final List<String> missing;
    try {
      missing = IssuesSimilarityElasticNetCommand.missing(ElasticNetSelection.cells(
          ElasticNetSelection.combinations(choices), Augmentation.Variant.OUTER), columns);
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
      r = SimilarityScoring.run(entries, choices);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final double scoringSeconds = (System.nanoTime() - fitting) / 1e9;

    System.out.printf("%n  refit on the outer training set (%,d rows); scored %,d rows of the "
        + "outer test%n", r.fitRows(), r.scoredRows());
    System.out.printf("  final lambda = chosen lambda x %,d / %,d, alpha as chosen; no "
        + "recalibration%n", r.fitRows(), r.innerFitRows());
    System.out.printf("  test projects with no issue in training (there the project median is "
        + "the global one): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

    final List<Map<String, Object>> rows = new ArrayList<>();
    System.out.printf("%n  %-24s %9s %9s %9s %9s %9s%n", "model", "MAE h", "MAE d", "Spearman",
        "pairwise", "slow10% d");
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score sc = m.score();
      System.out.printf("  %-24s %9.2f %9.2f %9.3f %9.3f %9.2f%n", m.name(), sc.maeHours(),
          sc.maeDays(), sc.spearman(), sc.pairwise(), m.bands().get(4).mae() / 24);
      rows.add(modelRow(m, choices.get(m.name())));
    }
    System.out.printf("%n  error by band of the true duration, MAE in days%n");
    for (final SimilarityScoring.Scored m : r.models()) {
      final StringBuilder line = new StringBuilder(String.format("  %-24s", m.name()));
      for (final Breakdown.Band b : m.bands()) {
        line.append(String.format("  %s %.2f", b.label(), b.mae() / 24));
      }
      System.out.println(line);
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
    run.put("elastic_net_parameters_sha256", elasticNetSha);
    run.put("predictions_sha256", predictionsSha);
    run.put("diagnostics_sha256", diagnosticsSha);
    final Map<String, Object> candidates = new LinkedHashMap<>();
    ElasticNetSelection.combinations(choices).forEach((name, c) -> candidates.put(name,
        combination(c)));
    run.put("candidates", candidates);
    run.put("fit_rows", r.fitRows());
    run.put("scored_rows", r.scoredRows());
    run.put("inner_fit_rows", r.innerFitRows());
    run.put("final_lambda", "chosen lambda x fit_rows / inner_fit_rows; alpha as chosen");
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
   * Against a previous run's predictions from the same two inputs, the largest difference; null
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

  /** A combination as a document, its fields in a fixed order. */
  private static Map<String, Object> combination(final ElasticNetSelection.Combination c) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("jaccard_n", c.jaccardN());
    out.put("embedding_n", c.embeddingN());
    out.put("cluster_k", c.clusterK());
    return out;
  }

  /**
   * @param choice the model's parameters; null for a reference
   */
  private static Map<String, Object> modelRow(final SimilarityScoring.Scored m,
      final ElasticNetSelection.Choice choice) {
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
      if (choice != null && choice.combination() != null) {
        row.putAll(combination(choice.combination()));
      }
      row.put("columns", m.fitted().columns());
      row.put("selected", m.fitted().selected());
      row.put("removed", m.removed().stream().map(x -> x.column() + ": " + x.reason()).toList());
      row.put("dropped_constant", m.fitted().dropped());
    }
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
      throw new CommandFailedException(List.of("  MATRIX, ELASTIC_NET_PARAMETERS and "
          + "REPORT_DIR are required; missing " + name));
    }
    return Path.of(value);
  }
}
