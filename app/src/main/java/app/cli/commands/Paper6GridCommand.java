package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.Universe.Population;
import app.paper6.model.Fitting;
import app.pipeline.Paper6GridTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps the grid on the inner selection of each population and prints the parameters each cell
 * chose and what it weighed. Reads the cache; writes {@code PARAMETROS}; records the run in
 * {@code PAPER6_DB}. Never reaches the outer test rows.
 */
public final class Paper6GridCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = Paper6Printing.cache();
    final StringBuilder params = new StringBuilder("# populacao\tcelula\talpha\tlambda -- "
        + "escritos por paper6-grade na selecao interna\n");
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : Paper6Printing.populations()) {
      final Paper6GridTask.Report report =
          new Paper6GridTask(context.progress()).run(entries, population);
      System.out.printf("%n  DURACAO DE ISSUES -- GRADE NA SELECAO INTERNA -- %s%n",
          Paper6Printing.name(population));
      System.out.printf("  ajuste interno %,d linhas, selecao interna %,d linhas; o teste externo "
          + "nao e lido%n", report.fitRows(), report.selectionRows());
      System.out.printf("  grade Elastic Net: alpha %s x %d lambdas, ancora 2*max|X'r|/alpha%n",
          java.util.Arrays.toString(Fitting.ALPHAS), Fitting.LAMBDA_STEPS);
      Paper6Printing.outcome(report.outcome());
      System.out.println();
      Paper6Printing.header();
      Paper6Printing.row(report.project());
      Paper6Printing.row(report.constant());
      rows.add(Paper6Printing.scoreRow(population, "referencia", report.project()));
      rows.add(Paper6Printing.scoreRow(population, "referencia", report.constant()));
      for (final Paper6GridTask.Timed t : report.fits()) {
        Paper6Printing.row(t.fitted().score());
        final Fitting.Fitted f = t.fitted();
        params.append(population.name()).append('\t').append(f.cell().label()).append('\t')
            .append(f.alpha()).append('\t').append(f.lambda()).append('\n');
        final Map<String, Object> row = Paper6Printing.scoreRow(population, "celula", f.score());
        row.put("alpha", f.alpha());
        row.put("lambda", f.lambda());
        row.put("degrau", f.step());
        row.put("colunas", f.columns());
        row.put("segundos", t.millis() / 1000.0);
        rows.add(row);
      }
      System.out.printf("%n  PARAMETROS ESCOLHIDOS%n");
      for (final Paper6GridTask.Timed t : report.fits()) {
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
    run.put("etapa", "paper6-grade");
    run.put("teste_externo", "nao lido");
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }
}
