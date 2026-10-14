package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.features.FeatureMatrix;
import app.pipeline.BuildFeaturesTask;
import app.pipeline.SplitTask;
import java.time.Duration;
import java.util.List;

/** Builds one predictor row per label, every quantity read as of the issue's creation. */
public final class FeaturesCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Duration window = context.config().teamWindow();

    final BuildFeaturesTask task = new BuildFeaturesTask(context.labelStore(), context.ledger(),
        context.complexityStore(), context.issueAttributes(), context.featureStore(),
        context.progress());

    final BuildFeaturesTask.Report report;
    try {
      report = task.run(window);
    } catch (final SplitTask.LabelsEmptyException | BuildFeaturesTask.LedgerEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    print(report, window);
    System.out.printf("%n  wrote %,d rows to %s%n", report.written(),
        context.featureStore().describe());
  }

  private static void print(final BuildFeaturesTask.Report report, final Duration window) {

    final FeatureMatrix.Scan scan = report.scan();
    final int labels = report.labels();

    System.out.printf("%n  PREDICTORS PER LABEL%n%n");
    System.out.printf("  labels : %,d      active team window : %d days%n",
        labels, window.toDays());

    System.out.printf("%n  DEVELOPER HISTORY%n");
    line("no earlier history, LOC_tot = 0 legitimate", scan.withoutHistory(), labels);
    line("  of these, (dev, project) pair absent from the ledger", scan.unknownPair(), labels);
    System.out.printf("    %-48s%n",
        "(zero for being the first task and zero for lack of evidence enter the model alike,");
    System.out.printf("    %-48s%n",
        " and only this line tells them apart -- neither should be discarded)");

    System.out.printf("%n  ACTIVE TEAM%n");
    line("labels with no one active in the window", scan.emptyTeam(), labels);
    System.out.printf("    %-48s%n",
        "(the study excludes these from the full model and counts them)");
    final List<Double> sizes = scan.teamSizes().stream()
        .map(Integer::doubleValue).sorted().toList();
    System.out.printf("    %-48s p25=%5.0f  p50=%5.0f  p75=%5.0f  p95=%5.0f%n",
        "team size", Quantiles.at(sizes, 0.25), Quantiles.at(sizes, 0.50),
        Quantiles.at(sizes, 0.75), Quantiles.at(sizes, 0.95));

    System.out.printf("%n  CREATION ATTRIBUTES  (outside the specification, stored flagged)%n");
    line("issue found in the source corpus", scan.attributesFound(), labels);
    line("description field stored", scan.withDescription(), labels);
    line("  and not empty", scan.withNonEmptyDescription(), labels);
    line("type field stored", scan.withIssueType(), labels);
    line("linked issues field stored", scan.withLinkedIssues(), labels);
    line("  and with at least one link", scan.withAnyLink(), labels);
    System.out.printf("    %-48s%n",
        "(the corpus stores an empty list far more often than it omits the field, so almost every");
    System.out.printf("    %-48s%n",
        " zero here is real -- only the omission is missing data, and it is rare)");

    if (report.withComplexity()) {
      System.out.printf("%n  ACCUMULATED COMPLEXITY%n");
      line("rows with McCC_tot", labels, labels);
      System.out.printf("    %-48s%n",
          "(summed once per commit; methods with no metric in the corpus do not enter and are not zero)");
    } else {
      System.out.printf("%n  MISSING IN THIS RUN%n");
      System.out.printf("    %-48s %9s%n", "McCC_tot, mu_M, s_M", "100,00%");
      System.out.printf("    %-48s%n",
          "(the per-commit complexity table is missing: run `complexity`. The funnel deduplicates once");
      System.out.printf("    %-48s%n",
          " per issue, and McCC_tot needs once per commit, which is another rule)");
    }
  }

  private static void line(final String name, final int count, final int total) {
    System.out.printf("    %-48s %9s   %6.2f%%%n", name, String.format("%,d", count),
        total == 0 ? 0 : 100.0 * count / total);
  }
}
