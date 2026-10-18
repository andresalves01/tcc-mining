package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.ProjectSplit;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.ProjectBootstrap;
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
 * The inner report of the description-similarity chain (ticket 16): twenty-one models -- the
 * nineteen of the test, the three medians, eight Elastic Nets and their eight forests, and the
 * ninth Elastic Net, the retrospective model as before ticket 19, with its forest, which the test
 * does not score (ticket 20) -- refitted on the
 * inner fitting rows at the choices of the parameters file and scored on the inner validation,
 * by the study's own scoring ({@link SimilarityScoring}, {@link SimilarityScoring.Scope#INNER}),
 * in whole business days on the issues of at least one ({@link BusinessDays}).
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
 * days, 1, 2-3, 4-10 and 11 or more ({@link #BANDS}); the paired comparisons of the test, which
 * hold every model against the global and the project median and the retrospective model
 * against the one before ticket 19, Elastic Net and forest, and the side reading of the
 * retrospective pairs on the issues with and without an implementation commit ({@link
 * #COMPARISONS}); the ordering intervals, a declared measure ({@link #ORDERING}); the Elastic Nets'
 * coefficients and the forests' permutation importance on the inner validation, as the test writes
 * them ({@link #DIAGNOSTICS}, {@link #IMPORTANCE}).
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

  private static final List<String> OUTPUTS = List.of(REPORT, PREDICTIONS, MODELS, BANDS,
      COMPARISONS, ORDERING, DIAGNOSTICS, IMPORTANCE);

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
    try {
      population = BusinessDays.population(training);
      readings = SimilarityScoring.readings(population.entries(), chosen.choices(),
          chosen.forests(), chosen.cut().k(), SimilarityScoring.Scope.INNER);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    // the same inner fitting set as the selection's, by the matrix's hash: the same fences
    final SimilarityScoring.Fences fences = readings.main().fences();
    if (Double.compare(fences.fence(), chosen.cut().fence()) != 0
        || Double.compare(fences.usualFence(), chosen.cut().usualFence()) != 0) {
      throw refused(String.format(Locale.ROOT, "the fences on the inner fitting set, %s and %s "
          + "business days, are not those the selection recorded, %s and %s", fences.fence(),
          fences.usualFence(), chosen.cut().fence(), chosen.cut().usualFence()));
    }

    final SimilarityScoring.Report main = readings.main();
    final Map<String, List<String>> files = new LinkedHashMap<>();
    files.put(REPORT, report(readings, population, chosen, matrix, elasticNet));
    files.put(PREDICTIONS, predictions(readings));
    files.put(MODELS, models(readings));
    files.put(BANDS, bands(readings));
    files.put(COMPARISONS, comparisons(readings));
    files.put(ORDERING, ordering(readings));
    files.put(DIAGNOSTICS, IssuesSimilarityTestCommand.diagnostics(main));
    files.put(IMPORTANCE, IssuesSimilarityTestCommand.importance(main));
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
      final BusinessDays.Population population, final IssuesSimilarityTestCommand.Chosen chosen,
      final Path matrix, final Path elasticNet) {
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
    md.add("- each k on own_with_similarity, its error on the usual-flow validation issues: "
        + String.join("; ", chosen.cut().grid().stream().map(t -> f("k %s, fence %s, %d "
            + "fitting issues, MAE %.3f%s", TailCut.label(t.k()), t.fence(), t.fitRows(),
            t.mae(), t.k() == fences.k() ? " (chosen)" : "")).toList()));
    md.add("- fitted on " + r.fitRows() + " inner fitting issues at the chosen alpha and lambda "
        + "(not scaled); scored on " + r.scoredRows() + " inner validation issues, "
        + r.withCommitRows() + " of them with an implementation commit");
    md.add("- validation projects with no fitting issue (the project median is the global one "
        + "there): " + r.unseenRows() + " issues, " + r.unseenProjects());
    md.add("- " + ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE + ": "
        + ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE_ROLE);
    md.add("- the Elastic Nets' coefficients and the forests' permutation importance, in the files "
        + "beside this one, are the main reading's");
    md.add("");
    section(md, readings.main(), "Main reading: the usual flow");
    section(md, r, "Complementary reading: all issues");
    md.add("Files beside this one: " + String.join(", ", OUTPUTS.subList(1, OUTPUTS.size()))
        + ".");
    return md;
  }

  /** Every table of one reading, under its own heading. */
  private static void section(final List<String> md, final SimilarityScoring.Report r,
      final String title) {
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
    md.add("### MAE by band of the true business days");
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
    md.add("### Side reading: the retrospective pairs on the " + r.withCommitRows()
        + " issues with an implementation commit");
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
