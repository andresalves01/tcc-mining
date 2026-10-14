package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pullrequests.RowCache;
import app.pipeline.PullRequestAblationTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Puts each specification, stripped of the size of the change, against a model that is only size.
 *
 * <p>Development side. The reserved partition was scored once and is not reopened for this.
 */
public final class PullRequestAblationCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String a4 = System.getenv("A4");
    if (cache == null || cache.isBlank() || a4 == null || a4.isBlank()) {
      throw new CommandFailedException(List.of("  defina CACHE=<caminho> e A4=<caminho>"));
    }
    final List<RowCache.Entry> entries;
    final app.pullrequests.PriorStudyFeatures features;
    try {
      entries = RowCache.read(Path.of(cache));
      features = app.pullrequests.PriorStudyFeatures.read(Path.of(a4));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler: " + e));
    }

    final PullRequestAblationTask.Report report =
        new PullRequestAblationTask(context.progress()).run(entries, features);

    System.out.printf("%n  OS CENARIOS SEM O TAMANHO, CONTRA UM MODELO QUE E SO O TAMANHO%n");
    System.out.printf("  selecao interna: ajusta em %,d, pontua em %,d. O reservado nao foi"
        + " tocado.%n", report.fitRows(), report.selectionRows());
    System.out.printf("  linhas alteradas = adicionadas + removidas. Cada cenario amputado mantem"
        + " o alpha e o lambda%n  do completo: reajustar mudaria duas coisas de uma vez e a"
        + " comparacao deixaria de ser ablacao.%n%n");

    System.out.printf("  %-46s %5s %9s %10s %9s %11s%n",
        "modelo", "col", "MAE h", "vs const %", "Spearman", "par a par");
    print(report.baseline());
    print(report.constant());
    for (final PullRequestAblationTask.Measured m : report.measured()) {
      System.out.printf("  %-46s %5d %9.2f %+9.1f%% %9.3f %10.1f%%%n",
          m.label(), m.columns(), m.score().maeHours(), m.score().gainOverConstantPercent(),
          m.score().spearman(), 100 * m.score().pairwise());
    }

    System.out.printf("%n%n  SEM TAMANHO, CONTRA UM MODELO QUE E SO TAMANHO%n");
    System.out.printf("  as primeiras cinco sao a comparacao simetrica -- o cenario perdeu linhas"
        + " E arquivos,%n  e o adversario tem os dois. As ultimas cinco perderam so as linhas e"
        + " enfrentam so as linhas.%n");
    System.out.printf("  reamostragem pareada, %d sorteios. Intervalo contendo zero = empate.%n%n",
        app.pullrequests.model.Bootstrap.DRAWS);
    System.out.printf("    %-42s %-30s %8s %11s %22s%n",
        "o cenario amputado", "contra", "vence", "dif. media", "intervalo de 95%");
    for (final app.pullrequests.model.Bootstrap.Comparison c : report.comparisons()) {
      System.out.printf("    %-42s %-30s %7.1f%% %+11.2f   [%+7.2f, %+7.2f]  %s%n",
          c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "EMPATE" : "");
    }
    System.out.printf("%n  nada foi escrito.%n");
  }

  private static void print(final app.pullrequests.model.Score score) {
    System.out.printf("  %-46s %5s %9.2f %+9.1f%% %9.3f %10.1f%%%n",
        score.label(), "-", score.maeHours(), score.gainOverConstantPercent(),
        score.spearman(), 100 * score.pairwise());
  }
}
