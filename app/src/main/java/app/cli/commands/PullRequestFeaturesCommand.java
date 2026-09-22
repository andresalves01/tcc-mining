package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.features.FeatureMatrix;
import app.pipeline.BuildFeaturesTask;
import app.pipeline.SplitTask;
import java.time.Duration;
import java.util.List;

/**
 * Builds {@code issue_features} for the pull-request labels -- {@link BuildFeaturesTask} itself,
 * completely unmodified from the issue pipeline. What differs is which databases the ports behind
 * {@code context} point at: {@code labelStore}/{@code featureStore} read and write {@code
 * TARGET_DB} (empty until {@code pr-labels} and {@code split} have run against it); {@code
 * externalLedger}/{@code externalComplexity} read {@code LEDGER_DB} -- an already-built ledger and
 * complexity table this stage never writes to; {@code pullRequestAttributes} reads {@code
 * SOURCE_DB}. See {@code papers/04-pull-requests/PLANO.md}.
 */
public final class PullRequestFeaturesCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Duration window = context.config().teamWindow();

    final BuildFeaturesTask task = new BuildFeaturesTask(context.labelStore(),
        context.externalLedger(), context.externalComplexity(), context.pullRequestAttributes(),
        context.featureStore(), context.progress());

    final BuildFeaturesTask.Report report;
    try {
      report = task.run(window);
    } catch (final SplitTask.LabelsEmptyException | BuildFeaturesTask.LedgerEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    final FeatureMatrix.Scan scan = report.scan();
    System.out.printf("%n  PREDITORES POR PULL REQUEST%n%n");
    System.out.printf("  rotulos : %,d      janela da equipe ativa : %d dias%n",
        report.labels(), window.toDays());
    System.out.printf("%n  sem historico anterior do autor, LOC_tot = 0 legitimo : %,d%n",
        scan.withoutHistory());
    System.out.printf("    destes, par (autor, projeto) ausente do ledger       : %,d%n",
        scan.unknownPair());
    System.out.printf("  rotulos sem ninguem ativo na janela de equipe          : %,d%n",
        scan.emptyTeam());
    System.out.printf("  descricao do PR gravada e nao vazia                    : %,d de %,d%n",
        scan.withNonEmptyDescription(), report.labels());
    System.out.printf("%n  complexidade acumulada do autor (mccc_tot, mu_M, s_M)  : %s%n",
        report.withComplexity() ? "presente, via " + context.externalComplexity().describe()
            : "AUSENTE -- LEDGER_DB nao tem complexity construida");
    System.out.printf("  complexidade DA TAREFA (mccc_i, nl_i)                  : "
        + "zero constante em toda a populacao -- nao computavel para PRs, ver PLANO.md%n");
    System.out.printf("%n  gravou %,d linhas em %s%n", report.written(),
        context.featureStore().describe());
  }
}
