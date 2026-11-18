package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.AnalogyEstimate;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.EstimationUncertainty;
import app.issues.PredictedBands;
import app.issues.PredictionIntervals;
import app.issues.ProjectSplit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.SplitRule;
import app.issues.TailCut;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.model.ProjectBootstrap;
import app.issues.port.ResultStore;
import app.model.Parallel;
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
 * The study's test step, outer mode only (see {@link
 * SimilarityScoring}): the fourteen models the inner report scores -- the global and project
 * medians, the six Elastic Nets of {@link ElasticNetSelection#MODELS} (the own start model,
 * Al-Zubaidi and Kikas at the start; the retrospective scenario's main reading ({@link
 * Retrospective}), without the comments and the participants, the same with them, a ceiling and
 * not a competitor, and Kikas's retrospective one) and, as a robustness check, the random forest
 * beside each -- refitted on the outer training set with the parameters the selection step
 * wrote, the outer test scored once, every agreed comparison, and the side reading of the
 * retrospective comparison on the issues with an implementation commit. The target is whole
 * business days, on the issues of at least one ({@link BusinessDays}): every row is read with its
 * day boundary from the matrix and any of 0 business days is dropped from the outer training and
 * the outer test before anything is fitted (none since ticket 21, the split being drawn over the
 * issues of at least one); every error, band and comparison is in business days.
 *
 * <p><b>Tail cut</b> (ticket 23; {@link TailCut}). Every model, forest and reference is refitted
 * on the outer training rows at or below the fence of the k the parameters file records, both
 * fences taken on the outer training set, never on the outer test. Every table is printed and
 * recorded twice, each document with its {@code reading}: on the outer test issues at or below
 * the usual flow's fence, the main reading, and on every one, the complementary reading. The
 * predictions file holds every scored issue; the diagnostics are the main reading's.
 *
 * <p><b>Relevance</b> ({@link SimilarityScoring#relevance}). Per Elastic Net model, each
 * standardised column's coefficient in the refit on the outer training set, with its 95%
 * interval from resampling whole projects of the outer training set, printed, written ({@link
 * #RELEVANCE}) and recorded. No forest's permutation importance: shuffling a column among the
 * outer test's issues and reading the rise in error would read the test's labels column by
 * column.
 *
 * <p><b>Prediction intervals</b> (ticket 24; {@link PredictionIntervals}). The inner models are
 * refitted as the inner report fits them and calibrated on the whole usual-flow inner validation
 * ({@link SimilarityScoring#calibration}); their factors, one per model and level, are applied to
 * the outer models' predictions, an approximation (item 53 viii), and the coverage, median width,
 * width over the global median's and MAE are printed and recorded per reading, model and level,
 * overall and per band of the observed duration, read after the fact. The bands, the error
 * bands', are cut at the quartiles of the outer training rows fitted.
 *
 * <p><b>The first-commit study's temporal split</b> (its ticket 07). The parameters file's header
 * names the split rule its choices were made under ({@link ElasticNetSelection#headerRule}, the
 * random one when it names none), and a file of another rule than the run's is refused. Under the
 * temporal rule the analogy estimate ({@link AnalogyEstimate}) is the fifteenth model, its column
 * read from the file ({@link ElasticNetSelection#analogy}), never chosen here, its fallback the
 * global median the declared reference learns on the outer training; a temporal file without
 * that choice is refused, and so is a file of another rule with it, where the analogy estimate
 * stays exploratory. The forests are refitted with the file's tree count ({@link
 * ElasticNetSelection#trees}); a temporal file must give it, a random one without it is at {@link
 * Forest#TREES}. Every reading is also given without the issues of new
 * projects, those with issues on the outer test side and none on the outer training side, with
 * their count; with none, the readings without them are the readings themselves and are not
 * repeated. The temporal opening is recorded under {@link #FIRST_COMMIT_TEMPORAL_STAGE} and, as
 * the random one, runs once. The revised design ({@code SPLIT_RULE=revised}) is read as a
 * temporal one, recorded under {@link #FIRST_COMMIT_REVISED_STAGE}, once, and its record never
 * says the outer test is read for the first time: it had been read once under the design of
 * 02/11/2026.
 *
 * <p>Reads MATRIX and ELASTIC_NET_PARAMETERS, which gives per model its alpha and lambda, for
 * the three with the similarity medians the Jaccard n, embedding n and cluster k they read, and
 * its forest's column share and leaf; it chooses nothing. A parameters file without a forest for
 * every model, or written for other models, is refused. Refuses, scoring, writing and recording nothing, unless the parameters file
 * exists and is the step's own, its choices were made on this very matrix (by SHA-256), every
 * row's split is the one the rule the run declares draws ({@link SplitRule}, setting {@code
 * SPLIT_RULE}), and the matrix has every column
 * the models read.
 * Beside the error comparisons, the ordering intervals, a declared measure stated before the
 * outer test was opened ({@link #ORDERING}, {@link ProjectBootstrap#ordering}), printed and
 * recorded. Writes the per-issue predictions, the models' diagnostics and the relevance to
 * REPORT_DIR; records the run,
 * with the SHA-256 of every input and output, the time taken, the hardware and the versions, in
 * ISSUES_DB under its own stage name.
 */
public final class IssuesSimilarityTestCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-test";

  /**
   * The step that opens the first-commit study's outer test (its ticket 05): the same scoring on
   * that population, run once. It refuses when ISSUES_DB already holds a run under this name or
   * REPORT_DIR already holds predictions.
   */
  public static final String FIRST_COMMIT_STAGE = "issues-first-commit-test";

  /**
   * The step that opens the outer test of the first-commit study's temporal split, recorded under
   * a name of its own: ISSUES_DB already holds the opening of the random split's, under {@link
   * #FIRST_COMMIT_STAGE}, and each is opened once.
   */
  public static final String FIRST_COMMIT_TEMPORAL_STAGE = "issues-first-commit-test-temporal";

  /**
   * The step that reads the outer test of the temporal split's revised design, recorded under a
   * name of its own: ISSUES_DB already holds the opening of the design of 02/11/2026, under {@link
   * #FIRST_COMMIT_TEMPORAL_STAGE}, and this one runs once too.
   */
  public static final String FIRST_COMMIT_REVISED_STAGE = "issues-first-commit-test-revised";

  /**
   * What the revised design's run says of its reading of the outer test, factual: the same outer
   * test had been read once under the design of 02/11/2026, so it is not read for the first time.
   */
  static final String REVISED_READING = "read under the revised design; the outer test had been "
      + "read once under the design of 02/11/2026";

  /** The outer test of the temporal split's revised design, a sentence of its own. */
  static final String FIRST_COMMIT_REVISED_EXAMINED = "the outer test is the first-commit "
      + "population's temporal split: every issue whose first commit came after the cut, "
      + REVISED_READING + ".";

  /** The first-commit study's outer test, a sentence of its own. */
  static final String FIRST_COMMIT_EXAMINED = "the outer test is the first-commit population's "
      + "draw of 30% per project, seed 1738, read for the first time by this run.";

  /** The outer test of the first-commit study's temporal split, a sentence of its own. */
  static final String FIRST_COMMIT_TEMPORAL_EXAMINED = "the outer test is the first-commit "
      + "population's temporal split: every issue whose first commit came after the cut, read "
      + "for the first time by this run.";

  /** What the analogy estimate is in the test, for the output and the run record. */
  static final String ANALOGY_RULE = "the analogy estimate, the fifteenth model of the temporal "
      + "split: the similarity median the parameters file froze, chosen on the usual-flow inner "
      + "validation, read in its outer column as the estimate with no model around it; an issue "
      + "without one takes the global median learned on the outer training rows at or below the "
      + "fence, the declared reference's own value; nothing of it is chosen on the outer test";

  /** What a new project is, for the output and the run record. */
  static final String NEW_PROJECTS = "new projects: projects with issues on the outer test side "
      + "and none on the outer training side, before the tail cut; there the project median is "
      + "the global one, and every reading is also given without their issues (the line above "
      + "counts after the tail cut, so it also holds a project whose training issues all lie "
      + "above the fence)";

  /** One line per scored issue, one column per model. */
  public static final String PREDICTIONS = "similarity-test-predictions.tsv";

  /** Per model, each column it was declared with: its weight, or why it was left out. */
  public static final String DIAGNOSTICS = "similarity-test-diagnostics.tsv";

  /**
   * Per Elastic Net model, each standardised column's coefficient in the refit on the outer
   * training set, with its 95% interval from resampling whole projects of the outer training set.
   */
  public static final String RELEVANCE = "similarity-test-relevance.tsv";

  /**
   * The SHA-256 of the two inputs the predictions beside it came from: a rerun is compared with
   * the previous predictions only when these are the same.
   */
  public static final String INPUTS = "similarity-test-inputs.tsv";
  public static final String UNCERTAINTY = "similarity-test-uncertainty.tsv";
  public static final String PERCENTILES = "similarity-test-prediction-percentiles.tsv";
  public static final String UNCERTAINTY_REPORT = "similarity-test-uncertainty.md";

  /** What the outer test is, a sentence of its own (André, 17/10/2026). */
  static final String EXAMINED = "the outer test is a new draw of 30% per project, seed 1738.";

  private static final String EFFORT = "selection effort differs between families: the three "
      + "literature sets (Al-Zubaidi and Kikas at the start, Kikas's retrospective one) chose "
      + "only alpha and lambda on the 3 x 12 grid; the "
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

  /**
   * Why this step scores no other population: the first-commit study's outer test is opened once,
   * by a step of its own, with the design frozen and the author's explicit authorisation.
   */
  static final String SEALED = "the outer test of the first-commit study is sealed: it is opened "
      + "once, with the design frozen and the author's explicit authorisation, and this step "
      + "scores the In Progress study's alone";

  /** Where each setting is read from: the environment, then the system properties. */
  private final Function<String, String> settings;

  /** The population this step scores: the In Progress study's, or the first-commit study's. */
  private final app.issues.Universe.Population study;

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
    this(settings, app.issues.Universe.Population.IN_PROGRESS);
  }

  /** The step for {@code study}: the In Progress study's test or the first-commit study's. */
  IssuesSimilarityTestCommand(final Function<String, String> settings,
      final app.issues.Universe.Population study) {
    this.settings = settings;
    this.study = study;
  }

  /** The first-commit study's test step, settings from the environment. */
  public static IssuesSimilarityTestCommand firstCommit() {
    return new IssuesSimilarityTestCommand(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    }, app.issues.Universe.Population.FIRST_COMMIT);
  }

  private boolean firstCommitStudy() {
    return study == app.issues.Universe.Population.FIRST_COMMIT;
  }

  private String stage(final SplitRule rule) {
    return !firstCommitStudy() ? STAGE
        : switch (rule) {
          case RANDOM -> FIRST_COMMIT_STAGE;
          case TEMPORAL -> FIRST_COMMIT_TEMPORAL_STAGE;
          case REVISED -> FIRST_COMMIT_REVISED_STAGE;
        };
  }

  private String examined(final SplitRule rule) {
    return !firstCommitStudy() ? EXAMINED
        : switch (rule) {
          case RANDOM -> FIRST_COMMIT_EXAMINED;
          case TEMPORAL -> FIRST_COMMIT_TEMPORAL_EXAMINED;
          case REVISED -> FIRST_COMMIT_REVISED_EXAMINED;
        };
  }

  /**
   * Why the revised design's outer test may not be opened on the matrix of {@code matrixSha}, or
   * null when it may. It is opened once, so it waits for the whole revised design: the newest
   * first-commit extraction run of the design's collection must declare the crossing issues left
   * out of what the training side teaches ({@code crossing_left_out}), and the similarity run that
   * wrote the matrix's similarity columns must declare point-in-time histories ({@link
   * IssuesSimilarityCommand#POINT_IN_TIME_HISTORY}). That similarity run is found through the
   * retrospective run that wrote the matrix: the retrospective run's input is the similarity run's
   * output. Reads run records alone, never a row or a label; the step calls it before it reads
   * the parameters or any row of the matrix.
   */
  static String revisedUnready(final ResultStore store, final String matrixSha) {
    final java.util.Optional<List<Map<String, Object>>> extractions =
        store.recordedRuns(IssuesInProgressExtractCommand.STAGE);
    final java.util.Optional<List<Map<String, Object>>> similarities =
        store.recordedRuns(IssuesSimilarityCommand.STAGE);
    final java.util.Optional<List<Map<String, Object>>> retrospectives =
        store.recordedRuns(IssuesRetrospectiveCommand.STAGE);
    if (extractions.isEmpty() || similarities.isEmpty() || retrospectives.isEmpty()) {
      return "ISSUES_DB cannot tell which runs made this matrix; the revised design's outer test "
          + "is opened once, on a matrix of the whole revised design";
    }
    final Map<String, Object> extraction = extractions.get().stream()
        .filter(r -> app.issues.Universe.Population.FIRST_COMMIT.name()
            .equals(r.get("study_population")))
        .findFirst().orElse(null);
    if (extraction == null) {
      return "no first-commit extraction run is recorded in the revised design's collection";
    }
    if (extraction.get("crossing_left_out") == null) {
      return "the newest extraction run does not declare the crossing issues left out of what "
          + "the training side teaches (crossing_left_out); its matrix is not of the revised "
          + "design";
    }
    final Map<String, Object> retrospective = retrospectives.get().stream()
        .filter(r -> matrixSha.equals(r.get("retrospective_matrix_sha256")))
        .findFirst().orElse(null);
    if (retrospective == null) {
      return "no retrospective run of the revised design's collection wrote this matrix";
    }
    final Object input = retrospective.get("matrix_sha256");
    final Map<String, Object> similarity = similarities.get().stream()
        .filter(r -> r.get("written_sha256") instanceof Map<?, ?> w && input != null
            && input.equals(w.get("matrix")))
        .findFirst().orElse(null);
    if (similarity == null) {
      return "no similarity run of the revised design's collection wrote the matrix this one was "
          + "made from";
    }
    if (!IssuesSimilarityCommand.POINT_IN_TIME_HISTORY.equals(similarity.get("history"))) {
      return "the similarity run that wrote this matrix's columns does not declare point-in-time "
          + "histories";
    }
    return null;
  }

  /** What the run records of the outer test: under the revised design, never a first reading. */
  private String outerTest(final SplitRule rule) {
    return !firstCommitStudy() ? "scored"
        : rule == SplitRule.REVISED ? "scored, " + REVISED_READING
        : "scored, opened for the first time";
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = path(settings, "MATRIX");
    final Path elasticNet = path(settings, "ELASTIC_NET_PARAMETERS");
    final Path reports = path(settings, "REPORT_DIR");
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), study);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, written or "
          + "recorded: " + e.getMessage()));
    }
    final Path predictionsFile = reports.resolve(PREDICTIONS);
    final Path diagnosticsFile = reports.resolve(DIAGNOSTICS);
    final Path inputsFile = reports.resolve(INPUTS);
    final Path relevanceFile = reports.resolve(RELEVANCE);
    ResultStore store = context.issuesResults();
    final long began = System.nanoTime();
    for (final Path out : List.of(predictionsFile, diagnosticsFile, inputsFile, relevanceFile,
        reports.resolve(UNCERTAINTY), reports.resolve(PERCENTILES),
        reports.resolve(UNCERTAINTY_REPORT))) {
      for (final Path in : List.of(matrix, elasticNet)) {
        if (same(out, in)) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was scored, written or "
              + "recorded: " + out + " is an input of this step and is never written"));
        }
      }
    }

    // the first-commit study's outer test is sealed (its ticket 05): the In Progress step refuses
    // its matrix on the header alone, before the parameters, any row or any label is read; the
    // first-commit step takes only that matrix, and only once
    try {
      if (!firstCommitStudy()) {
        if (Files.exists(matrix) && RowCache.splitPopulations(matrix)
            .contains(app.issues.Universe.Population.FIRST_COMMIT)) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, "
              + "written or recorded: " + matrix + " holds the first-commit population's split; "
              + SEALED));
        }
      } else {
        IssuesInProgressExtractCommand.requireOwnDatabases(context.config());
        if (!Files.exists(matrix)
            || RowCache.splitPopulation(matrix) != app.issues.Universe.Population.FIRST_COMMIT) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, "
              + "written or recorded: " + matrix + " is not a matrix of the first-commit "
              + "population's split"));
        }
        if (rule.temporal()) {
          store = IssuesInProgressExtractCommand.runStore(context, study, rule);
        }
        if (Files.exists(predictionsFile)) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, "
              + "written or recorded: " + predictionsFile + " exists; the first-commit study's "
              + "outer test is opened once"));
        }
        final java.util.Optional<Boolean> before =
            store.recorded(stage(rule));
        if (before.isEmpty() || before.get()) {
          throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, "
              + "written or recorded: " + (before.isEmpty()
                  ? "ISSUES_DB cannot tell whether a run of " + stage(rule) + " exists"
                  : "ISSUES_DB already holds a run of " + stage(rule))
              + "; the first-commit study's outer test is opened once"));
        }
        // the revised design's outer test waits for the whole design: its matrix's extraction
        // leaves the crossing issues out and its similarity reads point-in-time histories
        if (rule == SplitRule.REVISED) {
          final String unready = revisedUnready(store, Sha256.of(matrix));
          if (unready != null) {
            throw new CommandFailedException(List.of("  REFUSED, nothing was read, scored, "
                + "written or recorded: " + unready));
          }
        }
      }
    } catch (final IOException | IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    final String matrixSha;
    final String elasticNetSha;
    final Map<String, ElasticNetSelection.Choice> choices;
    final Map<String, Forest.Choice> forests;
    final TailCut.Choice cut;
    final Chosen chosen;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      try {
        chosen = chosen(elasticNet, matrix);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      if (chosen.population() != study) {
        throw refused(elasticNet + " was written for " + chosen.population() + ", this step "
            + "scores " + study);
      }
      elasticNetSha = chosen.elasticNetSha();
      matrixSha = chosen.matrixSha();
      choices = chosen.choices();
      forests = chosen.forests();
      cut = chosen.cut();
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    // the split is checked before anything of the matrix is printed, its sizes included
    final String splitReferenceSetting = settings.apply("SPLIT_REFERENCE_MATRIX");
    final List<String> split;
    if (splitReferenceSetting == null || splitReferenceSetting.isBlank()) {
      split = rule.differences(entries, study);
    } else {
      final List<RowCache.Entry> reference;
      try {
        reference = RowCache.read(Path.of(splitReferenceSetting));
      } catch (final IOException e) {
        throw refused("cannot read split reference: " + e);
      }
      split = new ArrayList<>(rule.differences(reference, study));
      if (split.isEmpty()) {
        final Map<String, RowCache.Entry> byId = new LinkedHashMap<>();
        for (final RowCache.Entry e : reference) {
          if (byId.put(e.unit().issueId(), e) != null) {
            split.add("duplicate issue in split reference: " + e.unit().issueId());
            break;
          }
        }
        for (final RowCache.Entry e : entries) {
          final RowCache.Entry original = byId.get(e.unit().issueId());
          if (original == null || !original.unit().project().equals(e.unit().project())
              || !original.outer(study).equals(e.outer(study))
              || !original.inner(study).equals(e.inner(study))) {
            split.add("scored issue is absent from or moved relative to the full split: "
                + e.unit().issueId());
            break;
          }
        }
      }
    }
    if (!split.isEmpty()) {
      throw refused(split.get(0));
    }
    final String design = otherDesign(chosen, rule, elasticNet);
    if (design != null) {
      throw refused(design);
    }
    final AnalogyEstimate.Frozen analogy = chosen.analogy();
    System.out.printf("%n  DURATION FROM %s -- DESCRIPTION SIMILARITY -- OUTER TEST%n",
        firstCommitStudy() ? "THE FIRST COMMIT" : "IN PROGRESS");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());
    System.out.println(choicesLine(elasticNet, elasticNetSha));
    final ProjectSplit.Sizes s = ElasticNetSelection.sizes(entries);
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
    final String unwritten = BusinessDays.unwritten(columns, study);
    if (unwritten != null) {
      throw refused(matrix + ": " + unwritten);
    }
    final BusinessDays.Population population;
    try {
      population = BusinessDays.population(entries, Augmentation.Variant.OUTER);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }

    System.out.printf("%n  NOTE: %s%n  NOTE: %s.%n", examined(rule), EFFORT);
    final double waitK = IssuesInProgressExtractCommand.matrixWaitFenceK(store, study);
    System.out.printf("  target: %s; %s%n", BusinessDays.target(entries, waitK),
        BusinessDays.HOLIDAYS);
    System.out.printf("  population: %s%n", BusinessDays.populationRule(entries));
    System.out.printf("  issues of at least one business day: outer training %,d of %,d, outer "
        + "test %,d of %,d%n", population.kept().train(), population.all().train(),
        population.kept().test(), population.all().test());
    System.out.printf("  the parameters are read, never chosen; the outer test is scored now%n");
    final long fitting = System.nanoTime();
    // the lower end of the prediction intervals, in business days
    final int floor = app.issues.AdjustedDuration.marked(entries) ? 0 : 1;
    final SimilarityScoring.Readings readings;
    final SimilarityScoring.Readings withoutNew;
    final List<PredictionIntervals.Read> intervals;
    final List<PredictionIntervals.Read> intervalsWithoutNew;
    final List<SimilarityScoring.Relevance> relevance;
    final Map<String, PredictionIntervals.Calibration> calibrated;
    try {
      readings = SimilarityScoring.readings(population.entries(), choices, forests, cut.k(),
          SimilarityScoring.Scope.RESERVED, study, chosen.design());
      // every reading again without the issues of new projects, when there is any
      withoutNew = readings.complementary().newProjectRows() == 0 ? null
          : SimilarityScoring.withoutNewProjects(population.entries(), choices, forests, cut.k(),
              SimilarityScoring.Scope.RESERVED, study, chosen.design());
      // the coefficients of the refit on the outer training set, resampling its projects; no
      // forest's permutation importance, which would read the outer test's labels
      relevance = SimilarityScoring.relevance(population.entries(), choices, cut.k(),
          SimilarityScoring.Scope.RESERVED, study);
      // the prediction intervals (ticket 24, item 53 viii): the inner models refitted and
      // calibrated on the whole usual-flow inner validation, applied to the outer models
      calibrated =
          PredictionIntervals.calibrated(PredictionIntervals.Sample.of(
              SimilarityScoring.calibration(population.entries(), choices, forests, cut.k(),
                  study, chosen.design()), floor));
      intervals = PredictionIntervals.outer(calibrated,
          PredictionIntervals.Sample.of(readings.main(), floor),
          PredictionIntervals.Sample.of(readings.complementary(), floor),
          Parallel.threads(Parallel.BOOTSTRAP_THREADS));
      intervalsWithoutNew = withoutNew == null ? List.of()
          : PredictionIntervals.outer(calibrated,
              PredictionIntervals.Sample.of(withoutNew.main(), floor),
              PredictionIntervals.Sample.of(withoutNew.complementary(), floor),
              Parallel.threads(Parallel.BOOTSTRAP_THREADS));
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    final double scoringSeconds = (System.nanoTime() - fitting) / 1e9;
    // the complementary reading holds every scored issue: the predictions file is of those
    final SimilarityScoring.Report r = readings.complementary();
    final SimilarityScoring.Report main = readings.main();

    System.out.printf("%n  refit on the outer training set (%,d of %,d rows, those at or below "
        + "the fence of k %s, %.3f bd, taken on the outer training set); scored %,d rows of the "
        + "outer test%n", r.fitRows(), r.fences().uncutFitRows(), TailCut.label(cut.k()),
        r.fences().fence(), r.scoredRows());
    System.out.printf("  tail cut: %s; %s%n", TailCut.RULE, TailCut.QUANTILES);
    System.out.printf(PredictedBands.READINGS_LINE, TailCut.Reading.USUAL_FLOW.description(),
        main.scoredRows(), main.fences().usualFence(),
        TailCut.Reading.ALL.description(), r.scoredRows());
    System.out.printf("  final lambda = chosen lambda x %,d / %,d, alpha as chosen; no "
        + "recalibration%n", r.fitRows(), r.innerFitRows());
    System.out.printf("  random forests (robustness check): %d trees, depth %d, seed %d, the chosen "
        + "count of columns per split and the chosen leaf%n", chosen.trees(),
        Forest.MAX_DEPTH, Forest.SEED);
    System.out.printf("  test projects with no issue in training (there the project median is "
        + "the global one): %,d issues, %s%n", r.unseenRows(), r.unseenProjects());
    System.out.printf("  %s: %d, with %,d issues of the outer test (%,d of the usual flow) %s%s%n",
        NEW_PROJECTS, r.newProjects().size(), r.newProjectRows(), main.newProjectRows(),
        r.newProjects(), withoutNew == null
            ? "; with none, each reading without them is the reading itself" : "");
    if (analogy == null) {
      System.out.printf("  analogy estimate: absent; under the %s split it is not a model of "
          + "this step%n", rule.setting());
    } else {
      System.out.printf(java.util.Locale.ROOT, "  %s: reads %s%s, fallback %.3f business days; "
          + "issues given the fallback: %,d of %,d (usual flow %,d of %,d)%n", ANALOGY_RULE,
          analogy.column(), Augmentation.Variant.OUTER.suffix(), globalMedian(r),
          r.analogyFallbacks(), r.scoredRows(), main.analogyFallbacks(), main.scoredRows());
    }
    System.out.printf(PredictedBands.BANDS_LINE, BusinessDays.BANDS, BusinessDays.bandLimits(r.bandTops()));
    System.out.printf("  prediction intervals: %s; %s; %s%n", PredictionIntervals.rule(app.issues.AdjustedDuration.marked(entries) ? 0 : 1),
        PredictionIntervals.FACTOR, PredictionIntervals.OUTER);

    final List<Map<String, Object>> rows = new ArrayList<>();
    final List<SimilarityScoring.Report> scored = new ArrayList<>(List.of(main, r));
    if (withoutNew != null) {
      scored.addAll(List.of(withoutNew.main(), withoutNew.complementary()));
    }
    for (final SimilarityScoring.Report reading : scored) {
      System.out.printf("%n  READING: %s%n", readingDescription(reading));
      final List<Map<String, Object>> of = new ArrayList<>();
      print(reading, of, choices, sidePhrase(), firstCommitStudy());
      printIntervals((reading.withoutNewProjects() ? intervalsWithoutNew : intervals).stream()
          .filter(x -> x.reading() == reading.reading()).toList(), reading.bandTops(), of);
      final String name = readingName(reading);
      of.forEach(row -> row.put("reading", name));
      rows.addAll(of);
    }
    printRelevance(relevance, rows);
    final UncertaintyTables.Tables uncertainty = UncertaintyTables.of(readings,
        population.entries(), calibrated, Parallel.threads(Parallel.BOOTSTRAP_THREADS));
    rows.addAll(uncertainty.documents());
    uncertainty.markdown().forEach(line -> System.out.println("  " + line));

    final List<String> predictions = SimilarityScoring.predictions(r);
    final Double rerun;
    final String predictionsSha;
    final String diagnosticsSha;
    final String relevanceSha;
    try {
      Files.createDirectories(reports);
      final List<String> inputs = List.of("matrix_sha256\t" + matrixSha,
          "elastic_net_parameters_sha256\t" + elasticNetSha);
      rerun = rerun(predictionsFile, inputsFile, inputs, predictions);
      write(inputsFile, inputs);
      write(predictionsFile, predictions);
      write(diagnosticsFile, diagnostics(main));
      write(relevanceFile, relevance(relevance));
      write(reports.resolve(UNCERTAINTY), uncertainty.uncertainty());
      write(reports.resolve(PERCENTILES), uncertainty.percentiles());
      write(reports.resolve(UNCERTAINTY_REPORT), uncertainty.markdown());
      predictionsSha = Sha256.of(predictionsFile);
      diagnosticsSha = Sha256.of(diagnosticsFile);
      relevanceSha = Sha256.of(relevanceFile);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + reports + ": " + e));
    }
    System.out.printf("%n  predictions written to %s  sha256 %s%n", predictionsFile,
        predictionsSha);
    System.out.printf("  diagnostics written to %s  sha256 %s%n", diagnosticsFile,
        diagnosticsSha);
    System.out.printf("  relevance written to %s  sha256 %s%n", relevanceFile, relevanceSha);
    if (rerun != null) {
      System.out.printf("  against the predictions of the previous run there: largest "
          + "difference %.3g business days, tolerance %.0e: %s%n", rerun,
          SimilarityScoring.RERUN_TOLERANCE,
          rerun <= SimilarityScoring.RERUN_TOLERANCE ? "REPRODUCED" : "NOT REPRODUCED");
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", stage(rule));
    if (firstCommitStudy()) {
      run.put("split_rule", rule.rule());
      if (rule.temporal()) {
        run.put(SplitRule.DESIGN_FIELD, rule.setting());
      }
    }
    run.put("mode", "outer");
    run.put("outer_test", outerTest(rule));
    if (firstCommitStudy()) {
      run.put("outer_test_opened_at", java.time.Instant.now().toString());
    }
    run.put("models", r.models().stream().map(SimilarityScoring.Scored::name).toList());
    run.putAll(statements());
    run.put("outer_test_draw", examined(rule));
    // the first-commit study's additions (its temporal split's ticket 07); the In Progress
    // study's record stays as it was
    if (firstCommitStudy()) {
      run.put("analogy_estimate", analogy == null
          ? "absent: not a model of this step under the " + rule.setting() + " split"
          : analogyRecord(analogy, r, main));
      run.put("new_projects", newProjects(r, main, withoutNew));
    }
    run.put("target", BusinessDays.target(entries, waitK));
    run.put("population", BusinessDays.populationRule(entries));
    run.put("prediction_intervals", PredictionIntervals.rule(app.issues.AdjustedDuration.marked(entries) ? 0 : 1));
    run.put("population_rows", populationRows(population));
    run.put("matrix_sha256", matrixSha);
    if (splitReferenceSetting != null && !splitReferenceSetting.isBlank()) {
      try {
        run.put("split_reference_matrix_sha256", Sha256.of(Path.of(splitReferenceSetting)));
      } catch (final IOException e) {
        throw new CommandFailedException(List.of("could not hash split reference: " + e));
      }
    }
    run.put("elastic_net_parameters_sha256", elasticNetSha);
    run.put("predictions_sha256", predictionsSha);
    run.put("diagnostics_sha256", diagnosticsSha);
    run.put("relevance_sha256", relevanceSha);
    try {
      run.put("uncertainty_sha256", Sha256.of(reports.resolve(UNCERTAINTY)));
      run.put("prediction_percentiles_sha256", Sha256.of(reports.resolve(PERCENTILES)));
      run.put("uncertainty_report_sha256", Sha256.of(reports.resolve(UNCERTAINTY_REPORT)));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not hash uncertainty outputs: " + e));
    }
    run.put("estimation_uncertainty_rule", EstimationUncertainty.RULE);
    run.put("estimation_uncertainty_intervals", EstimationUncertainty.INTERVAL_RULE);
    run.put("prediction_percentiles", EstimationUncertainty.PERCENTILE_RULE);
    run.put("spsp_context", UncertaintyTables.context());
    run.put("relevance", RELEVANCE_RULE);
    run.put("forest_grid", IssuesSimilarityElasticNetCommand.forestGrid(chosen.trees()));
    if (firstCommitStudy()) {
      run.put("forest_trees", chosen.trees());
    }
    run.put("forest_permutation_importance", "not read: it would read the outer test's labels");
    run.put("candidates", candidates(choices));
    run.put("tail_cut", tailCut(r.fences()));
    run.put("error_bands", BusinessDays.BANDS);
    run.put("error_band_tops_business_days",
        List.of(r.bandTops()[0], r.bandTops()[1], r.bandTops()[2]));
    run.put("fit_rows", r.fitRows());
    run.put("scored_rows", r.scoredRows());
    run.put("usual_flow_scored_rows", main.scoredRows());
    run.put(firstCommitStudy() ? "scored_rows_with_java_code"
        : "scored_rows_with_implementation_commit", r.withCommitRows());
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
        store.record(run, rows));
  }

  /**
   * A parameters file's choices, as the test and the inner report read them.
   *
   * @param elasticNetSha the SHA-256 of the bytes parsed
   * @param matrixSha the SHA-256 of the matrix, the one the choices were made on
   * @param cut the tail cut the selection chose (ticket 23): its k, fences and grid
   * @param population the population the parameters file was written for, by its header
   * @param rule the split rule the parameters file was written under, by its header: the random
   *     one when it names none
   * @param analogy the analogy estimate's frozen choice; null when the file has none
   * @param givenTrees every forest's count of trees, as the file gives it; empty when it gives
   *     none
   */
  record Chosen(String elasticNetSha, String matrixSha,
      Map<String, ElasticNetSelection.Choice> choices, Map<String, Forest.Choice> forests,
      TailCut.Choice cut, app.issues.Universe.Population population, SplitRule rule,
      AnalogyEstimate.Frozen analogy, java.util.OptionalInt givenTrees) {

    /** Every forest's count of trees: the file's, or {@link Forest#TREES} when it gives none. */
    int trees() {
      return givenTrees.orElse(Forest.TREES);
    }

    /** What the file freezes beyond the fourteen's choices, as the scoring reads it. */
    SimilarityScoring.Design design() {
      return new SimilarityScoring.Design(analogy, trees());
    }
  }

  /**
   * The choices of {@code elasticNet}, refused -- an {@link IllegalArgumentException} with the
   * reason -- unless the file exists and is the Elastic Net step's own for these models, with a
   * forest for each and the tail cut's k, fences and grid ({@link ElasticNetSelection#tailCut}),
   * and its choices were made on {@code matrix}, by SHA-256. Nothing of the matrix but its bytes'
   * hash is read.
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
          + "or one written in an earlier format or for other models");
    }
    final List<ElasticNetSelection.Parameter> enParameters = ElasticNetSelection.read(
        elasticNetLines);
    final Map<String, ElasticNetSelection.Choice> choices =
        ElasticNetSelection.choices(enParameters);
    final Map<String, Forest.Choice> forests = ElasticNetSelection.forests(enParameters);
    final TailCut.Choice cut = ElasticNetSelection.tailCut(enParameters);
    final AnalogyEstimate.Frozen analogy = ElasticNetSelection.analogy(enParameters);
    final java.util.OptionalInt trees = ElasticNetSelection.trees(enParameters);
    if (!Set.copyOf(ElasticNetSelection.MODELS).equals(choices.keySet())) {
      throw new IllegalArgumentException(elasticNet + " holds alpha and lambda for "
          + choices.keySet() + "; the models are " + ElasticNetSelection.MODELS);
    }
    if (!Set.copyOf(ElasticNetSelection.MODELS).equals(forests.keySet())) {
      throw new IllegalArgumentException(elasticNet + " holds the forest parameters for "
          + forests.keySet() + "; every model has a forest, " + ElasticNetSelection.MODELS
          + "; run issues-in-progress-similarity-elastic-net again");
    }
    final String matrixSha = Sha256.of(matrix);
    if (!matrixSha.equals(ElasticNetSelection.matrixSha(enParameters))) {
      throw new IllegalArgumentException("the choices in " + elasticNet + " were chosen on "
          + "another matrix (sha256 " + ElasticNetSelection.matrixSha(enParameters) + "), not "
          + matrix + " (sha256 " + matrixSha + ")");
    }
    return new Chosen(sha256(elasticNetBytes), matrixSha, choices, forests, cut,
        ElasticNetSelection.headerPopulation(elasticNetLines.get(0)),
        ElasticNetSelection.headerRule(elasticNetLines.get(0)), analogy, trees);
  }

  /**
   * Why the parameters file is not of the design the run declares; null when it is. Its header's
   * split rule must be the run's; the analogy estimate is a declared model of the temporal split
   * alone, frozen there and absent elsewhere; a file of the temporal split must give the forests'
   * tree count.
   */
  static String otherDesign(final Chosen chosen, final SplitRule rule, final Path elasticNet) {
    if (chosen.rule() != rule) {
      return elasticNet + " was written under the split rule " + chosen.rule().setting()
          + "; this run declares " + rule.setting() + " (" + SplitRule.SETTING + ")";
    }
    if (rule.temporal() && chosen.analogy() == null) {
      return elasticNet + " freezes no choice of the analogy estimate, the fifteenth "
          + "model of the temporal split; the selection of the temporal split must write it "
          + "first";
    }
    if (rule.temporal() && chosen.givenTrees().isEmpty()) {
      return elasticNet + " gives no tree count for the forests (the line "
          + ElasticNetSelection.FOREST_STEP + " " + ElasticNetSelection.EVERY_MODEL
          + " trees <n>); a file of the temporal split must give it, and only a file of the "
          + "random split is read at " + Forest.TREES + " trees without it";
    }
    if (!rule.temporal() && chosen.analogy() != null) {
      return elasticNet + " freezes a choice of the analogy estimate, a declared model of "
          + "the first-commit study's temporal split alone; under the " + rule.setting()
          + " split it is exploratory and never scored here";
    }
    return null;
  }

  /**
   * A reading's name in the run record's rows: its tail cut reading, and whether the issues of
   * new projects were left out.
   */
  static String readingName(final SimilarityScoring.Report reading) {
    return reading.reading().name().toLowerCase(java.util.Locale.ROOT)
        + (reading.withoutNewProjects() ? "_without_new_projects" : "");
  }

  /** A reading in words, as printed. */
  private static String readingDescription(final SimilarityScoring.Report reading) {
    return reading.reading().description() + (reading.withoutNewProjects()
        ? String.format(", without the %,d issues of the %d new projects %s",
            reading.newProjectRows(), reading.newProjects().size(), reading.newProjects())
        : "");
  }

  /** The global median a report's references learned: the analogy estimate's fallback. */
  private static double globalMedian(final SimilarityScoring.Report r) {
    return r.models().stream().filter(m -> m.name().equals(SimilarityScoring.GLOBAL)).findFirst()
        .orElseThrow().predicted()[0];
  }

  /** The analogy estimate as the run record states it. */
  private static Map<String, Object> analogyRecord(final AnalogyEstimate.Frozen analogy,
      final SimilarityScoring.Report all, final SimilarityScoring.Report usual) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", ANALOGY_RULE);
    out.put("column", analogy.column() + Augmentation.Variant.OUTER.suffix());
    out.put("fallback_business_days", globalMedian(all));
    out.put("fallbacks", List.of(all.analogyFallbacks(), all.scoredRows()));
    out.put("usual_flow_fallbacks", List.of(usual.analogyFallbacks(), usual.scoredRows()));
    return out;
  }

  /** The new projects as the run record states them. */
  private static Map<String, Object> newProjects(final SimilarityScoring.Report all,
      final SimilarityScoring.Report usual, final SimilarityScoring.Readings withoutNew) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", NEW_PROJECTS);
    out.put("projects", List.copyOf(all.newProjects()));
    out.put("issues", all.newProjectRows());
    out.put("usual_flow_issues", usual.newProjectRows());
    out.put("readings_without_them", withoutNew == null
        ? "none apart: with no new project each reading without them is the reading itself"
        : List.of(readingName(withoutNew.main()), readingName(withoutNew.complementary())));
    return out;
  }

  /** The scored issues the side reading keeps, in words. */
  private String sidePhrase() {
    return firstCommitStudy() ? "with Java code" : "with at least one implementation commit";
  }

  /** What the run record states about the test it scores. */
  static Map<String, Object> statements() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("outer_test_draw", EXAMINED);
    out.put("selection_effort", EFFORT);
    out.put("ordering_intervals", ORDERING);
    out.put("prediction_intervals", PredictionIntervals.RULE);
    out.put("prediction_interval_factor", PredictionIntervals.FACTOR);
    out.put("prediction_interval_outer_calibration", PredictionIntervals.OUTER);
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
   * Per forest, each design column's permutation importance on the scored issues: the mean rise
   * in mean absolute error, in business days, over the repeats, then each repeat's, as Java
   * prints a double. The inner report's only: the outer test reads none.
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

  /** The run record's Jaccard n, embedding n and cluster k, per model with the medians. */
  static Map<String, Object> candidates(final Map<String, ElasticNetSelection.Choice> choices) {
    final Map<String, Object> out = new LinkedHashMap<>();
    ElasticNetSelection.combinations(choices).forEach((name, c) -> out.put(name, combination(c)));
    return out;
  }

  /** The printed line naming the parameters file, with the count of Elastic Net models. */
  static String choicesLine(final Path elasticNet, final String sha) {
    return String.format("  Elastic Net choices %s  sha256 %s (%d models)", elasticNet, sha,
        ElasticNetSelection.MODELS.size());
  }

  /** What the relevance is, in words, for the run record and the inner report. */
  static final String RELEVANCE_RULE = "per Elastic Net model, each standardised column's "
      + "coefficient in the refit, with its 95% interval from resampling whole projects of the "
      + "fitting side (" + ProjectBootstrap.DRAWS + " draws, seed " + ProjectBootstrap.SEED
      + ", a project drawn twice counted twice): on each draw the model is refitted as the refit "
      + "was made, the design matrix learned on the drawn rows, the same alpha and the lambda "
      + "times the drawn rows over the fitting rows; a column constant on a draw's rows counts "
      + "as zero there; the coefficients are on standardised columns, so they compare with each "
      + "other within a model";

  /**
   * Per model and standardised column, its coefficient, whether the estimator kept it and its
   * interval: the relevance file of both steps.
   */
  static List<String> relevance(final List<SimilarityScoring.Relevance> relevance) {
    final List<String> out = new ArrayList<>();
    out.add("model\tfit_rows\tprojects\tcolumn\tcoefficient\tselected\tci_low\tci_high");
    for (final SimilarityScoring.Relevance r : relevance) {
      for (final SimilarityScoring.Coefficient c : r.coefficients()) {
        out.add(String.join("\t", r.model(), Integer.toString(r.fitRows()),
            Integer.toString(r.projects()), c.column(), Double.toString(c.coefficient()),
            Boolean.toString(c.selected()), Double.toString(c.low()),
            Double.toString(c.high())));
      }
    }
    return out;
  }

  /**
   * The relevance, printed per model and column, each coefficient's document added to {@code
   * rows}.
   */
  static void printRelevance(final List<SimilarityScoring.Relevance> relevance,
      final List<Map<String, Object>> rows) {
    System.out.printf("%n  RELEVANCE: %s%n", RELEVANCE_RULE);
    for (final SimilarityScoring.Relevance r : relevance) {
      System.out.printf("  %s (%,d fitting rows, %d projects)%n", r.model(), r.fitRows(),
          r.projects());
      for (final SimilarityScoring.Coefficient c : r.coefficients()) {
        System.out.printf(java.util.Locale.ROOT, "    %-40s %+9.4f [%+9.4f, %+9.4f]%s%n",
            c.column(), c.coefficient(), c.low(), c.high(), c.selected() ? "" : "  not kept");
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "coefficient");
        row.put("model", r.model());
        row.put("column", c.column());
        row.put("coefficient", c.coefficient());
        row.put("selected", c.selected());
        row.put("ci_projects", List.of(c.low(), c.high()));
        rows.add(row);
      }
    }
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
   * @param observed the scored issues' truth, for the MMRE and Pred(25); null to record neither
   */
  private static Map<String, Object> modelRow(final SimilarityScoring.Scored m,
      final ElasticNetSelection.Choice choice, final double[] observed) {
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
    if (observed != null) {
      row.put("mmre", AnalogyEstimate.mmre(m.predicted(), observed));
      row.put("pred25", AnalogyEstimate.pred25(m.predicted(), observed));
    }
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

  /**
   * The tail cut a scoring was fitted under, as a document: its rule, k, both fences, taken on the
   * fitting side of the scope, and the rows each one leaves.
   */
  static Map<String, Object> tailCut(final SimilarityScoring.Fences f) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", TailCut.RULE);
    out.put("quantiles", TailCut.QUANTILES);
    out.put("k", TailCut.label(f.k()));
    out.put("fence_business_days", f.fence());
    out.put("fit_rows_before_cut", f.uncutFitRows());
    out.put("usual_flow_k", TailCut.label(TailCut.USUAL_FLOW_K));
    out.put("usual_flow_fence_business_days", f.usualFence());
    out.put("usual_flow_scored_rows", f.usualRows());
    out.put("all_scored_rows", f.allRows());
    return out;
  }

  /** One reading's tables, printed, each line's document added to {@code rows}. */
  /**
   * @param relative whether each model's row records its MMRE and Pred(25): in the first-commit
   *     study, whose literature on estimation by analogy reports them; the In Progress study's rows
   *     stay as they were
   */
  private static void print(final SimilarityScoring.Report r, final List<Map<String, Object>> rows,
      final Map<String, ElasticNetSelection.Choice> choices,
      final String side, final boolean relative) {
    System.out.printf("  %-24s %9s %12s %9s %9s %9s %9s  (w/o slow10%%: the rows at or under "
        + "the 90th percentile of the truth, %,d of %,d; ties at it all kept; MMRE and Pred(25) "
        + "on |observed - estimate| / observed)%n", "model", "MAE bd", "w/o slow10%", "Spearman",
        "pairwise", "MMRE", "Pred(25)", r.models().get(0).errors().trimmedN(), r.scoredRows());
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score sc = m.score();
      System.out.printf("  %-24s %9.3f %12.3f %9.3f %9.3f %9.3f %9.3f%n", m.name(), sc.mae(),
          m.errors().trimmedMae(), sc.spearman(), sc.pairwise(),
          AnalogyEstimate.mmre(m.predicted(), r.observed()),
          AnalogyEstimate.pred25(m.predicted(), r.observed()));
      rows.add(modelRow(m, choices.get(m.name()), relative ? r.observed() : null));
      if (m.forest() != null) {
        System.out.printf("    %-22s mtry %d (share %.3f), leaf %d%n", "", m.forest().mtry(),
            m.forest().share(), m.forest().nodeSize());
      }
    }
    System.out.printf("%n  error by band of the true business days (at the quartiles of the "
        + "outer training rows fitted, %s), MAE in business days%n",
        BusinessDays.bandLimits(r.bandTops()));
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
    System.out.printf("%n  side reading, the retrospective scenario on the %,d of %,d scored "
        + "issues %s, intervals resampling their projects%n",
        r.withCommitRows(), r.scoredRows(), side);
    for (final ProjectBootstrap.Gap g : r.withCommits()) {
      System.out.printf("  %-24s vs %-24s %10.3f [%10.3f, %10.3f]%s%n", g.a(), g.b(),
          g.gap(), g.low(), g.high(), g.tied() ? "  tie" : "");
      final Map<String, Object> row = comparisonRow(g);
      row.put("kind", "side_reading_with_commits");
      row.put("n", r.withCommitRows());
      rows.add(row);
    }
  }

  /**
   * One reading's prediction intervals, printed, each model, level and band of the observed
   * duration's document, overall too, added to {@code rows}: the coverage with its interval, the
   * median width, the width over the global median's and the MAE.
   *
   * @param tops the bands' limits, the error bands' of the outer training rows fitted
   */
  static void printIntervals(final List<PredictionIntervals.Read> reads,
      final double[] tops, final List<Map<String, Object>> rows) {
    System.out.printf("%n  prediction intervals, the inner calibration applied (an approximation):"
        + " coverage [95%% interval resampling whole projects], median width in business days, "
        + "width over the global median's, MAE in business days; overall and per band of the "
        + "observed duration (%s), read after the fact%n", BusinessDays.bandLimits(tops));
    final String[] labels = BusinessDays.bandLabels(tops);
    for (final PredictionIntervals.Read x : reads) {
      final StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT,
          "  %-24s %3.0f%%", x.model(), 100 * x.level()));
      final List<PredictionIntervals.Group> groups = new ArrayList<>();
      groups.add(x.overall());
      groups.addAll(x.bands());
      final List<String> groupLabels = new ArrayList<>(); groupLabels.add("all");
      groupLabels.addAll(List.of(labels));
      for (final String stratum : List.of("zero", "nonzero")) {
        if (x.strata().containsKey(stratum)) { groups.add(x.strata().get(stratum)); groupLabels.add(stratum); }
      }
      for (int q = 0; q < groups.size(); q++) {
        final PredictionIntervals.Group g = groups.get(q);
        final String band = groupLabels.get(q);
        line.append("  ").append(band).append(g.coverage() == null
            ? " (n 0)" : String.format(java.util.Locale.ROOT, " %.3f [%.3f, %.3f] w %s x%s "
                + "mae %.3f (n %d)", g.coverage().value(), g.coverage().low(),
                g.coverage().high(), infinite(g.medianWidth()), infinite(g.relativeWidth()),
                g.mae(), g.n()));
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "prediction_interval");
        row.put("model", x.model());
        row.put("level", x.level());
        row.put("observed_band", band);
        row.put("n", g.n());
        if (g.coverage() != null) {
          row.put("coverage", g.coverage().value());
          row.put("coverage_ci_projects", List.of(g.coverage().low(), g.coverage().high()));
          row.put("median_width_business_days", g.medianWidth());
          row.put("width_over_global", g.relativeWidth());
          row.put("mae_business_days", g.mae());
        }
        rows.add(row);
      }
      System.out.println(line);
    }
  }

  /** A width or a ratio as printed: {@code ∞} when infinite. */
  private static String infinite(final double v) {
    return Double.isInfinite(v) ? "∞" : String.format(java.util.Locale.ROOT, "%.2f", v);
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
