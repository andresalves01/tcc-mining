package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.IssueUnit;
import app.issues.PredictionIntervals;
import app.issues.ProjectSplit;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.Forest;
import app.issues.model.ProjectBootstrap;
import app.model.Parallel;
import app.study.model.Breakdown;
import app.study.model.Score;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The inner report of the description-similarity chain (ticket 16): the fourteen models the
 * outer test scores -- the global and project medians, the six Elastic Nets of {@link
 * ElasticNetSelection#MODELS} and their forests -- refitted on the inner fitting rows at the
 * choices of the parameters file and scored on the inner validation, by the study's own scoring
 * ({@link SimilarityScoring}, {@link SimilarityScoring.Scope#INNER}), in whole business days on
 * the issues of at least one ({@link BusinessDays}).
 *
 * <p><b>Tail cut and two readings</b> (ticket 23; {@link TailCut}). The models are fitted on the
 * inner fitting rows at or below the fence of the k the parameters file records, the fences taken
 * on the inner fitting set; a fence that is not the one the selection recorded refuses. Every
 * table -- models, bands, paired comparisons, ordering intervals -- is read twice: on the
 * usual-flow validation issues, those at or below the k = 3 fence, the main reading, which item
 * 49's decision on ticket 19's columns reads; and on every validation issue, the complementary
 * reading, marked as such. The report's header states the chosen k, both fences, each k's error
 * and how many issues each reading holds; each file's first column names the reading. The
 * predictions file holds every validation issue, with whether it is of the usual flow; the
 * coefficients and the permutation importance are the main reading's.
 *
 * <p><b>Relevance</b> ({@link SimilarityScoring#relevance}). Per Elastic Net model, each
 * standardised column's coefficient with its 95% interval from resampling whole projects of the
 * inner fitting set ({@link #RELEVANCE}); per forest, the ten columns of the largest permutation
 * importance on the usual-flow validation issues, each with the spread of its repeats. A reading
 * of which attributes carry the models, not a choice of any.
 *
 * <p><b>Prediction intervals</b> (ticket 24; {@link PredictionIntervals}). Calibrated on the
 * usual-flow validation issues of half A, one factor per model and level, checked on half B in
 * each reading, overall and per band of the observed duration (the error bands, read after the
 * fact), the halves by whole projects; nothing of them chooses a model, k or column. The report's
 * header states each model's prediction span.
 *
 * <p><b>Optimistic.</b> The settings were chosen on this same inner validation, so every figure
 * of the report is optimistic; the report says so on top. It is a reading of the development
 * rows, never the test.
 *
 * <p><b>Read-only.</b> Reads MATRIX and ELASTIC_NET_PARAMETERS. Of the matrix, only the outer
 * training rows are kept, before anything else of them is read: every other row is left behind on
 * its split field alone ({@link RowCache#read(Path, Population, String)}), so the outer test is
 * never parsed beyond it; the outer test is not counted either. Refuses, writing nothing, unless
 * the parameters file is the selection step's own for these models with a forest for each, its
 * choices were made on this very matrix (by SHA-256) -- where the selection step checked the
 * split's rule over every row --, every outer training row is inner fitting or inner validation,
 * and the matrix has the day boundary and every column the models read. Writes files to
 * REPORT_DIR only and nothing to the database: no run is recorded, the context's ports are never
 * reached.
 *
 * <p><b>Files.</b> The report in words and tables ({@link #REPORT}); per inner validation issue its
 * prediction by every model and whether it has an implementation commit ({@link #PREDICTIONS});
 * per model the error, the gains over the global and the project median, Spearman, pairwise
 * ordering and the error's quantiles ({@link #MODELS}); the error by band of the true business
 * days, the bands at the quartiles of the cut fitting rows ({@link #BANDS}, ticket 24); the
 * prediction intervals checked on half B ({@link #INTERVALS}) and the calibration recomputed on
 * both halves for later use ({@link #CALIBRATION}); the paired comparisons of the test ({@link SimilarityScoring#pairs}), which hold every model
 * against the global and the project median, the own start model against Al-Zubaidi and Kikas at
 * the start, the main reading of the retrospective model against the own start model and against
 * Kikas's, and the ceiling against the main reading and against Kikas's, Elastic Net and forest,
 * and the side reading of the main reading's pair with the own start model on the issues with and
 * without an implementation commit ({@link #COMPARISONS}); the ordering intervals, a declared
 * measure ({@link #ORDERING}); the Elastic Nets' coefficients as the test writes them ({@link
 * #DIAGNOSTICS}) and with their intervals ({@link #RELEVANCE}); the forests' permutation
 * importance on the inner validation ({@link #IMPORTANCE}).
 */
public final class IssuesSimilarityInnerReportCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-inner-report";

  public static final String REPORT = "similarity-inner-report.md";
  public static final String PREDICTIONS = "similarity-inner-predictions.tsv";
  public static final String MODELS = "similarity-inner-models.tsv";
  public static final String BANDS = "similarity-inner-bands.tsv";
  public static final String COMPARISONS = "similarity-inner-comparisons.tsv";
  public static final String ORDERING = "similarity-inner-ordering.tsv";
  public static final String DIAGNOSTICS = "similarity-inner-diagnostics.tsv";
  public static final String IMPORTANCE = "similarity-inner-forest-importance.tsv";
  public static final String INTERVALS = "similarity-inner-intervals.tsv";
  public static final String CALIBRATION = "similarity-inner-calibration.tsv";
  public static final String RELEVANCE = "similarity-inner-relevance.tsv";

  private static final List<String> OUTPUTS = List.of(REPORT, PREDICTIONS, MODELS, BANDS,
      COMPARISONS, ORDERING, DIAGNOSTICS, IMPORTANCE, INTERVALS, CALIBRATION, RELEVANCE);

  /** How many columns of each forest the relevance section names, the largest importance first. */
  static final int TOP_IMPORTANCE = 10;

  /** What the report states first. */
  static final String OPTIMISTIC = "every figure here is optimistic: the settings (alpha, "
      + "lambda, the similarity medians' n and k, each forest's column share and leaf) were chosen "
      + "on this same inner validation; this is a reading of the development rows, not the test";

  private static final Population P = Population.IN_PROGRESS;

  private final Function<String, String> settings;

  public IssuesSimilarityInnerReportCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /** @param settings the settings by name; the tests hand their own */
  IssuesSimilarityInnerReportCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /** Never reaches {@code context}: nothing is read from or written to the database. */
  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = IssuesSimilarityTestCommand.path(settings, "MATRIX");
    final Path elasticNet =
        IssuesSimilarityTestCommand.path(settings, "ELASTIC_NET_PARAMETERS");
    final Path reports = IssuesSimilarityTestCommand.path(settings, "REPORT_DIR");
    for (final String name : OUTPUTS) {
      for (final Path in : List.of(matrix, elasticNet)) {
        if (IssuesSimilarityTestCommand.same(reports.resolve(name), in)) {
          throw refused(reports.resolve(name) + " is an input of this step and is never written");
        }
      }
    }

    final IssuesSimilarityTestCommand.Chosen chosen;
    final List<RowCache.Entry> training;
    final List<String> columns;
    try {
      try {
        chosen = IssuesSimilarityTestCommand.chosen(elasticNet, matrix);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      // the outer training rows only: every other row is left behind on its split field
      training = RowCache.read(matrix, P, "TRAIN");
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }

    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY -- INNER REPORT%n");
    System.out.printf("  NOTE: %s.%n", OPTIMISTIC);
    System.out.printf("  matrix read %s  sha256 %s (%,d outer training rows; the other rows are "
        + "left behind on their split field)%n", matrix, chosen.matrixSha(), training.size());
    System.out.printf("  Elastic Net choices %s  sha256 %s%n", elasticNet, chosen.elasticNetSha());
    final RegistrationInProgress.Sizes s = ElasticNetSelection.sizes(training);
    System.out.printf("  outer training %,d  inner fitting %,d  inner validation %,d%n", s.train(),
        s.innerFit(), s.innerSelection());
    // the rule is checked whole by the selection step, which read every row of this very matrix;
    // here, with the outer test left behind, only what the outer training shows of it
    if (s.train() == 0 || s.innerFit() + s.innerSelection() != s.train()) {
      throw refused(String.format("%,d of %,d outer training rows are neither inner fitting nor "
          + "inner validation; the random split sets none aside (a matrix of the temporal cut "
          + "does)", s.train() - s.innerFit() - s.innerSelection(), s.train()));
    }
    final List<String> missing;
    try {
      missing = IssuesSimilarityElasticNetCommand.missing(ElasticNetSelection.cells(
          ElasticNetSelection.combinations(chosen.choices()), Augmentation.Variant.INNER),
          columns);
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
    final SimilarityScoring.Readings readings;
    final List<SimilarityScoring.Relevance> relevance;
    try {
      population = BusinessDays.population(training, Augmentation.Variant.INNER);
      // the same inner fitting set as the selection's, by the matrix's hash: the same fences,
      // checked before any model is fitted
      final List<IssueUnit> fitting = population.entries().stream()
          .filter(e -> "TRAIN".equals(e.inner(P))).map(RowCache.Entry::unit).toList();
      final double fence = TailCut.fence(fitting, chosen.cut().k());
      final double usualFence = TailCut.fence(fitting, TailCut.USUAL_FLOW_K);
      if (Double.compare(fence, chosen.cut().fence()) != 0
          || Double.compare(usualFence, chosen.cut().usualFence()) != 0) {
        throw refused(String.format(Locale.ROOT, "the fences on the inner fitting set, %s and %s "
            + "business days, are not those the selection recorded, %s and %s", fence,
            usualFence, chosen.cut().fence(), chosen.cut().usualFence()));
      }
      readings = SimilarityScoring.readings(population.entries(), chosen.choices(),
          chosen.forests(), chosen.cut().k(), SimilarityScoring.Scope.INNER);
      relevance = SimilarityScoring.relevance(population.entries(), chosen.choices(),
          chosen.cut().k(), SimilarityScoring.Scope.INNER);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }

    final SimilarityScoring.Report main = readings.main();
    final PredictionIntervals.Inner intervals = PredictionIntervals.inner(
        PredictionIntervals.Sample.of(main),
        PredictionIntervals.Sample.of(readings.complementary()),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
    final Map<String, List<String>> files = new LinkedHashMap<>();
    files.put(REPORT, report(readings, intervals, relevance, population, chosen, matrix,
        elasticNet));
    files.put(PREDICTIONS, predictions(readings));
    files.put(MODELS, models(readings));
    files.put(BANDS, bands(readings));
    files.put(COMPARISONS, comparisons(readings));
    files.put(ORDERING, ordering(readings));
    files.put(DIAGNOSTICS, IssuesSimilarityTestCommand.diagnostics(main));
    files.put(IMPORTANCE, IssuesSimilarityTestCommand.importance(main));
    files.put(INTERVALS, intervals(intervals, main.bandTops()));
    files.put(CALIBRATION, calibration(intervals));
    files.put(RELEVANCE, IssuesSimilarityTestCommand.relevance(relevance));
    files.get(REPORT).forEach(line -> System.out.println("  " + line));
    try {
      Files.createDirectories(reports);
      for (final Map.Entry<String, List<String>> f : files.entrySet()) {
        IssuesSimilarityTestCommand.write(reports.resolve(f.getKey()), f.getValue());
      }
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + reports + ": " + e));
    }
    System.out.printf("%n  written to %s: %s; nothing recorded in the database%n", reports,
        String.join(", ", files.keySet()));
  }

  /** Both readings, the main one first. */
  private static List<SimilarityScoring.Report> both(final SimilarityScoring.Readings r) {
    return List.of(r.main(), r.complementary());
  }

  /** A reading's name in the files' first column: {@code usual_flow} or {@code all}. */
  static String name(final SimilarityScoring.Report r) {
    return r.reading().name().toLowerCase(Locale.ROOT);
  }

  /**
   * The predictions file of the test, of every inner validation issue, with whether it is of the
   * usual flow and whether it has an implementation commit.
   */
  static List<String> predictions(final SimilarityScoring.Readings readings) {
    final SimilarityScoring.Report r = readings.complementary();
    final boolean[] usual = TailCut.within(r.observed(), r.fences().usualFence());
    final List<String> lines = SimilarityScoring.predictions(r);
    final List<String> out = new ArrayList<>(lines.size());
    for (int k = 0; k < lines.size(); k++) {
      final String[] f = lines.get(k).split("\t", 5);
      out.add(String.join("\t", f[0], f[1], f[2], f[3],
          k == 0 ? "usual_flow" : Boolean.toString(usual[k - 1]),
          k == 0 ? "has_implementation_commit" : Boolean.toString(r.implementationCommit()[k - 1]),
          f[4]));
    }
    return out;
  }

  /** Per reading and model: the error, the gains, the ordering and the error's distribution. */
  static List<String> models(final SimilarityScoring.Readings readings) {
    final List<String> out = new ArrayList<>();
    out.add("reading\tmodel\tkind\tn\tmae_bd\tgain_over_global_percent\tgain_over_project_percent"
        + "\tspearman\tpairwise\tabs_error_p50_bd\tabs_error_p75_bd\tabs_error_p90_bd"
        + "\tabs_error_p99_bd\tmae_without_slowest_tenth_bd\trows_without_slowest_tenth");
    for (final SimilarityScoring.Report r : both(readings)) {
      for (final SimilarityScoring.Scored m : r.models()) {
        final Score s = m.score();
        final Breakdown.Errors e = m.errors();
        out.add(String.join("\t", name(r), m.name(), m.kind(), Integer.toString(s.n()),
            d(s.mae()), d(s.gainOverConstantPercent()), d(s.gainPercent()), d(s.spearman()),
            d(s.pairwise()), d(e.p50()), d(e.p75()), d(e.p90()), d(e.p99()), d(e.trimmedMae()),
            Integer.toString(e.trimmedN())));
      }
    }
    return out;
  }

  /** Per reading, model and band of the true business days, the error. */
  static List<String> bands(final SimilarityScoring.Readings readings) {
    final List<String> out = new ArrayList<>();
    out.add("reading\tmodel\tband\tn\tmedian_truth_bd\tmae_bd\tmedian_abs_error_bd");
    for (final SimilarityScoring.Report r : both(readings)) {
      for (final SimilarityScoring.Scored m : r.models()) {
        for (final Breakdown.Band b : m.bands()) {
          out.add(String.join("\t", name(r), m.name(), b.label(), Integer.toString(b.n()),
              d(b.medianTruth()), d(b.mae()), d(b.medianError())));
        }
      }
    }
    return out;
  }

  /**
   * Per reading, the paired comparisons, then the side reading on the issues with and without a
   * commit.
   */
  static List<String> comparisons(final SimilarityScoring.Readings readings) {
    final List<String> out = new ArrayList<>();
    out.add("reading\tissues\trows\ta\tb\tmae_diff_bd\tci_low\tci_high\ttie");
    for (final SimilarityScoring.Report r : both(readings)) {
      gaps(out, name(r), "all scored", r.scoredRows(), r.comparisons());
      gaps(out, name(r), "with implementation commit", r.withCommitRows(), r.withCommits());
      gaps(out, name(r), "without implementation commit", r.scoredRows() - r.withCommitRows(),
          r.withoutCommits());
    }
    return out;
  }

  private static void gaps(final List<String> out, final String reading, final String issues,
      final int rows, final List<ProjectBootstrap.Gap> gaps) {
    for (final ProjectBootstrap.Gap g : gaps) {
      out.add(String.join("\t", reading, issues, Integer.toString(rows), g.a(), g.b(),
          d(g.gap()), d(g.low()), d(g.high()), Boolean.toString(g.tied())));
    }
  }

  /** Per reading, the ordering intervals, a declared measure. */
  static List<String> ordering(final SimilarityScoring.Readings readings) {
    final List<String> out = new ArrayList<>();
    out.add("reading\ta\tb\tspearman_diff\tspearman_ci_low\tspearman_ci_high\tspearman_tie"
        + "\tpairwise_diff\tpairwise_ci_low\tpairwise_ci_high\tpairwise_tie");
    for (final SimilarityScoring.Report r : both(readings)) {
      for (final ProjectBootstrap.Ordering o : r.orderings()) {
        out.add(String.join("\t", name(r), o.a(), o.b(), d(o.spearman().gap()),
            d(o.spearman().low()), d(o.spearman().high()), Boolean.toString(o.spearman().tied()),
            d(o.pairwise().gap()), d(o.pairwise().low()), d(o.pairwise().high()),
            Boolean.toString(o.pairwise().tied())));
      }
    }
    return out;
  }

  /**
   * The report in words and tables: the optimism stated first, then the tail cut, then every
   * table of the main reading, the usual flow, then the same of the complementary one, every
   * validation issue.
   */
  static List<String> report(final SimilarityScoring.Readings readings,
      final PredictionIntervals.Inner intervals,
      final List<SimilarityScoring.Relevance> relevance, final BusinessDays.Population population,
      final IssuesSimilarityTestCommand.Chosen chosen, final Path matrix,
      final Path elasticNet) {
    final SimilarityScoring.Report r = readings.complementary();
    final SimilarityScoring.Fences fences = r.fences();
    final List<String> md = new ArrayList<>();
    md.add("# Description-similarity chain: inner report");
    md.add("");
    md.add("**NOTE: " + OPTIMISTIC + ".**");
    md.add("");
    md.add("- matrix: " + matrix + " (sha256 " + chosen.matrixSha() + "); outer training rows "
        + "only, the others left behind on their split field");
    md.add("- choices: " + elasticNet + " (sha256 " + chosen.elasticNetSha() + ")");
    md.add("- target: " + BusinessDays.TARGET + "; " + BusinessDays.HOLIDAYS);
    md.add("- silent days: T = " + BusinessDays.days(population.threshold()) + " business days, "
        + "from the gaps of the inner fitting set; days left out of the active business days: "
        + population.leftOut());
    md.add("- split: " + ProjectSplit.RULE);
    md.add("- population: " + BusinessDays.POPULATION + "; inner fitting "
        + population.kept().innerFit() + " of " + population.all().innerFit()
        + ", inner validation " + population.kept().innerSelection() + " of "
        + population.all().innerSelection());
    md.add("- tail cut: " + TailCut.RULE + "; " + TailCut.QUANTILES);
    md.add(f("- chosen k: %s, fence %s business days on the inner fitting set, which leaves %d "
        + "of its %d issues to fit; usual flow: k %s, fence %s business days", TailCut.label(
            fences.k()), fences.fence(), r.fitRows(), fences.uncutFitRows(),
        TailCut.label(TailCut.USUAL_FLOW_K), fences.usualFence()));
    md.add(f("- readings: main, %s: %d inner validation issues; complementary, %s: %d",
        TailCut.Reading.USUAL_FLOW.description(), readings.main().scoredRows(),
        TailCut.Reading.ALL.description(), r.scoredRows()));
    md.add("- " + ElasticNetSelection.TAIL_CUT_MODEL);
    md.add("- each k on " + ElasticNetSelection.BEFORE_PRESSURE + ", its error on the usual-flow "
        + "validation issues: "
        + String.join("; ", chosen.cut().grid().stream().map(t -> f("k %s, fence %s, %d "
            + "fitting issues, MAE %.3f%s", TailCut.label(t.k()), t.fence(), t.fitRows(),
            t.mae(), t.k() == fences.k() ? " (chosen)" : "")).toList()));
    md.add("- fitted on " + r.fitRows() + " inner fitting issues at the chosen alpha and lambda "
        + "(not scaled); scored on " + r.scoredRows() + " inner validation issues, "
        + r.withCommitRows() + " of them with an implementation commit");
    md.add("- validation projects with no fitting issue (the project median is the global one "
        + "there): " + r.unseenRows() + " issues, " + r.unseenProjects());
    md.add("- " + ElasticNetSelection.RETROSPECTIVE + ": "
        + ElasticNetSelection.RETROSPECTIVE_ROLE);
    md.add("- " + ElasticNetSelection.WITH_DISCUSSION + ": "
        + ElasticNetSelection.WITH_DISCUSSION_ROLE);
    md.add("- " + ElasticNetSelection.KIKAS_RETROSPECTIVE + ": "
        + ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE);
    md.add("- the Elastic Nets' coefficients and the forests' permutation importance, here and in "
        + "the files beside this one, are the main reading's");
    md.add(f("- error bands: %s; here %s business days, of the %d cut inner fitting issues; %s",
        BusinessDays.BANDS, BusinessDays.bandLimits(r.bandTops()), r.fitRows(),
        TailCut.QUANTILES));
    final PredictionIntervals.Halves h = intervals.halves();
    md.add("- prediction intervals: " + PredictionIntervals.RULE + "; "
        + PredictionIntervals.FACTOR);
    md.add(f("- calibration halves of the %d usual-flow validation issues: A, %d projects, %d "
        + "issues, calibrates; B, the other projects, %d issues, is checked; with the tail, in "
        + "the complementary reading, B holds %d validation issues; the projects of A: %s",
        h.aIssues() + h.bIssues(), h.a().size(), h.aIssues(), h.bIssues(),
        checked(intervals, TailCut.Reading.ALL), h.a()));
    md.add("- each model's prediction span on the usual-flow validation issues, business days: "
        + String.join("; ", intervals.calibrated().entrySet().stream().map(e -> f("%s %.2f-%.2f",
            e.getKey(), e.getValue().low(), e.getValue().high())).toList()));
    md.add("- the factors recomputed on A and B together, for later use, are in " + CALIBRATION
        + "; those of A, which the check reads, in " + INTERVALS);
    md.add("");
    section(md, readings.main(), intervals, "Main reading: the usual flow");
    section(md, r, intervals, "Complementary reading: all issues");
    relevance(md, relevance, readings.main());
    md.add("Files beside this one: " + String.join(", ", OUTPUTS.subList(1, OUTPUTS.size()))
        + ".");
    return md;
  }

  /** Every table of one reading, under its own heading. */
  private static void section(final List<String> md, final SimilarityScoring.Report r,
      final PredictionIntervals.Inner intervals, final String title) {
    md.add("## " + title + " (" + r.scoredRows() + " issues)");
    md.add("");
    md.add(r.reading().description() + ".");
    md.add("");
    md.add("### Every model (business days)");
    md.add("");
    md.add("| model | kind | MAE | gain vs global % | gain vs project % | Spearman | pairwise "
        + "| abs error p50 | p75 | p90 | MAE w/o slowest tenth |");
    md.add("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score s = m.score();
      final Breakdown.Errors e = m.errors();
      md.add(f("| %s | %s | %.3f | %+.2f | %+.2f | %.3f | %.3f | %.2f | %.2f | %.2f | %.3f |",
          m.name(), m.kind(), s.mae(), s.gainOverConstantPercent(), s.gainPercent(),
          s.spearman(), s.pairwise(), e.p50(), e.p75(), e.p90(), e.trimmedMae()));
    }
    md.add("");
    md.add(f("### MAE by band of the true business days (at the quartiles of the cut fitting rows, "
        + "%s)", BusinessDays.bandLimits(r.bandTops())));
    md.add("");
    final List<Breakdown.Band> first = r.models().get(0).bands();
    final StringBuilder head = new StringBuilder("| model |");
    final StringBuilder rule = new StringBuilder("|---|");
    for (final Breakdown.Band b : first) {
      head.append(f(" %s (n %d, median %.1f) |", b.label(), b.n(), b.medianTruth()));
      rule.append("---:|");
    }
    md.add(head.toString());
    md.add(rule.toString());
    for (final SimilarityScoring.Scored m : r.models()) {
      final StringBuilder line = new StringBuilder("| " + m.name() + " |");
      m.bands().forEach(b -> line.append(f(" %.3f |", b.mae())));
      md.add(line.toString());
    }
    md.add("");
    md.add("### Paired comparisons: MAE(a) - MAE(b), business days, 95% interval resampling whole "
        + "projects (" + ProjectBootstrap.DRAWS + " draws, seed " + ProjectBootstrap.SEED + ")");
    md.add("");
    table(md, r.comparisons());
    md.add("### Side reading: the main reading of the retrospective model against the own start "
        + "model on the " + r.withCommitRows() + " issues with an implementation commit");
    md.add("");
    table(md, r.withCommits());
    md.add("### For contrast: the same pairs on the " + (r.scoredRows() - r.withCommitRows())
        + " issues without one");
    md.add("");
    table(md, r.withoutCommits());
    md.add("### Ordering intervals (a declared measure): a - b in Spearman and in pairwise "
        + "ordering, the same draws");
    md.add("");
    md.add("| a | b | Spearman a - b [95%] | pairwise a - b [95%] |");
    md.add("|---|---|---|---|");
    for (final ProjectBootstrap.Ordering o : r.orderings()) {
      md.add(f("| %s | %s | %+.3f [%+.3f, %+.3f]%s | %+.3f [%+.3f, %+.3f]%s |", o.a(), o.b(),
          o.spearman().gap(), o.spearman().low(), o.spearman().high(),
          o.spearman().tied() ? " tie" : "", o.pairwise().gap(), o.pairwise().low(),
          o.pairwise().high(), o.pairwise().tied() ? " tie" : ""));
    }
    md.add("");
    intervalTable(md, r, intervals);
  }

  /**
   * The relevance section: per Elastic Net model, every standardised column's coefficient with
   * its interval resampling whole projects; per forest, the {@link #TOP_IMPORTANCE} columns of
   * the largest mean permutation importance on the usual-flow validation issues, each with the
   * smallest and largest of its repeats.
   */
  private static void relevance(final List<String> md,
      final List<SimilarityScoring.Relevance> relevance, final SimilarityScoring.Report main) {
    md.add("## Attribute relevance");
    md.add("");
    md.add("Which attributes carry each model; a reading, nothing here chooses a model or a "
        + "column.");
    md.add("");
    md.add("### Elastic Net: standardised coefficients");
    md.add("");
    md.add(IssuesSimilarityTestCommand.RELEVANCE_RULE + ". The scale is log1p of the business "
        + "days per standard deviation of the column; a column not kept has a weight the "
        + "estimator leaves near zero.");
    md.add("");
    for (final SimilarityScoring.Relevance m : relevance) {
      md.add(f("#### %s (%d fitting issues, %d projects)", m.model(), m.fitRows(),
          m.projects()));
      md.add("");
      md.add("| column | coefficient [95%] | kept |");
      md.add("|---|---|---|");
      for (final SimilarityScoring.Coefficient c : m.coefficients()) {
        md.add(f("| %s | %+.4f [%+.4f, %+.4f] | %s |", c.column(), c.coefficient(), c.low(),
            c.high(), c.selected() ? "yes" : "no"));
      }
      md.add("");
    }
    md.add(f("### Random forest: permutation importance, the %d largest", TOP_IMPORTANCE));
    md.add("");
    md.add(f("The rise in MAE, in business days, when one column's values are shuffled among the "
        + "%d usual-flow validation issues: the mean of %d repeats, and the smallest and largest "
        + "repeat. Every column is in %s.", main.scoredRows(), Forest.PERMUTATION_REPEATS,
        IMPORTANCE));
    md.add("");
    for (final SimilarityScoring.Scored m : main.models()) {
      if (m.forest() == null) {
        continue;
      }
      md.add("#### " + m.name());
      md.add("");
      md.add("| column | mean rise | smallest, largest repeat |");
      md.add("|---|---:|---|");
      m.forest().importance().stream()
          .sorted((a, b) -> Double.compare(b.meanIncrease(), a.meanIncrease()))
          .limit(TOP_IMPORTANCE).forEach(i -> {
            final double[] sorted = i.increases().clone();
            java.util.Arrays.sort(sorted);
            md.add(f("| %s | %+.4f | %+.4f, %+.4f |", i.column(), i.meanIncrease(), sorted[0],
                sorted[sorted.length - 1]));
          });
      md.add("");
    }
  }

  /** How many issues of half B the check reads in {@code reading}. */
  private static int checked(final PredictionIntervals.Inner intervals,
      final TailCut.Reading reading) {
    return intervals.reads().stream().filter(x -> x.reading() == reading).findFirst()
        .orElseThrow().overall().n();
  }

  /**
   * The prediction intervals of one reading, checked on half B: per model and level, overall and
   * per band of the observed duration (the error bands, read after the fact), the coverage with
   * its interval, the median width, the width over the global median's on the same issues, and
   * the MAE.
   */
  private static void intervalTable(final List<String> md, final SimilarityScoring.Report r,
      final PredictionIntervals.Inner intervals) {
    md.add(f("### Prediction intervals, calibrated on half A, checked on the %d issues of half B",
        checked(intervals, r.reading())));
    md.add("");
    md.add("One factor per model and level. Coverage: the share of the issues inside their "
        + "interval, with its 95% interval resampling whole projects (" + ProjectBootstrap.DRAWS
        + " draws, seed " + ProjectBootstrap.SEED + "); width: the median, in business days; "
        + "width / global: over the global median's median width on the same issues; MAE in "
        + "business days. Each figure is read overall and per band of the observed duration, the "
        + "error bands (" + BusinessDays.bandLimits(r.bandTops()) + "), after the fact: the band "
        + "never chooses the factor. An infinite width, ∞, comes from fewer calibration "
        + "issues than the level needs, their count beside it.");
    md.add("");
    md.add("| model | level | observed band | n | coverage [95%] | median width "
        + "| width / global | MAE |");
    md.add("|---|---:|---|---:|---|---:|---:|---:|");
    final String[] labels = BusinessDays.bandLabels(r.bandTops());
    for (final PredictionIntervals.Read x : intervals.reads()) {
      if (x.reading() != r.reading()) {
        continue;
      }
      final String calibrated =
          f(" (%d calibration issues)", intervals.checked().get(x.model()).n());
      md.add(groupRow(x, "all", x.overall(), calibrated));
      for (int b = 0; b < PredictionIntervals.BANDS; b++) {
        md.add(groupRow(x, labels[b], x.bands().get(b), calibrated));
      }
    }
    md.add("");
  }

  /**
   * One row of the interval table; an infinite width is {@code ∞}, with {@code calibrated},
   * the calibration issues its factor came from, beside it.
   */
  private static String groupRow(final PredictionIntervals.Read x, final String band,
      final PredictionIntervals.Group g, final String calibrated) {
    final String coverage = g.coverage() == null ? "-" : f("%.3f [%.3f, %.3f]",
        g.coverage().value(), g.coverage().low(), g.coverage().high());
    final String width = Double.isInfinite(g.medianWidth()) ? "∞" + calibrated
        : number(g.medianWidth());
    return f("| %s | %.0f%% | %s | %d | %s | %s | %s | %s |", x.model(), 100 * x.level(),
        band, g.n(), coverage, width, number(g.relativeWidth()),
        Double.isNaN(g.mae()) ? "-" : f("%.3f", g.mae()));
  }

  /** A width or a ratio as the interval table writes it: {@code ∞}, {@code -} for none. */
  private static String number(final double v) {
    return Double.isInfinite(v) ? "∞" : Double.isNaN(v) ? "-" : f("%.2f", v);
  }

  /**
   * Per reading, model and level, the check on half B, overall and per band of the observed
   * duration: the band's limits, the model's calibration issues and factor on half A, then the
   * figures.
   *
   * @param tops the bands' limits, the error bands', of the inner fitting rows cut
   */
  static List<String> intervals(final PredictionIntervals.Inner intervals, final double[] tops) {
    final List<String> out = new ArrayList<>();
    out.add("reading\tmodel\tlevel\tobserved_band\tobserved_above_bd\tobserved_up_to_bd"
        + "\tcalibration_issues\tfactor\tn\tcoverage\tcoverage_ci_low\tcoverage_ci_high"
        + "\tmedian_width_bd\twidth_over_global\tmae_bd");
    final String[] labels = BusinessDays.bandLabels(tops);
    for (final PredictionIntervals.Read x : intervals.reads()) {
      final PredictionIntervals.Calibration c = intervals.checked().get(x.model());
      final String factor = d(c.factors()[PredictionIntervals.LEVELS.indexOf(x.level())]);
      final String reading = x.reading().name().toLowerCase(Locale.ROOT);
      out.add(String.join("\t", reading, x.model(), d(x.level()), "all", "", "",
          Integer.toString(c.n()), factor, figures(x.overall())));
      for (int b = 0; b < PredictionIntervals.BANDS; b++) {
        out.add(String.join("\t", reading, x.model(), d(x.level()), labels[b],
            b == 0 ? "" : d(tops[b - 1]), b == tops.length ? "" : d(tops[b]),
            Integer.toString(c.n()), factor, figures(x.bands().get(b))));
      }
    }
    return out;
  }

  /**
   * Per model and level, the calibration recomputed on halves A and B together, for every later
   * use: the calibration issues, the factor and the span of the model's predictions.
   */
  static List<String> calibration(final PredictionIntervals.Inner intervals) {
    final List<String> out = new ArrayList<>();
    out.add("model\tlevel\tcalibration_issues\tfactor\tprediction_min_bd\tprediction_max_bd");
    intervals.calibrated().forEach((model, c) -> {
      for (int l = 0; l < PredictionIntervals.LEVELS.size(); l++) {
        out.add(String.join("\t", model, d(PredictionIntervals.LEVELS.get(l)),
            Integer.toString(c.n()), d(c.factors()[l]), d(c.low()), d(c.high())));
      }
    });
    return out;
  }

  private static String figures(final PredictionIntervals.Group g) {
    final boolean none = g.coverage() == null;
    return String.join("\t", Integer.toString(g.n()), none ? "" : d(g.coverage().value()),
        none ? "" : d(g.coverage().low()), none ? "" : d(g.coverage().high()),
        d(g.medianWidth()), d(g.relativeWidth()), d(g.mae()));
  }

  private static void table(final List<String> md, final List<ProjectBootstrap.Gap> gaps) {
    md.add("| a | b | MAE(a) - MAE(b) [95%] |");
    md.add("|---|---|---|");
    for (final ProjectBootstrap.Gap g : gaps) {
      md.add(f("| %s | %s | %+.3f [%+.3f, %+.3f]%s |", g.a(), g.b(), g.gap(), g.low(), g.high(),
          g.tied() ? " tie" : ""));
    }
    md.add("");
  }

  private static String f(final String format, final Object... args) {
    return String.format(Locale.ROOT, format, args);
  }

  private static String d(final double v) {
    return Double.toString(v);
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was scored or written: " + why));
  }
}
