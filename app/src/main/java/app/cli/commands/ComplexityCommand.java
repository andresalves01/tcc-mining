package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pipeline.BuildComplexityTask;
import app.pipeline.BuildFeaturesTask;
import app.pipeline.SplitTask;
import java.util.List;

/** Reads each relevant commit's changed methods and sums their complexity, once per commit. */
public final class ComplexityCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final BuildComplexityTask task = new BuildComplexityTask(context.labelStore(),
        context.ledger(), context.commitComplexity(), context.complexityStore(),
        context.progress());

    final BuildComplexityTask.Report report;
    try {
      report = task.run();
    } catch (final SplitTask.LabelsEmptyException | BuildFeaturesTask.LedgerEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("%n  COMPLEXITY PER COMMIT%n%n");
    System.out.printf("  commits some predictor may look up       : %,d%n", report.targeted());
    System.out.printf("  already read in an earlier run           : %,d%n",
        report.alreadyStored());
    System.out.printf("  read now                                 : %,d%n",
        report.scan().scanned());
    System.out.printf("    of these, with at least one recoverable method : %,d   (%.2f%%)%n",
        report.scan().withMethods(), share(report.scan().withMethods(), report.scan().scanned()));
    System.out.printf("    methods summed                                 : %,d%n",
        report.scan().methods());
    System.out.printf("    methods reached with no metric in the corpus   : %,d   (%.2f%%)%n",
        report.scan().unrecoverable(),
        share(report.scan().unrecoverable(),
            report.scan().methods() + report.scan().unrecoverable()));
    System.out.printf("    (they do not enter the sum and are not zero -- it is coverage, and it is reported)%n");
    System.out.printf("%n  wrote %,d rows to %s%n", report.written(),
        context.complexityStore().describe());
    System.out.printf("  run `features` again so that McCC_tot, mu_M and s_M are no longer missing%n");
  }

  private static double share(final long part, final long total) {
    return total == 0 ? 0 : 100.0 * part / total;
  }
}
