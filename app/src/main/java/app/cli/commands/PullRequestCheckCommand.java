package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pullrequests.RowCache;
import app.pipeline.PullRequestCheckTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Measures the five frozen specifications on the inner selection, and nothing else.
 *
 * <p>Same rows and same split as {@code pr-fit-inner}, but no search: alpha and lambda come from
 * the registration, so each specification is fitted once. It answers the two questions added before
 * the reserved partition was opened -- pairwise ordering accuracy, and whether the table's order
 * survives resampling -- in minutes rather than in an hour of rediscovering known parameters.
 */
public final class PullRequestCheckCommand implements CommandRunner {

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

    // PERFIL existe para poder ler os dois lados da fronteira que o estudo investiga. Nao muda
    // nada do reservado: este comando nao o alcanca, e o registro previo fixou um perfil so.
    final String escolhido = System.getenv("PERFIL");
    final app.pullrequests.Profile profile = escolhido == null || escolhido.isBlank()
        ? app.pipeline.PullRequestReservedTask.PROFILE
        : app.pullrequests.Profile.valueOf(escolhido.trim());

    final PullRequestCheckTask.Report report =
        new PullRequestCheckTask(context.progress()).run(entries, features, profile);

    System.out.printf("%n  PULL REQUESTS -- AS CINCO ESPECIFICACOES CONGELADAS, NA SELECAO INTERNA%n");
    System.out.printf("  perfil %s, Elastic Net, %s.%n", profile,
        profile == app.pipeline.PullRequestReservedTask.PROFILE
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
    for (final PullRequestCheckTask.Measured m : report.measured()) {
      print(m.frozen().family() + " (" + m.columns() + " col)", m.score());
    }

    printBreakdown(report);

    System.out.printf("%n%n  QUAIS VARIAVEIS ORDENAM, UMA A UMA%n");
    System.out.printf("  ordenando as linhas so por aquela coluna, quantos pares ela acerta."
        + " 50%% e a moeda.%n");
    System.out.printf("  abaixo de 50%% a coluna ordena ao contrario -- mais dela vai com pull"
        + " request MAIS RAPIDO.%n");
    System.out.printf("  para ordenacao, um modelo de uma variavel E a variavel: o ajuste e"
        + " monotono nela.%n");
    for (final PullRequestCheckTask.Measured m : report.measured()) {
      System.out.printf("%n  %s%n", m.frozen().family());
      m.alone().stream()
          .sorted(Comparator.comparingDouble((PullRequestCheckTask.Alone a) -> -a.power()))
          .limit(8)
          .forEach(a -> System.out.printf("    %-44s %6.1f%%   %s%n", a.column(),
              100 * a.accuracy(),
              a.accuracy() >= 0.5 ? "mais = mais lento" : "mais = mais rapido"));
      System.out.printf("    %-44s %6.1f%%   <- o modelo inteiro%n", "(as " + m.columns()
          + " colunas juntas)", 100 * m.score().pairwise());
    }

    System.out.printf("%n%n  A ORDEM ACIMA SOBREVIVE AO RUIDO?%n");
    System.out.printf("  reamostragem pareada das mesmas %,d linhas, %d sorteios, semente %d.%n",
        report.selectionRows(), app.pullrequests.model.Bootstrap.DRAWS,
        app.pullrequests.model.Bootstrap.SEED);
    System.out.printf("  'vence' e a fracao de sorteios em que o primeiro errou menos."
        + " Intervalo contendo zero significa empate nestas linhas.%n%n");
    System.out.printf("    %-24s %-24s %8s %11s %22s%n",
        "especificacao", "contra", "vence", "dif. media", "intervalo de 95%");
    for (final app.pullrequests.model.Bootstrap.Comparison c : report.comparisons()) {
      System.out.printf("    %-24s %-24s %7.1f%% %+11.2f   [%+7.2f, %+7.2f]  %s%n",
          c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "EMPATE" : "");
    }
    System.out.printf("%n  nada foi escrito, e o conjunto reservado nao foi alcancado.%n");
  }

  /** As previsoes da referencia, reconstruidas do proprio Score para poder recorta-las. */
  private static double[] baselinePredictions(final PullRequestCheckTask.Report report) {
    return report.baselineHours();
  }

  /** Onde o erro vive: a distribuicao dele, e o erro por faixa de duracao real. */
  private static void printBreakdown(final PullRequestCheckTask.Report report) {
    System.out.printf("%n%n  ONDE O ERRO VIVE%n");
    System.out.printf("  a media do erro nao descreve o pull request tipico: ela mistura acertos"
        + " em muitas linhas%n  com enganos enormes em poucas. Abaixo, a distribuicao do proprio"
        + " erro.%n%n");
    System.out.printf("  %-26s %9s %9s %9s %9s %9s %14s%n",
        "modelo", "MAE", "mediana", "p75", "p90", "p99", "sem o decil+");
    for (final PullRequestCheckTask.Measured m : report.measured()) {
      final var e = app.pullrequests.model.Breakdown.errors(m.predictedHours(), report.observedHours());
      System.out.printf("  %-26s %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f (n=%d)%n",
          m.frozen().family().toString().length() > 26
              ? m.frozen().family().toString().substring(0, 26) : m.frozen().family(),
          e.mae(), e.p50(), e.p75(), e.p90(), e.p99(), e.trimmedMae(), e.trimmedN());
    }

    System.out.printf("%n%n  O CORPO DA DISTRIBUICAO: entre o quartil de baixo e o decil de cima%n");
    System.out.printf("  fora os que mesclaram quase na hora e fora a cauda longa. As referencias"
        + " sao cortadas nas%n  MESMAS linhas -- comparar um modelo num subconjunto facil contra"
        + " uma referencia no todo%n  seria comparar duas perguntas diferentes.%n%n");
    final var corpoRef = app.pullrequests.model.Breakdown.body(
        report.baseline().n() > 0 ? baselinePredictions(report) : new double[0],
        report.observedHours(), 0.25, 0.90);
    System.out.printf("  %-26s %6s %12s %11s %13s %9s %10s%n",
        "modelo", "n", "dur. mediana", "erro medio", "erro mediano", "Spearman", "par a par");
    for (final PullRequestCheckTask.Measured m : report.measured()) {
      final var c = app.pullrequests.model.Breakdown.body(m.predictedHours(), report.observedHours(),
          0.25, 0.90);
      System.out.printf("  %-26s %6d %10.1f h %9.1f h %11.1f h %9.3f %9.1f%%%n",
          m.frozen().family(), c.n(), c.medianTruth(), c.mae(), c.medianError(),
          c.spearman(), 100 * c.pairwise());
      if (m == report.measured().get(0)) {
        System.out.printf("  %-26s %6s %10s %9s %11s %9s %9s%n", "  (faixa: de "
            + String.format("%.1f", c.lowHours()) + " h a "
            + String.format("%.1f", c.highHours()) + " h)", "", "", "", "", "", "");
      }
    }
    System.out.printf("  %-26s %6d %10.1f h %9.1f h %11.1f h %9.3f %9.1f%%%n",
        "referencia: med. projeto", corpoRef.n(), corpoRef.medianTruth(), corpoRef.mae(),
        corpoRef.medianError(), corpoRef.spearman(), 100 * corpoRef.pairwise());

    System.out.printf("%n%n  O ERRO POR FAIXA DE DURACAO REAL%n");
    System.out.printf("  faixas cortadas na VERDADE, nunca na previsao -- cortar na previsao"
        + " deixaria o modelo%n  escolher os proprios casos faceis. Todos sao avaliados nas"
        + " mesmas linhas de cada faixa.%n");
    for (final PullRequestCheckTask.Measured m : report.measured()) {
      System.out.printf("%n  %s%n", m.frozen().family());
      System.out.printf("    %-20s %6s %14s %12s %14s%n",
          "faixa", "n", "duracao mediana", "erro medio", "erro mediano");
      for (final app.pullrequests.model.Breakdown.Band b
          : app.pullrequests.model.Breakdown.byTruth(m.predictedHours(), report.observedHours())) {
        System.out.printf("    %-20s %6d %13.1f h %10.1f h %12.1f h%n",
            b.label(), b.n(), b.medianTruth(), b.mae(), b.medianError());
      }
    }
  }

  private static void print(final String label, final app.pullrequests.model.Score score) {
    System.out.printf("  %-26s %8.2f %9.2f %+9.1f%% %9.3f %10.1f%%%n",
        label.length() > 26 ? label.substring(0, 26) : label,
        score.maeHours(), score.maeDays(), score.gainOverConstantPercent(),
        score.spearman(), 100 * score.pairwise());
  }
}
