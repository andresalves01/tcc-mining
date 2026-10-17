package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.ProjectSplit;
import app.issues.RegistrationInProgress;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.model.Fitting;
import app.issues.model.Forest;
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
 * SimilarityScoring}): nineteen models -- the global, project and executor-project medians, the
 * eight Elastic Nets, the retrospective scenario seventh ({@link Retrospective}) and the own
 * specification with similarity before ticket 19 last, and, as a
 * robustness check, the random forest beside each -- refitted on the outer training set with the
 * parameters the selection step wrote, the outer test scored once, every agreed comparison, and
 * the side reading of the retrospective comparison on the issues with an implementation commit. The target is whole business days, on the issues of at least one
 * ({@link BusinessDays}): every row is read with its day boundary from the matrix and any of 0
 * business days is dropped from the outer training and the outer test before anything is fitted
 * (none since ticket 21, the split being drawn over the issues of at least one);
 * every error, band and comparison is in business days.
 *
 * <p>Reads MATRIX and ELASTIC_NET_PARAMETERS, which gives per model its alpha and lambda, for
 * the five with the similarity medians the Jaccard n, embedding n and cluster k they read, and
 * its forest's column share and leaf; it chooses nothing. A parameters file without a forest for
 * every model is refused. Refuses, scoring, writing and recording nothing, unless the parameters file
 * exists and is the step's own, its choices were made on this very matrix (by SHA-256), every
 * row's split is the one its rule draws ({@link ProjectSplit}), and the matrix has every column
 * the models read.
 * Beside the error comparisons, the ordering intervals, a declared measure stated before the
 * outer test was opened ({@link #ORDERING}, {@link ProjectBootstrap#ordering}), printed and
 * recorded. Writes the per-issue predictions, the models' diagnostics and the forests' permutation
 * importance to REPORT_DIR; records the run,
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
   * Per forest, each design column's permutation importance on the outer test: the rise in mean
   * absolute error, in business days, when its values are shuffled. A diagnostic.
   */
  public static final String IMPORTANCE = "similarity-test-forest-importance.tsv";

  /**
   * The SHA-256 of the two inputs the predictions beside it came from: a rerun is compared with
   * the previous predictions only when these are the same.
   */
  public static final String INPUTS = "similarity-test-inputs.tsv";

  private static final String EXAMINED = "the outer test is the registered in-progress study's, "
      + "which the earlier study already scored and examined; this run reads it again and is not "
      + "a test untouched by the author";

  private static final String EFFORT = "selection effort differs between families: the two "
      + "registered cells (Al-Zubaidi, Kikas) chose only alpha and lambda on the 3 x 12 grid; the "
      + "own specification without similarity chose the same; the "
      + ElasticNetSelection.WITH_SIMILARITY.size() + " models reading the "
      + "similarity medians chose also their Jaccard n, embedding n and cluster k among "
      + ElasticNetSelection.Combination.all().size() + " "
      + "combinations, each swept on the same grid; each forest chose its column share and leaf "
      + "on its own grid with every column its Elastic Net kept; the references chose nothing";

  /**
   * The ordering intervals (ticket 16): a declared measure, stated before the outer test was
   * opened, in this step's run record and in the inner report.
   */
  static final String ORDERING = "ordering intervals, a declared measure stated before the outer "
      + "test was opened: for every agreed pair and every model against the project median (the "
      + "global median orders nothing), the paired difference a - b in Spearman and in pairwise "
      + "ordering accuracy, as every score defines them, with its 95% interval from the same "
      + ProjectBootstrap.DRAWS + " draws of whole projects as the error comparisons (seed "
      + ProjectBootstrap.SEED + "); an interval containing zero is a tie";

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
    final Path matrix = path(settings, "MATRIX");
    final Path elasticNet = path(settings, "ELASTIC_NET_PARAMETERS");
    final Path reports = path(settings, "REPORT_DIR");
    final Path predictionsFile = reports.resolve(PREDICTIONS);
    final Path diagnosticsFile = reports.resolve(DIAGNOSTICS);
    final Path inputsFile = reports.resolve(INPUTS);
    final Path importanceFile = reports.resolve(IMPORTANCE);
    final long began = System.nanoTime();
    for (final Path out : List.of(predictionsFile, diagnosticsFile, inputsFile, importanceFile)) {
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
    final Map<String, Forest.Choice> forests;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      final Chosen chosen;
      try {
        chosen = chosen(elasticNet, matrix);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      elasticNetSha = chosen.elasticNetSha();
      matrixSha = chosen.matrixSha();
      choices = chosen.choices();
      forests = chosen.forests();
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    // the split is checked before anything of the matrix is printed, its sizes included
    final List<String> split = ProjectSplit.differences(entries);
    if (!split.isEmpty()) {
      throw refused(split.get(0));
    }
    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY -- OUTER TEST%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());
    System.out.printf("  Elastic Net choices %s  sha256 %s (%d models)%n", elasticNet,
        elasticNetSha, choices.size());
    final RegistrationInProgress.Sizes s = ElasticNetSelection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    final List<String> missing;
    try {
      missing = IssuesSimilarityElasticNetCommand.missing(ElasticNetSelection.cells(
          ElasticNetSelection.combinations(choices), Augmentation.Variant.OUTER), columns);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    if (!missing.isEmpty()) {
      throw refused(matrix + " has no column " + missing
          + IssuesSimilarityElasticNetCommand.retrospectiveHint(missing));
    }
    final String unwritten = BusinessDays.unwritten(columns);
    if (unwritten != null) {
      throw refused(matrix + ": " + unwritten);
    }
    final BusinessDays.Population population;
    try {
      population = BusinessDays.population(entries);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }

    System.out.printf("%n  NOTE: %s.%n  NOTE: %s.%n", EXAMINED, EFFORT);
    System.out.printf("  target: %s; %s%n", BusinessDays.TARGET, BusinessDays.HOLIDAYS);
    System.out.printf("  population: %s%n", BusinessDays.POPULATION);
    System.out.printf("  issues of at least one business day: outer training %,d of %,d, outer "
        + "test %,d of %,d%n", population.kept().train(), population.all().train(),
        population.kept().test(), population.all().test());
    System.out.printf("  the parameters are read, never chosen; the outer test is scored now%n");
    final long fitting = System.nanoTime();
    final SimilarityScoring.Report r;
    try {
      r = SimilarityScoring.run(population.entries(), choices, forests,
          SimilarityScoring.Scope.RESERVED);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final double scoringSeconds = (System.nanoTime() - fitting) / 1e9;

    System.out.printf("%n  refit on the outer training set (%,d rows); scored %,d rows of the "
        + "outer test%n", r.fitRows(), r.scoredRows());
    System.out.printf("  final lambda = chosen lambda x %,d / %,d, alpha as chosen; no "
        + "recalibration%n", r.fitRows(), r.innerFitRows());
    System.out.printf("  random forests (robustness check): %d trees, depth %d, seed %d, the chosen "
        + "count of columns per split and the chosen leaf%n", Forest.TREES,
        Forest.MAX_DEPTH, Forest.SEED);
    System.out.printf("  test projects with no issue in training (there the project median is "
        + "the global one): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());

    final List<Map<String, Object>> rows = new ArrayList<>();
    System.out.printf("%n  %-24s %9s %12s %9s %9s  (w/o slow10%%: the rows at or under the "
        + "90th percentile of the truth, %,d of %,d; ties at it all kept)%n", "model", "MAE bd",
        "w/o slow10%", "Spearman", "pairwise", r.models().get(0).errors().trimmedN(),
        r.scoredRows());
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score sc = m.score();
      System.out.printf("  %-24s %9.3f %12.3f %9.3f %9.3f%n", m.name(), sc.mae(),
          m.errors().trimmedMae(), sc.spearman(), sc.pairwise());
      rows.add(modelRow(m, choices.get(m.name())));
      if (m.forest() != null) {
        System.out.printf("    %-22s mtry %d (share %.3f), leaf %d%n", "", m.forest().mtry(),
            m.forest().share(), m.forest().nodeSize());
      }
    }
    System.out.printf("%n  error by band of the true business days (1, 2-3, 4-10, 11+), MAE in "
        + "business days%n");
    for (final SimilarityScoring.Scored m : r.models()) {
      final StringBuilder line = new StringBuilder(String.format("  %-24s", m.name()));
      for (final Breakdown.Band b : m.bands()) {
        line.append(String.format("  %s: %.3f (n %d)", b.label(), b.mae(), b.n()));
      }
      System.out.println(line);
    }
    System.out.printf("%n  paired comparisons: MAE(a) - MAE(b) in business days, 95%% interval "
        + "resampling whole projects (%d draws); an interval containing zero is a tie%n",
        ProjectBootstrap.DRAWS);
    for (final ProjectBootstrap.Gap g : r.comparisons()) {
      System.out.printf("  %-24s vs %-24s %10.3f [%10.3f, %10.3f]%s%n", g.a(), g.b(),
          g.gap(), g.low(), g.high(), g.tied() ? "  tie" : "");
      rows.add(comparisonRow(g));
    }
    System.out.printf("%n  ordering intervals (a declared measure): a - b in Spearman and in "
        + "pairwise ordering, 95%% interval from the same %d draws of whole projects; an "
        + "interval containing zero is a tie; the global median orders nothing%n",
        ProjectBootstrap.DRAWS);
    printOrderings(r.orderings());
    r.orderings().forEach(o -> rows.add(orderingRow(o)));
    System.out.printf("%n  side reading, the retrospective scenario on the %,d of %,d scored issues "
        + "with at least one implementation commit, intervals resampling their projects%n",
        r.withCommitRows(), r.scoredRows());
    for (final ProjectBootstrap.Gap g : r.withCommits()) {
      System.out.printf("  %-24s vs %-24s %10.3f [%10.3f, %10.3f]%s%n", g.a(), g.b(),
          g.gap(), g.low(), g.high(), g.tied() ? "  tie" : "");
      final Map<String, Object> row = comparisonRow(g);
      row.put("kind", "side_reading_with_commits");
      row.put("n", r.withCommitRows());
      rows.add(row);
    }

    final List<String> predictions = SimilarityScoring.predictions(r);
    final Double rerun;
    final String predictionsSha;
    final String diagnosticsSha;
    final String importanceSha;
    try {
      Files.createDirectories(reports);
      final List<String> inputs = List.of("matrix_sha256\t" + matrixSha,
          "elastic_net_parameters_sha256\t" + elasticNetSha);
      rerun = rerun(predictionsFile, inputsFile, inputs, predictions);
      write(inputsFile, inputs);
      write(predictionsFile, predictions);
      write(diagnosticsFile, diagnostics(r));
      write(importanceFile, importance(r));
      predictionsSha = IssuesReservedCommand.sha256(predictionsFile);
      diagnosticsSha = IssuesReservedCommand.sha256(diagnosticsFile);
      importanceSha = IssuesReservedCommand.sha256(importanceFile);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + reports + ": " + e));
    }
    System.out.printf("%n  predictions written to %s  sha256 %s%n", predictionsFile,
        predictionsSha);
    System.out.printf("  diagnostics written to %s  sha256 %s%n", diagnosticsFile,
        diagnosticsSha);
    System.out.printf("  forests' permutation importance written to %s  sha256 %s%n",
        importanceFile, importanceSha);
    if (rerun != null) {
      System.out.printf("  against the predictions of the previous run there: largest "
          + "difference %.3g business days, tolerance %.0e: %s%n", rerun,
          SimilarityScoring.RERUN_TOLERANCE,
          rerun <= SimilarityScoring.RERUN_TOLERANCE ? "REPRODUCED" : "NOT REPRODUCED");
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("mode", "outer");
    run.put("outer_test", "scored");
    run.put("models", r.models().stream().map(SimilarityScoring.Scored::name).toList());
    run.putAll(statements());
    run.put("population_rows", populationRows(population));
    run.put("matrix_sha256", matrixSha);
    run.put("elastic_net_parameters_sha256", elasticNetSha);
    run.put("predictions_sha256", predictionsSha);
    run.put("diagnostics_sha256", diagnosticsSha);
    run.put("forest_importance_sha256", importanceSha);
    run.put("forest_grid", IssuesSimilarityElasticNetCommand.forestGrid());
    run.put("forest_permutation_repeats", Forest.PERMUTATION_REPEATS);
    final Map<String, Object> candidates = new LinkedHashMap<>();
    ElasticNetSelection.combinations(choices).forEach((name, c) -> candidates.put(name,
        combination(c)));
    run.put("candidates", candidates);
    run.put("fit_rows", r.fitRows());
    run.put("scored_rows", r.scoredRows());
    run.put("scored_rows_with_implementation_commit", r.withCommitRows());
    run.put("inner_fit_rows", r.innerFitRows());
    run.put("final_lambda", "chosen lambda x fit_rows / inner_fit_rows; alpha as chosen");
    run.put("bootstrap_draws", ProjectBootstrap.DRAWS);
    run.put("bootstrap_seed", ProjectBootstrap.SEED);
    run.put("ordering_pairs", r.orderings().stream().map(o -> o.a() + " vs " + o.b()).toList());
    run.put("rerun_tolerance_business_days", SimilarityScoring.RERUN_TOLERANCE);
    run.put("rerun_max_difference_business_days", rerun);
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

  /**
   * A parameters file's choices, as the test and the inner report read them.
   *
   * @param elasticNetSha the SHA-256 of the bytes parsed
   * @param matrixSha the SHA-256 of the matrix, the one the choices were made on
   */
  record Chosen(String elasticNetSha, String matrixSha,
      Map<String, ElasticNetSelection.Choice> choices, Map<String, Forest.Choice> forests) {}

  /**
   * The choices of {@code elasticNet}, refused -- an {@link IllegalArgumentException} with the
   * reason -- unless the file exists and is the Elastic Net step's own for these models, with a
   * forest for each, and its choices were made on {@code matrix}, by SHA-256. Nothing of the
   * matrix but its bytes' hash is read.
   */
  static Chosen chosen(final Path elasticNet, final Path matrix) throws IOException {
    if (!Files.exists(elasticNet)) {
      throw new IllegalArgumentException(elasticNet + " does not exist; run "
          + "issues-in-progress-similarity-elastic-net first");
    }
    // parsed and hashed from the same bytes, so the hash recorded is of what was read
    final byte[] elasticNetBytes = Files.readAllBytes(elasticNet);
    final List<String> elasticNetLines =
        new String(elasticNetBytes, StandardCharsets.UTF_8).lines().toList();
    if (!IssuesSimilarityElasticNetCommand.onlyThisStep(elasticNetLines)) {
      throw new IllegalArgumentException(elasticNet + " is not the Elastic Net step's parameters "
          + "file for the models " + ElasticNetSelection.MODELS + "; it may be another step's, "
          + "or one written in an earlier format or for the models before ticket 12");
    }
    final List<ElasticNetSelection.Parameter> enParameters = ElasticNetSelection.read(
        elasticNetLines);
    final Map<String, ElasticNetSelection.Choice> choices =
        ElasticNetSelection.choices(enParameters);
    final Map<String, Forest.Choice> forests = ElasticNetSelection.forests(enParameters);
    if (!Set.copyOf(ElasticNetSelection.MODELS).equals(choices.keySet())) {
      throw new IllegalArgumentException(elasticNet + " holds alpha and lambda for "
          + choices.keySet() + "; the models are " + ElasticNetSelection.MODELS);
    }
    if (!Set.copyOf(ElasticNetSelection.MODELS).equals(forests.keySet())) {
      throw new IllegalArgumentException(elasticNet + " holds the forest parameters for "
          + forests.keySet() + "; every model has a forest, " + ElasticNetSelection.MODELS
          + "; run issues-in-progress-similarity-elastic-net again");
    }
    final String matrixSha = IssuesReservedCommand.sha256(matrix);
    if (!matrixSha.equals(ElasticNetSelection.matrixSha(enParameters))) {
      throw new IllegalArgumentException("the choices in " + elasticNet + " were chosen on "
          + "another matrix (sha256 " + ElasticNetSelection.matrixSha(enParameters) + "), not "
          + matrix + " (sha256 " + matrixSha + ")");
    }
    return new Chosen(sha256(elasticNetBytes), matrixSha, choices, forests);
  }

  /** What the run record states about the test it scores. */
  static Map<String, Object> statements() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("outer_test_examined_before", EXAMINED);
    out.put("selection_effort", EFFORT);
    out.put("ordering_intervals", ORDERING);
    out.putAll(IssuesSimilarityElasticNetCommand.statements());
    return out;
  }

  /** The rows of the outer training and the outer test before and after the drop of 0 days. */
  private static Map<String, Object> populationRows(final BusinessDays.Population p) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("outer_training", List.of(p.kept().train(), p.all().train()));
    out.put("outer_test", List.of(p.kept().test(), p.all().test()));
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

  /**
   * Per model, each declared column: its weight, its removal or its drop for being constant. A
   * forest has no weight: only its removals and drops are listed, its columns' importance being in
   * {@link #IMPORTANCE}.
   */
  static List<String> diagnostics(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("model\tcolumn\tstatus\tcoefficient\tselected\treason");
    for (final SimilarityScoring.Scored m : r.models()) {
      if (m.forest() != null) {
        for (final ElasticNetSelection.Removed x : m.removed()) {
          out.add(String.join("\t", m.name(), x.column(), "removed", "", "", x.reason()));
        }
        for (final String d : m.forest().dropped()) {
          out.add(String.join("\t", m.name(), d, "dropped", "", "",
              "constant on the " + r.scope().fittingSet() + " rows"));
        }
        continue;
      }
      if (m.fitted() == null) {
        continue;
      }
      for (final ElasticNetSelection.Removed x : m.removed()) {
        out.add(String.join("\t", m.name(), x.column(), "removed", "", "", x.reason()));
      }
      for (final String d : m.fitted().dropped()) {
        out.add(String.join("\t", m.name(), d, "dropped", "", "",
            "constant on the " + r.scope().fittingSet() + " rows"));
      }
      for (final Fitting.Contribution c : m.fitted().contributions()) {
        out.add(String.join("\t", m.name(), c.column(), "fitted",
            Double.toString(c.coefficient()), Boolean.toString(c.selected()), ""));
      }
    }
    return out;
  }

  /**
   * Per forest, each design column's permutation importance on the outer test: the mean rise in
   * mean absolute error, in business days, over the repeats, then each repeat's, as Java prints
   * a double.
   */
  static List<String> importance(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    final StringBuilder header = new StringBuilder("model\tcolumn\tmean_mae_increase_bd");
    for (int k = 1; k <= Forest.PERMUTATION_REPEATS; k++) {
      header.append("\trepeat_").append(k).append("_bd");
    }
    out.add(header.toString());
    for (final SimilarityScoring.Scored m : r.models()) {
      if (m.forest() == null) {
        continue;
      }
      for (final Forest.Importance i : m.forest().importance()) {
        final StringBuilder line = new StringBuilder(m.name()).append('\t').append(i.column())
            .append('\t').append(i.meanIncrease());
        for (final double v : i.increases()) {
          line.append('\t').append(v);
        }
        out.add(line.toString());
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
    row.put("mae_business_days", s.mae());
    row.put("gain_over_project_percent", s.gainPercent());
    row.put("gain_over_global_percent", s.gainOverConstantPercent());
    row.put("spearman", s.spearman());
    row.put("pairwise", s.pairwise());
    final Breakdown.Errors e = m.errors();
    row.put("abs_error_p50_business_days", e.p50());
    row.put("abs_error_p90_business_days", e.p90());
    row.put("mae_without_slowest_tenth_business_days", e.trimmedMae());
    row.put("rows_without_slowest_tenth", e.trimmedN());
    final List<Map<String, Object>> bands = new ArrayList<>();
    for (final Breakdown.Band b : m.bands()) {
      final Map<String, Object> band = new LinkedHashMap<>();
      band.put("band", b.label());
      band.put("n", b.n());
      band.put("median_truth_business_days", b.medianTruth());
      band.put("mae_business_days", b.mae());
      band.put("median_error_business_days", b.medianError());
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
    if (m.forest() != null) {
      row.put("mtry_share", m.forest().share());
      row.put("mtry", m.forest().mtry());
      row.put("node_size", m.forest().nodeSize());
      row.put("columns", m.forest().columns());
      row.put("removed", m.removed().stream().map(x -> x.column() + ": " + x.reason()).toList());
      row.put("dropped_constant", m.forest().dropped());
    }
    return row;
  }

  /** Each ordering comparison: the two differences, each with its interval. */
  static void printOrderings(final List<ProjectBootstrap.Ordering> orderings) {
    System.out.printf("  %-24s    %-24s %27s   %27s%n", "a", "b", "Spearman a - b [95%]",
        "pairwise a - b [95%]");
    for (final ProjectBootstrap.Ordering o : orderings) {
      System.out.printf("  %-24s vs %-24s %+7.3f [%+7.3f, %+7.3f]%s %+7.3f [%+7.3f, %+7.3f]%s%n",
          o.a(), o.b(), o.spearman().gap(), o.spearman().low(), o.spearman().high(),
          o.spearman().tied() ? " tie" : "    ", o.pairwise().gap(), o.pairwise().low(),
          o.pairwise().high(), o.pairwise().tied() ? " tie" : "");
    }
  }

  private static Map<String, Object> orderingRow(final ProjectBootstrap.Ordering o) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", "ordering_comparison");
    row.put("a", o.a());
    row.put("b", o.b());
    row.put("spearman_diff", o.spearman().gap());
    row.put("spearman_ci_projects", List.of(o.spearman().low(), o.spearman().high()));
    row.put("spearman_tie", o.spearman().tied());
    row.put("pairwise_diff", o.pairwise().gap());
    row.put("pairwise_ci_projects", List.of(o.pairwise().low(), o.pairwise().high()));
    row.put("pairwise_tie", o.pairwise().tied());
    return row;
  }

  private static Map<String, Object> comparisonRow(final ProjectBootstrap.Gap g) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", "comparison");
    row.put("a", g.a());
    row.put("b", g.b());
    row.put("mae_diff_business_days", g.gap());
    row.put("ci_projects", List.of(g.low(), g.high()));
    row.put("tie", g.tied());
    return row;
  }

  static void write(final Path file, final List<String> lines) throws IOException {
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

  static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  /** A required path setting, by name; a missing one refuses the run. */
  static Path path(final Function<String, String> settings, final String name)
      throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX, ELASTIC_NET_PARAMETERS and "
          + "REPORT_DIR are required; missing " + name));
    }
    return Path.of(value);
  }
}
