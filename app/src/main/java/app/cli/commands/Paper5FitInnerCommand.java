package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper5.RowCache;
import app.pipeline.Paper5FitTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Fits the whole grid against the inner selection of the training side, and prints it.
 *
 * <p><b>There is no {@code paper5-fit}.</b> Not a convention -- there is no command, no task method
 * and no import anywhere in this study that reaches the reserved partition, so no edit to this file
 * can open it by accident. The reserved side will be scored once, deliberately, when the
 * specification is frozen and the author says so.
 *
 * <p>Reads the cache that {@code paper5-extract CACHE=...} wrote, so changing a definition and
 * re-scoring costs seconds instead of re-reading six million file actions.
 *
 * <p>The table reports what section 9.2 allows and nothing else: mean absolute error in hours, the
 * same in days, the gap against the mandatory baseline, and Spearman. No RMSE, no R².
 */
public final class Paper5FitInnerCommand implements CommandRunner {

  /**
   * Held in a field on purpose.
   *
   * <p>Tribuo logs a line per tree at INFO -- three hundred trees, six settings, eleven cells is
   * twenty thousand lines of "Building model 7" around the table that matters. Silencing it needs
   * a <b>strong reference</b> to the logger: {@code java.util.logging} keeps only a weak one, so a
   * level set on a logger nobody holds is collected and the noise comes back, which is exactly what
   * happened the first time this was written as a local call.
   */
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
          "  defina CACHE=<caminho> apontando para o arquivo que paper5-extract gravou"));
    }
    final List<RowCache.Entry> entries;
    try {
      entries = RowCache.read(Path.of(cache));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  nao consegui ler " + cache + ": " + e));
    }

    // Os preditores do Artigo 4 vem de um export de smartshark_paper4.issue_features, que ja os
    // tem calculados. Sem A4=<caminho> a grade roda igual, e a familia da contribuicao propria
    // aparece com as colunas ausentes -- o relatorio diz qual dos dois casos aconteceu, em vez de
    // deixar um resultado vazio parecendo um resultado ruim.
    final String a4 = System.getenv("A4");
    app.paper5.Artigo4Features features = null;
    if (a4 != null && !a4.isBlank()) {
      try {
        features = app.paper5.Artigo4Features.read(Path.of(a4));
      } catch (final IOException e) {
        throw new CommandFailedException(List.of("  nao consegui ler A4=" + a4 + ": " + e));
      }
      System.out.printf("  preditores do Artigo 4: %,d unidades lidas de %s%n",
          features.units(), a4);
    } else {
      System.out.printf("  A4 nao definido: a familia CONTRIBUICAO_PROPRIA roda sem seus dados%n");
    }

    final Paper5FitTask.Report report;
    try {
      report = new Paper5FitTask(context.progress()).run(entries, features);
    } catch (final Paper5FitTask.NothingToFitException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("%n  ARTIGO 5 -- SELECAO INTERNA%n%n");
    for (final Map.Entry<String, String> fact : Paper5FitTask.provenance(
        context.config().minResolvedBeforeCut(), context.config().trainShare()).entrySet()) {

      System.out.printf("  %-22s %s%n", fact.getKey(), fact.getValue());
    }
    System.out.printf("%n  ajustado em %,d linhas, pontuado em %,d%n",
        report.fitRows(), report.selectionRows());
    System.out.printf("  linhas que cairam na mediana global do treino : %,d%n",
        report.baselineFellBack());

    // Without the outcome's own shape a mean absolute error cannot be read. The mean is four times
    // the median here, so an error of two hundred hours is four typical pull requests, not "close".
    final var outcome = report.outcome();
    System.out.printf("%n  O ALVO, no lado pontuado (horas)%n");
    System.out.printf("    n=%,d   mediana=%.1f   media=%.1f   p25=%.1f   p75=%.1f   p90=%.1f"
        + "   maximo=%.0f%n", outcome.n(), outcome.medianHours(), outcome.meanHours(),
        outcome.p25Hours(), outcome.p75Hours(), outcome.p90Hours(), outcome.maxHours());
    System.out.printf("    a media e %.1f vezes a mediana: a distribuicao e assimetrica e o MAE"
        + " e dominado pela cauda.%n", outcome.meanHours() / outcome.medianHours());

    System.out.printf("%n  %-58s %6s %9s %9s %9s %8s %10s %9s%n",
        "familia / perfil | algoritmo", "n", "MAE h", "MAE dias", "ganho h", "ganho %",
        "vs const %", "Spearman");
    print(report.oracleConstant());
    print(report.constant());
    print(report.baseline());
    report.fits().stream()
        .sorted(Comparator.comparingDouble(f -> f.score().maeHours()))
        .forEach(fitted -> {
          print(fitted.score());
          System.out.printf("  %-58s %s   %d de %d colunas%n", "",
              fitted.settings(), fitted.selectedColumns(), fitted.columns().size());
        });

    printContributions(report);

    System.out.printf("%n  cobertura dos tres preditores herdados do Artigo 4"
        + " (ausentes, de %,d no ajuste e %,d na pontuacao):%n",
        report.fitRows(), report.selectionRows());
    report.historyCoverage().forEach(c -> System.out.printf("    %-32s ajuste %5d   pontuacao %5d%n",
        c.column(), c.absentOnFit(), c.absentOnSelection()));

    if (!report.unfitted().isEmpty()) {
      System.out.printf("%n  pontos da grade que a biblioteca nao conseguiu ajustar"
          + " (defeito conhecido do Tribuo 4.3.2, ver ExtraTrees.Settings.grid):%n");
      report.unfitted().forEach(line -> System.out.printf("    %s%n", line));
    }
    if (!report.dropped().isEmpty()) {
      System.out.printf("%n  colunas retiradas por serem constantes no lado do ajuste:%n");
      report.dropped().forEach(line -> System.out.printf("    %s%n", line));
    }
    System.out.printf("%n  esta execucao nao alcancou o conjunto reservado em nenhum caminho:"
        + " as linhas do outro lado do corte externo sao recusadas na entrada da tarefa.%n");
    System.out.printf("  nada foi escrito.%n");
  }

  /**
   * What each column did, cell by cell, for every Elastic Net fit.
   *
   * <p>Printed for every cell rather than for the winner, because choosing which cells to inspect by
   * the score they produced is the same error as choosing an attribute that way. Extra Trees has no
   * coefficients and is left out instead of given an invented number.
   */
  private static void printContributions(final Paper5FitTask.Report report) {
    System.out.printf("%n%n  CONTRIBUICAO POR COLUNA -- Elastic Net, celula por celula%n");
    System.out.printf("  coeficiente: peso na matriz padronizada, comparavel entre colunas."
        + " rho proprio: correlacao de postos da coluna sozinha com o alvo, no lado do ajuste.%n");
    System.out.printf("  uma coluna pode ter rho alto e coeficiente zero porque outra ja a carrega,"
        + " e o contrario tambem acontece.%n");

    for (final Paper5FitTask.Fitted fitted : report.fits()) {
      if (fitted.contributions().isEmpty()) {
        continue;
      }
      System.out.printf("%n  %s  (%s)%n", fitted.score().label(), fitted.settings());
      System.out.printf("    %-42s %12s %12s %s%n",
          "coluna", "coeficiente", "rho proprio", "usada");
      fitted.contributions().stream()
          .sorted(Comparator.comparingDouble((Paper5FitTask.Contribution c)
              -> -Math.abs(c.coefficient())))
          .forEach(c -> System.out.printf("    %-42s %+12.4f %+12.3f %s%n",
              c.column(), c.coefficient(), c.ownSpearman(), c.selected() ? "sim" : "nao"));
    }
  }

  private static void print(final app.paper5.model.Score score) {
    System.out.printf("  %-58s %6d %9.2f %9.2f %+9.2f %+7.1f%% %+9.1f%% %9.3f%n",
        score.label(), score.n(), score.maeHours(), score.maeDays(),
        score.gainHours(), score.gainPercent(), score.gainOverConstantPercent(), score.spearman());
  }
}
