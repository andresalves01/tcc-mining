package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.RowCache;
import app.pipeline.Paper5CheckTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
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

    // PERFIL existe para poder ler os dois lados da fronteira que o estudo investiga. Nao muda
    // nada do reservado: este comando nao o alcanca, e o registro previo fixou um perfil so.
    final String escolhido = System.getenv("PERFIL");
    final app.paper5.Profile profile = escolhido == null || escolhido.isBlank()
        ? app.pipeline.Paper5ReservedTask.PROFILE
        : app.paper5.Profile.valueOf(escolhido.trim());

    final Paper5CheckTask.Report report =
        new Paper5CheckTask(context.progress()).run(entries, features, profile);

    System.out.printf("%n  ARTIGO 5 -- AS CINCO ESPECIFICACOES CONGELADAS, NA SELECAO INTERNA%n");
    System.out.printf("  perfil %s, Elastic Net, %s.%n", profile,
        profile == app.pipeline.Paper5ReservedTask.PROFILE
            ? "parametros do registro previo"
            : "parametros que a varredura escolheu -- NAO congelados, leitura de desenvolvimento");
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

    System.out.printf("%n%n  QUAIS VARIAVEIS ORDENAM, UMA A UMA%n");
    System.out.printf("  ordenando as linhas so por aquela coluna, quantos pares ela acerta."
        + " 50%% e a moeda.%n");
    System.out.printf("  abaixo de 50%% a coluna ordena ao contrario -- mais dela vai com pull"
        + " request MAIS RAPIDO.%n");
    System.out.printf("  para ordenacao, um modelo de uma variavel E a variavel: o ajuste e"
        + " monotono nela.%n");
    for (final Paper5CheckTask.Measured m : report.measured()) {
      System.out.printf("%n  %s%n", m.frozen().family());
      m.alone().stream()
          .sorted(Comparator.comparingDouble((Paper5CheckTask.Alone a) -> -a.power()))
          .limit(8)
          .forEach(a -> System.out.printf("    %-44s %6.1f%%   %s%n", a.column(),
              100 * a.accuracy(),
              a.accuracy() >= 0.5 ? "mais = mais lento" : "mais = mais rapido"));
      System.out.printf("    %-44s %6.1f%%   <- o modelo inteiro%n", "(as " + m.columns()
          + " colunas juntas)", 100 * m.score().pairwise());
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
