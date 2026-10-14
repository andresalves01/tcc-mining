package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pullrequests.RowCache;
import app.pipeline.PullRequestReservedTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The one command in this study that reaches the reserved partition, and it reaches it once.
 *
 * <p>It exists because {@code REGISTRO-PREVIO.md} was written and committed first. Everything it
 * would otherwise have to choose -- which specifications, which parameters, which references, which
 * metrics -- is already fixed there and appears here only as literals. The command has no options
 * that change a result: {@code CACHE} and {@code A4} say where the data is, and that is all.
 *
 * <p>Running it a second time and reporting the better of the two runs would defeat the design as
 * thoroughly as tuning on the reserved rows would. It is deterministic, so a rerun produces the same
 * numbers; what must not happen is a rerun after a change to anything the registration froze.
 */
public final class PullRequestReservedCommand implements CommandRunner {

  private static final java.util.logging.Logger TRIBUO =
      java.util.logging.Logger.getLogger("org.tribuo");

  static {
    TRIBUO.setLevel(java.util.logging.Level.WARNING);
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of(
          "  defina CACHE=<caminho> apontando para o arquivo que pr-extract gravou"));
    }
    final List<RowCache.Entry> entries;
    try {
      entries = RowCache.read(Path.of(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + cache + ": " + e));
    }

    final String a4 = System.getenv("A4");
    if (a4 == null || a4.isBlank()) {
      throw new CommandFailedException(List.of(
          "  defina A4=<caminho> com o export de issue_features: a especificacao congelada inclui",
          "  a familia do artigo anterior, e roda-la sem os dados dela daria um resultado vazio",
          "  parecendo um resultado ruim"));
    }
    final app.pullrequests.PriorStudyFeatures features;
    try {
      features = app.pullrequests.PriorStudyFeatures.read(Path.of(a4));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler A4=" + a4 + ": " + e));
    }

    System.out.printf("%n  PULL REQUESTS -- CONJUNTO RESERVADO%n%n");
    for (final Map.Entry<String, String> fact
        : PullRequestReservedTask.frozenSpecification().entrySet()) {

      System.out.printf("  %-22s %s%n", fact.getKey(), fact.getValue());
    }
    System.out.printf("%n  parametros congelados, copiados do registro previo:%n");
    PullRequestReservedTask.FROZEN.forEach(f -> System.out.printf(
        "    %-24s alpha=%.2f  lambda=%.4g%n", f.family(), f.alpha(), f.lambda()));

    final PullRequestReservedTask.Report report =
        new PullRequestReservedTask(context.progress()).run(entries, features);

    System.out.printf("%n  ajustado em %,d linhas de treino, pontuado em %,d reservadas%n",
        report.trainingRows(), report.reservedRows());
    System.out.printf("  linhas cujo projeto nao aparece no treino : %,d%n",
        report.baselineFellBack());

    final var outcome = report.outcome();
    System.out.printf("%n  O ALVO, no conjunto reservado (horas)%n");
    System.out.printf("    n=%,d   mediana=%.1f   media=%.1f   p25=%.1f   p75=%.1f   p90=%.1f"
        + "   maximo=%.0f%n", outcome.n(), outcome.medianHours(), outcome.meanHours(),
        outcome.p25Hours(), outcome.p75Hours(), outcome.p90Hours(), outcome.maxHours());

    // A importancia vem antes do resultado de proposito: ela sai do reajuste no treino e nao
    // depende de nada que o reservado diga, entao e resultado por si.
    System.out.printf("%n%n  O QUE CADA VARIAVEL PESOU, no reajuste sobre as %,d linhas de treino%n",
        report.trainingRows());
    for (final PullRequestReservedTask.Result result : report.results()) {
      System.out.printf("%n  %s  (alpha=%.2f lambda=%.4g, %d colunas)%n",
          result.family(), result.alpha(), result.lambda(), result.columns().size());
      result.weights().stream()
          .sorted(Comparator.comparingDouble(w -> -Math.abs(w.coefficient())))
          .limit(20)
          .forEach(w -> System.out.printf("    %-42s %+10.4f%n", w.column(), w.coefficient()));
    }

    System.out.printf("%n%n  O RESULTADO NO CONJUNTO RESERVADO%n%n");
    System.out.printf("  %-44s %6s %9s %9s %9s %8s %11s %9s %9s%n",
        "especificacao", "n", "MAE h", "MAE dias", "ganho h", "ganho %", "vs const %", "Spearman",
        "par a par");
    print(report.baseline());
    print(report.constant());
    report.results().stream()
        .sorted(Comparator.comparingDouble(r -> r.score().maeHours()))
        .forEach(r -> print(r.score()));

    System.out.printf("%n%n  ONDE O ERRO VIVE%n");
    System.out.printf("  %-26s %9s %9s %9s %9s %9s %14s%n",
        "especificacao", "MAE", "mediana", "p75", "p90", "p99", "sem o decil+");
    for (final PullRequestReservedTask.Result r : report.results()) {
      final var e = app.pullrequests.model.Breakdown.errors(r.predictedHours(), report.observedHours());
      System.out.printf("  %-26s %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f (n=%d)%n",
          r.family(), e.mae(), e.p50(), e.p75(), e.p90(), e.p99(), e.trimmedMae(), e.trimmedN());
    }
    System.out.printf("%n%n  O CORPO DA DISTRIBUICAO: entre o quartil de baixo e o decil de cima%n");
    System.out.printf("  as referencias sao cortadas nas MESMAS linhas.%n%n");
    System.out.printf("  %-26s %6s %12s %11s %13s %9s %10s%n",
        "modelo", "n", "dur. mediana", "erro medio", "erro mediano", "Spearman", "par a par");
    for (final PullRequestReservedTask.Result r : report.results()) {
      final var c = app.pullrequests.model.Breakdown.body(r.predictedHours(), report.observedHours(),
          0.25, 0.90);
      System.out.printf("  %-26s %6d %10.1f h %9.1f h %11.1f h %9.3f %9.1f%%%n",
          r.family(), c.n(), c.medianTruth(), c.mae(), c.medianError(), c.spearman(),
          100 * c.pairwise());
    }
    final var corpoBase = app.pullrequests.model.Breakdown.body(report.baselineHours(),
        report.observedHours(), 0.25, 0.90);
    System.out.printf("  %-26s %6d %10.1f h %9.1f h %11.1f h %9.3f %9.1f%%%n",
        "referencia: med. projeto", corpoBase.n(), corpoBase.medianTruth(), corpoBase.mae(),
        corpoBase.medianError(), corpoBase.spearman(), 100 * corpoBase.pairwise());
    final var corpoConst = app.pullrequests.model.Breakdown.body(report.constantHours(),
        report.observedHours(), 0.25, 0.90);
    System.out.printf("  %-26s %6d %10.1f h %9.1f h %11.1f h %9.3f %9.1f%%%n",
        "calibracao: constante", corpoConst.n(), corpoConst.medianTruth(), corpoConst.mae(),
        corpoConst.medianError(), corpoConst.spearman(), 100 * corpoConst.pairwise());
    System.out.printf("  (faixa: de %.1f h a %.1f h)%n", corpoBase.lowHours(),
        corpoBase.highHours());

    for (final PullRequestReservedTask.Result r : report.results()) {
      System.out.printf("%n  %s -- erro por faixa de duracao real%n", r.family());
      System.out.printf("    %-20s %6s %14s %12s %14s%n",
          "faixa", "n", "duracao mediana", "erro medio", "erro mediano");
      for (final app.pullrequests.model.Breakdown.Band b
          : app.pullrequests.model.Breakdown.byTruth(r.predictedHours(), report.observedHours())) {
        System.out.printf("    %-20s %6d %13.1f h %10.1f h %12.1f h%n",
            b.label(), b.n(), b.medianTruth(), b.mae(), b.medianError());
      }
    }

    System.out.printf("%n%n  A ORDEM ACIMA SOBREVIVE AO RUIDO?%n");
    System.out.printf("  reamostragem pareada das mesmas %,d linhas reservadas, %d sorteios,"
        + " semente %d.%n", report.reservedRows(), app.pullrequests.model.Bootstrap.DRAWS,
        app.pullrequests.model.Bootstrap.SEED);
    System.out.printf("  intervalo contendo zero = as duas sao indistinguiveis nestas linhas,"
        + " qualquer que seja a ordem da tabela.%n%n");
    System.out.printf("    %-24s %-24s %8s %11s %22s%n",
        "especificacao", "contra", "vence", "dif. media", "intervalo de 95%");
    for (final app.pullrequests.model.Bootstrap.Comparison c : report.comparisons()) {
      System.out.printf("    %-24s %-24s %7.1f%% %+11.2f   [%+7.2f, %+7.2f]  %s%n",
          c.a(), c.b(), 100 * c.winRate(), c.meanGap(), c.lowGap(), c.highGap(),
          c.indistinguishable() ? "EMPATE" : "");
    }

    // Gravado depois de impresso, e nunca em lugar de imprimir: se o banco recusar a escrita, o
    // resultado da leitura unica ja esta na saida e nao se perde por causa de um erro de conexao.
    final String execucao = persist(context, report);
    System.out.printf("%n  gravado em %s, execucao %s%n",
        context.config().prDb(), execucao);

    if (!report.dropped().isEmpty()) {
      System.out.printf("%n  colunas retiradas por serem constantes no treino:%n");
      report.dropped().forEach(line -> System.out.printf("    %s%n", line));
    }
    System.out.printf("%n  o conjunto reservado foi pontuado uma vez, com a especificacao"
        + " congelada antes de ser aberto.%n");
  }

  /** Everything the report printed, as documents, so the database says the same as the page. */
  private static String persist(final Context context, final PullRequestReservedTask.Report report) {
    final Map<String, Object> run = new java.util.LinkedHashMap<>(
        PullRequestReservedTask.frozenSpecification());
    run.put("estagio", "conjunto reservado, leitura unica");
    run.put("linhas_treino", report.trainingRows());
    run.put("linhas_reservadas", report.reservedRows());
    run.put("projetos_sem_treino", report.baselineFellBack());
    run.put("alvo_mediana_horas", report.outcome().medianHours());
    run.put("alvo_media_horas", report.outcome().meanHours());
    run.put("colunas_constantes_retiradas", report.dropped());
    final List<Map<String, Object>> noise = new java.util.ArrayList<>();
    for (final app.pullrequests.model.Bootstrap.Comparison c : report.comparisons()) {
      final Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("a", c.a());
      row.put("b", c.b());
      row.put("fracao_em_que_a_vence", c.winRate());
      row.put("diferenca_media_horas", c.meanGap());
      row.put("intervalo_95_baixo", c.lowGap());
      row.put("intervalo_95_alto", c.highGap());
      row.put("indistinguiveis", c.indistinguishable());
      noise.add(row);
    }
    run.put("teste_de_ruido", noise);
    run.put("teste_de_ruido_sorteios", app.pullrequests.model.Bootstrap.DRAWS);

    final List<Map<String, Object>> rows = new java.util.ArrayList<>();
    rows.add(scoreRow("referencia", report.baseline()));
    rows.add(scoreRow("referencia", report.constant()));
    for (final PullRequestReservedTask.Result result : report.results()) {
      final Map<String, Object> row = scoreRow("especificacao", result.score());
      row.put("familia", result.family().name());
      row.put("alpha", result.alpha());
      row.put("lambda", result.lambda());
      row.put("colunas", result.columns());
      // Os pesos vao inteiros, nao os vinte maiores que a tela mostra: a tela e um resumo e o
      // banco e o registro.
      final List<Map<String, Object>> weights = new java.util.ArrayList<>();
      for (final PullRequestReservedTask.Weight weight : result.weights()) {
        weights.add(Map.of("coluna", weight.column(), "coeficiente", weight.coefficient()));
      }
      row.put("pesos", weights);
      rows.add(row);
    }
    return context.prResults().record(run, rows);
  }

  private static Map<String, Object> scoreRow(final String tipo,
      final app.pullrequests.model.Score score) {

    final Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("tipo", tipo);
    row.put("rotulo", score.label());
    row.put("n", score.n());
    row.put("mae_horas", score.maeHours());
    row.put("mae_dias", score.maeDays());
    row.put("ganho_horas", score.gainHours());
    row.put("ganho_percentual", score.gainPercent());
    row.put("ganho_sobre_constante_percentual", score.gainOverConstantPercent());
    row.put("spearman", score.spearman());
    row.put("acerto_par_a_par", score.pairwise());
    return row;
  }

  private static void print(final app.pullrequests.model.Score score) {
    System.out.printf("  %-44s %6d %9.2f %9.2f %+9.2f %+7.1f%% %+10.1f%% %9.3f %8.1f%%%n",
        score.label(), score.n(), score.maeHours(), score.maeDays(),
        score.gainHours(), score.gainPercent(), score.gainOverConstantPercent(), score.spearman(),
        100 * score.pairwise());
  }
}
