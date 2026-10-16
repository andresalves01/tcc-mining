package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
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
 * The inner report of the description-similarity chain (ticket 16): the seventeen models of the
 * test -- the three medians, the seven Elastic Nets and their seven forests -- refitted on the
 * inner fitting rows at the choices of the parameters file and scored on the inner validation,
 * by the study's own scoring ({@link SimilarityScoring}, {@link SimilarityScoring.Scope#INNER}),
 * in whole business days on the issues of at least one ({@link BusinessDays}).
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
 * choices were made on this very matrix (by SHA-256), the outer training, inner fitting and inner
 * validation sizes are the registered study's, and the matrix has the day boundary and every
 * column the models read. Writes files to REPORT_DIR only and nothing to the database: no run is
 * recorded, the context's ports are never reached.
 *
 * <p><b>Files.</b> The report in words and tables ({@link #REPORT}); per inner validation issue its
 * prediction by every model and whether it has an implementation commit ({@link #PREDICTIONS});
 * per model the error, the gains over the global and the project median, Spearman, pairwise
 * ordering and the error's quantiles ({@link #MODELS}); the error by band of the true business
 * days, 1, 2-3, 4-10 and 11 or more ({@link #BANDS}); the paired comparisons of the test, which
 * hold every model against the global and the project median, and the side reading of the
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
  private final RegistrationInProgress.Sizes expected;

  public IssuesSimilarityInnerReportCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    }, RegistrationInProgress.SIZES);
  }

  /**
   * @param settings the settings by name; the tests hand their own
   * @param expected the split sizes the matrix must have, the registered study's but in tests
   */
  IssuesSimilarityInnerReportCommand(final Function<String, String> settings,
      final RegistrationInProgress.Sizes expected) {
    this.settings = settings;
    this.expected = expected;
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
    if (s.train() != expected.train() || s.innerFit() != expected.innerFit()
        || s.innerSelection() != expected.innerSelection()) {
      throw refused("the outer training, inner fitting and inner validation sizes are not the "
          + "registered study's, " + expected);
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
    if (!columns.contains(BusinessDays.BOUNDARY)) {
      throw refused(matrix + " has no " + BusinessDays.BOUNDARY + "; it was written before "
          + "business days");
    }
    final BusinessDays.Population population;
    final SimilarityScoring.Report r;
    try {
      population = BusinessDays.population(training);
      r = SimilarityScoring.run(population.entries(), chosen.choices(), chosen.forests(),
          SimilarityScoring.Scope.INNER);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }

    final Map<String, List<String>> files = new LinkedHashMap<>();
    files.put(REPORT, report(r, population, chosen, matrix, elasticNet));
    files.put(PREDICTIONS, predictions(r));
    files.put(MODELS, models(r));
    files.put(BANDS, bands(r));
    files.put(COMPARISONS, comparisons(r));
    files.put(ORDERING, ordering(r));
    files.put(DIAGNOSTICS, IssuesSimilarityTestCommand.diagnostics(r));
    files.put(IMPORTANCE, IssuesSimilarityTestCommand.importance(r));
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

  /** The predictions file of the test, with whether each issue has an implementation commit. */
  static List<String> predictions(final SimilarityScoring.Report r) {
    final List<String> lines = SimilarityScoring.predictions(r);
    final List<String> out = new ArrayList<>(lines.size());
    for (int k = 0; k < lines.size(); k++) {
      final String[] f = lines.get(k).split("\t", 5);
      out.add(String.join("\t", f[0], f[1], f[2], f[3],
          k == 0 ? "has_implementation_commit" : Boolean.toString(r.implementationCommit()[k - 1]),
          f[4]));
    }
    return out;
  }

  /** Per model: the error, the gains, the ordering and the error's distribution. */
  static List<String> models(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("model\tkind\tn\tmae_bd\tgain_over_global_percent\tgain_over_project_percent"
        + "\tspearman\tpairwise\tabs_error_p50_bd\tabs_error_p75_bd\tabs_error_p90_bd"
        + "\tabs_error_p99_bd\tmae_without_slowest_tenth_bd\trows_without_slowest_tenth");
    for (final SimilarityScoring.Scored m : r.models()) {
      final Score s = m.score();
      final Breakdown.Errors e = m.errors();
      out.add(String.join("\t", m.name(), m.kind(), Integer.toString(s.n()), d(s.mae()),
          d(s.gainOverConstantPercent()), d(s.gainPercent()), d(s.spearman()), d(s.pairwise()),
          d(e.p50()), d(e.p75()), d(e.p90()), d(e.p99()), d(e.trimmedMae()),
          Integer.toString(e.trimmedN())));
    }
    return out;
  }

  /** Per model and band of the true business days, the error. */
  static List<String> bands(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("model\tband\tn\tmedian_truth_bd\tmae_bd\tmedian_abs_error_bd");
    for (final SimilarityScoring.Scored m : r.models()) {
      for (final Breakdown.Band b : m.bands()) {
        out.add(String.join("\t", m.name(), b.label(), Integer.toString(b.n()),
            d(b.medianTruth()), d(b.mae()), d(b.medianError())));
      }
    }
    return out;
  }

  /** The paired comparisons, then the side reading on the issues with and without a commit. */
  static List<String> comparisons(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("reading\trows\ta\tb\tmae_diff_bd\tci_low\tci_high\ttie");
    gaps(out, "all scored", r.scoredRows(), r.comparisons());
    gaps(out, "with implementation commit", r.withCommitRows(), r.withCommits());
    gaps(out, "without implementation commit", r.scoredRows() - r.withCommitRows(),
        r.withoutCommits());
    return out;
  }

  private static void gaps(final List<String> out, final String reading, final int rows,
      final List<ProjectBootstrap.Gap> gaps) {
    for (final ProjectBootstrap.Gap g : gaps) {
      out.add(String.join("\t", reading, Integer.toString(rows), g.a(), g.b(), d(g.gap()),
          d(g.low()), d(g.high()), Boolean.toString(g.tied())));
    }
  }

  /** The ordering intervals, a declared measure. */
  static List<String> ordering(final SimilarityScoring.Report r) {
    final List<String> out = new ArrayList<>();
    out.add("a\tb\tspearman_diff\tspearman_ci_low\tspearman_ci_high\tspearman_tie"
        + "\tpairwise_diff\tpairwise_ci_low\tpairwise_ci_high\tpairwise_tie");
    for (final ProjectBootstrap.Ordering o : r.orderings()) {
      out.add(String.join("\t", o.a(), o.b(), d(o.spearman().gap()), d(o.spearman().low()),
          d(o.spearman().high()), Boolean.toString(o.spearman().tied()), d(o.pairwise().gap()),
          d(o.pairwise().low()), d(o.pairwise().high()), Boolean.toString(o.pairwise().tied())));
    }
    return out;
  }

  /** The report in words and tables, the optimism stated first. */
  static List<String> report(final SimilarityScoring.Report r,
      final BusinessDays.Population population, final IssuesSimilarityTestCommand.Chosen chosen,
      final Path matrix, final Path elasticNet) {
    final List<String> md = new ArrayList<>();
    md.add("# Description-similarity chain: inner report");
    md.add("");
    md.add("**NOTE: " + OPTIMISTIC + ".**");
    md.add("");
    md.add("- matrix: " + matrix + " (sha256 " + chosen.matrixSha() + "); outer training rows "
        + "only, the others left behind on their split field");
    md.add("- choices: " + elasticNet + " (sha256 " + chosen.elasticNetSha() + ")");
    md.add("- target: " + BusinessDays.TARGET + "; " + BusinessDays.HOLIDAYS);
    md.add("- population: " + BusinessDays.POPULATION + "; inner fitting "
        + population.kept().innerFit() + " of " + population.all().innerFit()
        + ", inner validation " + population.kept().innerSelection() + " of "
        + population.all().innerSelection());
    md.add("- fitted on " + r.fitRows() + " inner fitting issues at the chosen alpha and lambda "
        + "(not scaled); scored on " + r.scoredRows() + " inner validation issues, "
        + r.withCommitRows() + " of them with an implementation commit");
    md.add("- validation projects with no fitting issue (the project median is the global one "
        + "there): " + r.unseenRows() + " issues, " + r.unseenProjects());
    md.add("");
    md.add("## Every model (business days)");
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
    md.add("## MAE by band of the true business days");
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
    md.add("## Paired comparisons: MAE(a) - MAE(b), business days, 95% interval resampling whole "
        + "projects (" + ProjectBootstrap.DRAWS + " draws, seed " + ProjectBootstrap.SEED + ")");
    md.add("");
    table(md, r.comparisons());
    md.add("## Side reading: the retrospective pairs on the " + r.withCommitRows()
        + " issues with an implementation commit");
    md.add("");
    table(md, r.withCommits());
    md.add("## For contrast: the same pairs on the " + (r.scoredRows() - r.withCommitRows())
        + " issues without one");
    md.add("");
    table(md, r.withoutCommits());
    md.add("## Ordering intervals (a declared measure): a - b in Spearman and in pairwise "
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
    md.add("Files beside this one: " + String.join(", ", OUTPUTS.subList(1, OUTPUTS.size()))
        + ".");
    return md;
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
