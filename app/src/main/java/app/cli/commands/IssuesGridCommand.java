package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.pipeline.IssuesGridTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps the grid on the inner selection of each population and prints the parameters each cell
 * chose and what it weighed. Reads the cache; writes {@code PARAMETROS}; records the run in
 * {@code ISSUES_DB}. Never reaches the outer test rows.
 */
public final class IssuesGridCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    sweep(context, IssuesPrinting.cache(), IssuesPrinting.populations(), "issues-grade");
  }

  /** The grid on the given populations of a cache; the secondary study calls it too. */
  static void sweep(final Context context, final List<app.issues.RowCache.Entry> entries,
      final java.util.Collection<Population> populations, final String stage)
      throws CommandFailedException {
    final StringBuilder params = new StringBuilder("# populacao\tcelula\talpha\tlambda -- "
        + "escritos por issues-grade na selecao interna\n");
    final List<Map<String, Object>> rows = new ArrayList<>();
    final Map<Population, IssuesGridTask.Report> reports =
        new java.util.EnumMap<>(Population.class);
    for (final Population population : populations) {
      final IssuesGridTask.Report report =
          new IssuesGridTask(context.progress()).run(entries, population);
      reports.put(population, report);
      System.out.printf("%n  DURACAO DE ISSUES -- GRADE NA SELECAO INTERNA -- %s%n",
          IssuesPrinting.name(population));
      System.out.printf("  ajuste interno %,d linhas, selecao interna %,d linhas; o teste externo "
          + "nao e lido%n", report.fitRows(), report.selectionRows());
      System.out.printf("  grade Elastic Net: alpha %s x %d lambdas, ancora 2*max|X'r|/alpha%n",
          java.util.Arrays.toString(Fitting.ALPHAS), Fitting.LAMBDA_STEPS);
      IssuesPrinting.outcome(report.outcome());
      System.out.println();
      IssuesPrinting.header();
      IssuesPrinting.row(report.project());
      IssuesPrinting.row(report.constant());
      rows.add(IssuesPrinting.scoreRow(population, "referencia", report.project()));
      rows.add(IssuesPrinting.scoreRow(population, "referencia", report.constant()));
      for (final IssuesGridTask.Timed t : report.fits()) {
        IssuesPrinting.row(t.fitted().score());
        final Fitting.Fitted f = t.fitted();
        params.append(population.name()).append('\t').append(f.cell().label()).append('\t')
            .append(f.alpha()).append('\t').append(f.lambda()).append('\n');
        final Map<String, Object> row = IssuesPrinting.scoreRow(population, "celula", f.score());
        row.put("alpha", f.alpha());
        row.put("lambda", f.lambda());
        row.put("degrau", f.step());
        row.put("colunas", f.columns());
        row.put("segundos", t.millis() / 1000.0);
        rows.add(row);
      }
      System.out.printf("%n  PARAMETROS ESCOLHIDOS%n");
      for (final IssuesGridTask.Timed t : report.fits()) {
        final Fitting.Fitted f = t.fitted();
        final double[] spread = f.spread();
        System.out.printf("    %-32s alpha %.2f  lambda %-12.6g degrau %2d de %d%s; previsoes "
            + "p10/p50/p90 %.0f/%.0f/%.0f h; %d colunas, %.0f s%n", f.cell().label(), f.alpha(),
            f.lambda(), f.step(), Fitting.LAMBDA_STEPS,
            f.step() == 0 ? " = TOPO, TUDO ZERADO" : f.step() == Fitting.LAMBDA_STEPS - 1
                ? " = PISO DA GRADE" : "", spread[0], spread[1], spread[2], f.columns().size(),
            t.millis() / 1000.0);
      }
    }
    System.out.printf("%n  GRADE INTEIRA: MAE (h) na selecao interna de cada alpha em cada degrau "
        + "(0 = topo, modelo constante), e a distancia do melhor de cada alpha ao escolhido%n");
    for (final Population population : populations) {
      final IssuesGridTask.Report report = reports.get(population);
      System.out.printf("%n  -- %s%n", IssuesPrinting.name(population));
      for (final IssuesGridTask.Timed t : report.fits()) {
        final Fitting.Fitted f = t.fitted();
        System.out.printf("    %s (escolhido: alpha %.2f, degrau %d, MAE %.2f h)%n",
            f.cell().label(), f.alpha(), f.step(), f.score().maeHours());
        for (final double alpha : Fitting.ALPHAS) {
          final StringBuilder line = new StringBuilder(String.format("      alpha %.2f:", alpha));
          double best = Double.POSITIVE_INFINITY;
          for (final Fitting.Trial trial : f.trials()) {
            if (trial.alpha() == alpha) {
              line.append(String.format(" %7.1f", trial.maeHours()));
              best = Math.min(best, trial.maeHours());
            }
          }
          line.append(String.format("   melhor %+6.2f h do escolhido",
              best - f.score().maeHours()));
          System.out.println(line);
        }
      }
    }

    final String out = System.getenv("PARAMETROS");
    if (out != null && !out.isBlank()) {
      try {
        java.nio.file.Files.writeString(java.nio.file.Path.of(out), params.toString());
        System.out.printf("%n  parametros gravados em %s%n", out);
      } catch (final java.io.IOException e) {
        throw new CommandFailedException(List.of("  nao consegui gravar " + out + ": " + e));
      }
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", stage);
    run.put("teste_externo", "nao lido");
    System.out.printf("%n  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }
}
