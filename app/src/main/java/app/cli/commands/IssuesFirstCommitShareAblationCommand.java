package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.RowCache;
import app.issues.ShareAblation;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.ProjectBootstrap;
import app.model.Parallel;
import app.study.model.Score;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The first-commit study's share ablation ({@link ShareAblation}), a diagnostic outside the
 * fourteen declared models, on the inner validation only. Reads MATRIX, the first-commit
 * retrospective matrix, and ELASTIC_NET_PARAMETERS, the selection's choices made on it, of which
 * it reads the tail cut's k and, to check that the variant with the share is the selection's own
 * choice, the own retrospective model's. Of the matrix only the outer training rows are kept, the
 * others left behind on their split field. Writes {@link #REPORT} and {@link #TABLE} to
 * REPORT_DIR; nothing to the database, no run recorded.
 */
public final class IssuesFirstCommitShareAblationCommand implements CommandRunner {

  public static final String STAGE = "issues-first-commit-share-ablation";

  public static final String REPORT = "share-ablation.md";

  public static final String TABLE = "share-ablation.tsv";

  private final Function<String, String> settings;

  public IssuesFirstCommitShareAblationCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /** @param settings the settings by name; the tests hand their own */
  IssuesFirstCommitShareAblationCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /** Never reaches {@code context}: nothing is read from or written to the database. */
  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = IssuesSimilarityTestCommand.path(settings, "MATRIX");
    final Path elasticNet = IssuesSimilarityTestCommand.path(settings, "ELASTIC_NET_PARAMETERS");
    final Path reports = IssuesSimilarityTestCommand.path(settings, "REPORT_DIR");
    for (final String name : List.of(REPORT, TABLE)) {
      for (final Path in : List.of(matrix, elasticNet)) {
        if (IssuesSimilarityTestCommand.same(reports.resolve(name), in)) {
          throw refused(reports.resolve(name) + " is an input of this step and is never written");
        }
      }
    }
    final IssuesSimilarityTestCommand.Chosen chosen;
    final List<RowCache.Entry> training;
    try {
      if (RowCache.splitPopulation(matrix) != Population.FIRST_COMMIT) {
        throw refused(matrix + " is not of the first-commit population: the share is its "
            + "attribute alone");
      }
      try {
        chosen = IssuesSimilarityTestCommand.chosen(elasticNet, matrix);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      if (chosen.population() != Population.FIRST_COMMIT) {
        throw refused(elasticNet + " was written for " + chosen.population());
      }
      // the outer training rows only, crossing ones included: every other row is left behind
      // on its split field
      training = RowCache.training(matrix, Population.FIRST_COMMIT);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read the inputs: " + e));
    }
    final ShareAblation.Result r;
    try {
      r = ShareAblation.run(BusinessDays.population(training, Augmentation.Variant.INNER)
          .entries(), chosen.cut().k(), Parallel.threads(Parallel.ELASTIC_NET_THREADS));
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    // the variant with the share is the selection's own choice, or the procedure is not its own
    final ElasticNetSelection.Choice own = chosen.choices().get(ElasticNetSelection.RETROSPECTIVE);
    if (own.alpha() != r.with().alpha() || own.lambda() != r.with().lambda()
        || !own.combination().equals(r.with().combination())) {
      throw refused("the variant with the share chose " + r.with() + ", not the selection's "
          + own);
    }
    final List<String> md = report(r, matrix, elasticNet, chosen);
    md.forEach(line -> System.out.println("  " + line));
    try {
      Files.createDirectories(reports);
      IssuesSimilarityTestCommand.write(reports.resolve(REPORT), md);
      IssuesSimilarityTestCommand.write(reports.resolve(TABLE), table(r));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write to " + reports + ": " + e));
    }
    System.out.printf("%n  written to %s: %s, %s; nothing recorded in the database%n", reports,
        REPORT, TABLE);
  }

  static List<String> report(final ShareAblation.Result r, final Path matrix,
      final Path elasticNet, final IssuesSimilarityTestCommand.Chosen chosen) {
    final List<String> md = new ArrayList<>();
    md.add("# Issue duration from the first commit: the share ablation (a diagnostic)");
    md.add("");
    md.add("**NOTE: " + IssuesSimilarityInnerReportCommand.OPTIMISTIC + ". " + ShareAblation.RULE
        + ".**");
    md.add("");
    md.add("- matrix: " + matrix + " (sha256 " + chosen.matrixSha() + "); outer training rows "
        + "only, the others left behind on their split field");
    md.add("- choices: " + elasticNet + " (sha256 " + chosen.elasticNetSha() + "): the tail "
        + "cut's k, and the own retrospective model's choice, which the variant with the share "
        + "reproduces");
    md.add(f("- tail cut: k %s, fence %s business days; %d inner fitting issues fitted",
        TailCut.label(r.k()), r.fence(), r.fitRows()));
    for (final ShareAblation.Chosen c : List.of(r.with(), r.without())) {
      md.add(f("- %s: Jaccard n %d, embedding n %d, cluster k %d, alpha %s, lambda %s; usual-flow "
          + "MAE at selection %.3f", c.name(), c.combination().jaccardN(),
          c.combination().embeddingN(), c.combination().clusterK(), c.alpha(), c.lambda(),
          c.selectionMae()));
    }
    md.add("");
    md.add("| reading | issues | MAE with | MAE without | MAE with - without [95%] "
        + "| Spearman with | Spearman without | Spearman with - without [95%] "
        + "| pairwise with - without [95%] |");
    md.add("|---|---:|---:|---:|---|---:|---:|---|---|");
    for (final ShareAblation.Reading x : r.readings()) {
      final ProjectBootstrap.Gap s = x.ordering().spearman();
      final ProjectBootstrap.Gap p = x.ordering().pairwise();
      md.add(f("| %s | %d | %.3f | %.3f | %+.3f [%+.3f, %+.3f]%s | %.3f | %.3f "
          + "| %+.3f [%+.3f, %+.3f]%s | %+.3f [%+.3f, %+.3f]%s |",
          x.reading().name().toLowerCase(Locale.ROOT), x.rows(), x.with().mae(),
          x.without().mae(), x.gap().gap(), x.gap().low(), x.gap().high(),
          x.gap().tied() ? " tie" : "", x.with().spearman(), x.without().spearman(), s.gap(),
          s.low(), s.high(), s.tied() ? " tie" : "", p.gap(), p.low(), p.high(),
          p.tied() ? " tie" : ""));
    }
    md.add("");
    md.add("Intervals resample whole projects (" + ProjectBootstrap.DRAWS + " draws, seed "
        + ProjectBootstrap.SEED + "); an interval containing zero is a tie.");
    return md;
  }

  static List<String> table(final ShareAblation.Result r) {
    final List<String> out = new ArrayList<>();
    out.add("reading\tissues\tmodel\tmae_bd\tspearman\tpairwise\tjaccard_n\tembedding_n"
        + "\tcluster_k\talpha\tlambda\tmae_with_minus_without\tci_low\tci_high"
        + "\tspearman_with_minus_without\tspearman_ci_low\tspearman_ci_high");
    for (final ShareAblation.Reading x : r.readings()) {
      for (final ShareAblation.Chosen c : List.of(r.with(), r.without())) {
        final Score s = c == r.with() ? x.with() : x.without();
        out.add(String.join("\t", x.reading().name().toLowerCase(Locale.ROOT),
            Integer.toString(x.rows()), c.name(), d(s.mae()), d(s.spearman()), d(s.pairwise()),
            Integer.toString(c.combination().jaccardN()),
            Integer.toString(c.combination().embeddingN()),
            Integer.toString(c.combination().clusterK()), d(c.alpha()), d(c.lambda()),
            d(x.gap().gap()), d(x.gap().low()), d(x.gap().high()),
            d(x.ordering().spearman().gap()), d(x.ordering().spearman().low()),
            d(x.ordering().spearman().high())));
      }
    }
    return out;
  }

  private static String d(final double v) {
    return Double.toString(v);
  }

  private static String f(final String format, final Object... args) {
    return String.format(Locale.ROOT, format, args);
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was scored or written: " + why));
  }
}
