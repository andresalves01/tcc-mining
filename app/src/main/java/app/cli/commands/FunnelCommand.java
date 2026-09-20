package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.labels.FunnelStage;
import app.labels.IssueLabel;
import app.labels.LabelScan;
import app.pipeline.BuildFunnelTask;
import app.pipeline.BuildLedgerTask;
import java.util.List;
import java.util.Optional;

/** Applies every exclusion in order, extracts the methods of the survivors, writes the labels. */
public final class FunnelCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final PipelineConfig config = context.config();

    final BuildFunnelTask task =
        new BuildFunnelTask(context.labels(), context.labelStore(), context.registry());

    final BuildFunnelTask.Report report;
    try {
      final Optional<String> pilot = config.pilotProject();
      report = pilot.isPresent() ? task.run(pilot.get()) : task.run();
    } catch (final BuildLedgerTask.RegistryEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    printFunnel(report.scan());
    System.out.printf("%n  wrote %,d rows to %s%n", report.written(),
        context.labelStore().describe());
  }

  /** The sequential exclusion table, then the totals the funnel is reported with. */
  private static void printFunnel(final LabelScan scan) {
    System.out.printf("%n  FUNIL GLOBAL DEFINITIVO%n%n");
    System.out.printf("  %-54s %9s %9s %11s %8s%n",
        "estagio", "retidas", "excluidas", "denominador", "excl.%");
    for (final FunnelStage stage : scan.stages()) {
      System.out.printf("  %-54s %9s %9s %11s %7.2f%%%n", stage.name(),
          String.format("%,d", stage.retained()), String.format("%,d", stage.excluded()),
          String.format("%,d", stage.denominator()), 100 * stage.incrementalShare());
    }

    final List<IssueLabel> labels = scan.labels();
    final List<Double> ttc = labels.stream().map(IssueLabel::ttcDays).sorted().toList();
    final FunnelStage methodStage = scan.stages().get(scan.stages().size() - 1);
    final FunnelStage previous = scan.stages().get(scan.stages().size() - 2);

    System.out.printf("%n  rotulos                       : %,d%n", labels.size());
    System.out.printf("  desenvolvedores               : %,d%n",
        labels.stream().map(IssueLabel::canonicalAuthorId).distinct().count());
    System.out.printf("  projetos                      : %,d%n",
        labels.stream().map(IssueLabel::projectName).distinct().count());
    System.out.printf("  excluidas por ausencia de metodo: %,d  (%.2f%% de %,d)%n",
        methodStage.excluded(), 100 * methodStage.incrementalShare(), methodStage.denominator());
    System.out.printf("  cobertura de metodos por issue: %.2f%%%n", 100 * scan.methodCoverage());
    System.out.printf("  metodos alterados extraidos   : %,d  (mediana %,d por rotulo)%n",
        scan.methodsExtracted(), medianMethods(labels));
    if (!ttc.isEmpty()) {
      System.out.printf("  TTC  p25=%.2fd  p50=%.2fd  p75=%.2fd  p95=%.2fd%n",
          Quantiles.at(ttc, 0.25), Quantiles.at(ttc, 0.50),
          Quantiles.at(ttc, 0.75), Quantiles.at(ttc, 0.95));
      System.out.printf("  TTC  min=%.6fd  max=%.2fd   (diagnostico)%n",
          ttc.get(0), ttc.get(ttc.size() - 1));
    }
    System.out.printf("  38 registros de migracao      : %.3f%% do novo denominador (%,d)%n",
        100.0 * 38 / Math.max(1, labels.size()), labels.size());
    System.out.printf("  estagio anterior a cobertura de metodos: %,d rotulos%n",
        previous.retained());
  }

  private static long medianMethods(final List<IssueLabel> labels) {
    final List<Integer> counts = labels.stream().map(l -> l.methods().size()).sorted().toList();
    return counts.isEmpty() ? 0 : counts.get(counts.size() / 2);
  }
}
