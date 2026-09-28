package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.RowCache;
import app.pipeline.Paper5CheckTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Measures the five frozen specifications on the inner selection, and nothing else.
 *
 * <p>Same rows and same split as {@code paper5-fit-inner}, but no search: alpha and lambda come from
 * the registration, so each specification is fitted once. It answers the two questions added before
 * the reserved partition was opened -- pairwise ordering accuracy, and whether the table's order
 * survives resampling -- in minutes rather than in an hour of rediscovering known parameters.
 */
public final class Paper5CheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String a4 = System.getenv("A4");
    if (cache == null || cache.isBlank() || a4 == null || a4.isBlank()) {
      throw new CommandFailedException(List.of("  defina CACHE=<caminho> e A4=<caminho>"));
    }
    final List<RowCache.Entry> entries;
    final app.paper5.Artigo4Features features;
    try {
      entries = RowCache.read(Path.of(cache));
      features = app.paper5.Artigo4Features.read(Path.of(a4));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler: " + e));
    }

    final Paper5CheckTask.Report report =
        new Paper5CheckTask(context.progress()).run(entries, features);

    System.out.printf("%n  ARTIGO 5 -- AS CINCO ESPECIFICACOES CONGELADAS, NA SELECAO INTERNA%n");
    System.out.printf("  perfil %s, Elastic Net, parametros do registro previo.%n",
        app.pipeline.Paper5ReservedTask.PROFILE);
    System.out.printf("  ajustado em %,d linhas, pontuado em %,d. O reservado nao foi tocado.%n%n",
        report.fitRows(), report.selectionRows());

    final var outcome = report.outcome();
    System.out.printf("  o alvo, nas linhas pontuadas: mediana=%.1f h   media=%.1f h   p90=%.1f h%n%n",
        outcome.medianHours(), outcome.meanHours(), outcome.p90Hours());

    System.out.printf("  %-26s %8s %9s %10s %9s %11s%n",
        "especificacao", "MAE h", "MAE dias", "vs const %", "Spearman", "par a par");
    print(report.baseline().label(), report.baseline());
    print(report.constant().label(), report.constant());
    for (final Paper5CheckTask.Measured m : report.measured()) {
      print(m.frozen().family() + " (" + m.columns() + " col)", m.score());
    }

    System.out.printf("%n%n  A ORDEM ACIMA SOBREVIVE AO RUIDO?%n");
    System.out.printf("  reamostragem pareada das mesmas %,d linhas, %d sorteios, semente %d.%n",
        report.selectionRows(), app.paper5.model.Bootstrap.DRAWS,
        app.paper5.model.Bootstrap.SEED);
    System.out.printf("  'vence' e a fracao de sorteios em que o primeiro errou menos."
        + " Intervalo contendo zero significa empate nestas linhas.%n%n");
    System.out.printf("    %-24s %-24s %8s %11s %22s%n",
        "especificacao", "contra", "vence", "dif. media", "intervalo de 95%");
    for (final app.paper5.model.Bootstrap.Comparison c : report.comparisons()) {
      System.out.printf("    %-24s %-24s %7.1f%% %+11.2f   [%+7.2f, %+7.2f]  %s%n",
          c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "EMPATE" : "");
    }
    System.out.printf("%n  nada foi escrito, e o conjunto reservado nao foi alcancado.%n");
  }

  private static void print(final String label, final app.paper5.model.Score score) {
    System.out.printf("  %-26s %8.2f %9.2f %+9.1f%% %9.3f %10.1f%%%n",
        label.length() > 26 ? label.substring(0, 26) : label,
        score.maeHours(), score.maeDays(), score.gainOverConstantPercent(),
        score.spearman(), 100 * score.pairwise());
  }
}
