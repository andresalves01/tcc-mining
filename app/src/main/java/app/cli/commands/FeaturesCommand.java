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
        context.issueAttributes(), context.featureStore(), context.progress());

    final BuildFeaturesTask.Report report;
    try {
      report = task.run(window);
    } catch (final SplitTask.LabelsEmptyException | BuildFeaturesTask.LedgerEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    print(report, window);
    System.out.printf("%n  gravou %,d linhas em %s%n", report.written(),
        context.featureStore().describe());
  }

  private static void print(final BuildFeaturesTask.Report report, final Duration window) {
    final FeatureMatrix.Scan scan = report.scan();
    final int labels = report.labels();

    System.out.printf("%n  PREDITORES POR ROTULO%n%n");
    System.out.printf("  rotulos : %,d      janela da equipe ativa : %d dias%n",
        labels, window.toDays());

    System.out.printf("%n  HISTORICO DO DESENVOLVEDOR%n");
    line("sem historico anterior, LOC_tot = 0 legitimo", scan.withoutHistory(), labels);
    line("  destes, par (dev, projeto) ausente do ledger", scan.unknownPair(), labels);
    System.out.printf("    %-48s%n",
        "(zero por ser a primeira tarefa e zero por falta de evidencia entram iguais no modelo,");
    System.out.printf("    %-48s%n",
        " e so esta linha os separa -- nenhum dos dois deve ser descartado)");

    System.out.printf("%n  EQUIPE ATIVA%n");
    line("rotulos sem ninguem ativo na janela", scan.emptyTeam(), labels);
    System.out.printf("    %-48s%n",
        "(o estudo exclui estes do modelo completo e os contabiliza)");
    final List<Double> sizes = scan.teamSizes().stream()
        .map(Integer::doubleValue).sorted().toList();
    System.out.printf("    %-48s p25=%5.0f  p50=%5.0f  p75=%5.0f  p95=%5.0f%n",
        "tamanho da equipe", Quantiles.at(sizes, 0.25), Quantiles.at(sizes, 0.50),
        Quantiles.at(sizes, 0.75), Quantiles.at(sizes, 0.95));

    System.out.printf("%n  ATRIBUTOS DE CRIACAO  (fora da especificacao, guardados marcados)%n");
    line("issue localizada no corpus de origem", scan.attributesFound(), labels);
    line("campo de descricao gravado", scan.withDescription(), labels);
    line("  e nao vazio", scan.withNonEmptyDescription(), labels);
    line("campo de tipo gravado", scan.withIssueType(), labels);
    line("campo de issues ligadas gravado", scan.withLinkedIssues(), labels);
    line("  e com ao menos uma ligacao", scan.withAnyLink(), labels);
    System.out.printf("    %-48s%n",
        "(o corpus grava lista vazia muito mais vezes do que omite o campo, entao quase todo");
    System.out.printf("    %-48s%n",
        " zero aqui e real -- so a omissao e falta de dado, e ela e rara)");

    System.out.printf("%n  AUSENTES NESTA RODADA%n");
    System.out.printf("    %-48s %9s%n", "McCC_tot, mu_M, s_M", "100,00%");
    System.out.printf("    %-48s%n",
        "(falta a tabela de complexidade por commit: o funil deduplica uma vez por issue,");
    System.out.printf("    %-48s%n",
        " e McCC_tot exige uma vez por commit, que e outra regra)");
  }

  private static void line(final String name, final int count, final int total) {
    System.out.printf("    %-48s %9s   %6.2f%%%n", name, String.format("%,d", count),
        total == 0 ? 0 : 100.0 * count / total);
  }
}
