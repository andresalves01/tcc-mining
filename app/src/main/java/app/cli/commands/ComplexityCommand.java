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

    System.out.printf("%n  COMPLEXIDADE POR COMMIT%n%n");
    System.out.printf("  commits que algum preditor pode consultar : %,d%n", report.targeted());
    System.out.printf("  ja lidos em execucao anterior             : %,d%n",
        report.alreadyStored());
    System.out.printf("  lidos agora                               : %,d%n",
        report.scan().scanned());
    System.out.printf("    destes, com ao menos um metodo recuperavel : %,d   (%.2f%%)%n",
        report.scan().withMethods(), share(report.scan().withMethods(), report.scan().scanned()));
    System.out.printf("    metodos somados                            : %,d%n",
        report.scan().methods());
    System.out.printf("    metodos alcancados sem metrica no corpus   : %,d   (%.2f%%)%n",
        report.scan().unrecoverable(),
        share(report.scan().unrecoverable(),
            report.scan().methods() + report.scan().unrecoverable()));
    System.out.printf("    (nao entram na soma e nao sao zero -- e cobertura, e vai relatada)%n");
    System.out.printf("%n  gravou %,d linhas em %s%n", report.written(),
        context.complexityStore().describe());
    System.out.printf("  rode `features` de novo para que McCC_tot, mu_M e s_M deixem de faltar%n");
  }

  private static double share(final long part, final long total) {
    return total == 0 ? 0 : 100.0 * part / total;
  }
}
