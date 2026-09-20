package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.cost.Contribution;
import app.cost.CostCurve;
import app.cost.CostParams;
import app.cost.Seniority;
import app.pipeline.SeniorityReportTask;
import java.util.List;
import java.util.function.ToIntFunction;

/** Reports seniority {@code q} and the cost curve over the ledger. Reads only, writes nothing. */
public final class SeniorityCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final PipelineConfig config = context.config();
    final ToIntFunction<Contribution> volume =
        config.javaOnlyVolume() ? Seniority.javaLines() : Seniority.allLines();
    final CostParams params = new CostParams(config.costMinimum(), config.costRatio());

    final SeniorityReportTask.Report report;
    try {
      report = new SeniorityReportTask(context.ledger()).run(params, volume);
    } catch (final SeniorityReportTask.LedgerEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("  volume            : %s%n", config.javaOnlyVolume() ? ".java" : "all files");
    System.out.printf("  as of             : %s%n", report.asOf());
    System.out.printf("  contributions     : %,d (bots excluded)%n", report.contributions());
    System.out.printf("  developers        : %,d in %,d projects%n",
        report.developers(), report.projects());

    System.out.println();
    System.out.println("  q, career scope (rank over the whole corpus)");
    printQuantiles(report.careerRanks(), "    q");

    System.out.println();
    System.out.println("  |q_project - q_career| over every (project, developer) pair");
    printQuantiles(report.scopeGaps(), "    gap");

    System.out.printf("%n  cost at C_min=%.4g, ratio=%.4g  ->  C_max=%.4g%n",
        params.minimum(), params.ratio(), params.maximum());
    printQuantiles(report.costs(), "    C");
    final CostCurve curve = new CostCurve(params);
    System.out.printf("    curve   ln C = %.4f %+.4f q %+.4f q^2   C(0.5)=%.4f%n",
        params.delta0(), params.delta1(), params.delta2(), curve.at(0.5));
  }

  private static void printQuantiles(final List<Double> sorted, final String label) {
    if (sorted.isEmpty()) {
      System.out.println(label + "   (empty)");
      return;
    }
    // The maximum carries more digits than the quantiles on purpose: `q` divides by the whole
    // population, so the most senior developer lands just below 1 and a three-decimal max would
    // print as 1.000 and misreport the ceiling as attained.
    System.out.printf("%s   p10=%.3f  p25=%.3f  p50=%.3f  p75=%.3f  p90=%.3f  max=%.6f%n", label,
        SeniorityReportTask.Report.quantile(sorted, 0.10),
        SeniorityReportTask.Report.quantile(sorted, 0.25),
        SeniorityReportTask.Report.quantile(sorted, 0.50),
        SeniorityReportTask.Report.quantile(sorted, 0.75),
        SeniorityReportTask.Report.quantile(sorted, 0.90),
        sorted.get(sorted.size() - 1));
  }
}
