package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.model.Fitting;
import app.pipeline.Paper6GridTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps the grid on the inner selection and prints, per cell, the parameters it chose and what the
 * cell weighed. Reads the cache; records the run in {@code PAPER6_DB}. Never reaches the reserved
 * rows.
 */
public final class Paper6GridCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Paper6GridTask.Report report =
        new Paper6GridTask(context.progress()).run(Paper6Printing.cache());

    System.out.printf("%n  TRANSPOSICAO PARA ISSUES -- GRADE NA SELECAO INTERNA%n");
    System.out.printf("  ajuste interno %,d linhas, selecao interna %,d linhas; o reservado nao e "
        + "lido%n", report.fitRows(), report.selectionRows());
    System.out.printf("  grade Elastic Net: alpha %s x %d lambdas, semente %d%n",
        java.util.Arrays.toString(Fitting.ALPHAS), Fitting.LAMBDA_STEPS, Fitting.SEED);
    Paper6Printing.outcome(report.outcome());
    System.out.println();
    Paper6Printing.header();
    Paper6Printing.row(report.project());
    Paper6Printing.row(report.constant());
    Paper6Printing.row(report.pair());
    for (final Paper6GridTask.Timed t : report.fits()) {
      Paper6Printing.row(t.fitted().score());
    }
    System.out.printf("%n  celulas nao ajustadas:%n");
    report.skipped().forEach(s -> System.out.println("    " + s));

    System.out.printf("%n  PARAMETROS ESCOLHIDOS (copiar como literais para o registro)%n");
    for (final Paper6GridTask.Timed t : report.fits()) {
      final Fitting.Fitted f = t.fitted();
      System.out.printf("    new Frozen(\"%s\", %.2f, %s),   // %d colunas, %d selecionadas, %.0f s%n",
          f.cell().label(), f.alpha(), Double.toString(f.lambda()), f.columns().size(),
          f.selected(), t.millis() / 1000.0);
    }

    System.out.printf("%n  PESO DE CADA COLUNA (coeficiente padronizado | rho proprio no ajuste)%n");
    for (final Paper6GridTask.Timed t : report.fits()) {
      final Fitting.Fitted f = t.fitted();
      System.out.printf("  -- %s%n", f.cell().label());
      if (!f.dropped().isEmpty()) {
        System.out.printf("     retiradas por serem constantes: %s%n", f.dropped());
      }
      f.contributions().stream()
          .sorted((a, b) -> Double.compare(Math.abs(b.coefficient()), Math.abs(a.coefficient())))
          .forEach(c -> System.out.printf("     %-40s %+9.4f | %+.3f%s%n", c.column(),
              c.coefficient(), c.ownSpearman(), c.selected() ? "" : "   (zerada)"));
    }

    final String params = System.getenv("PARAMETROS");
    if (params != null && !params.isBlank()) {
      final StringBuilder out = new StringBuilder("# celula\talpha\tlambda -- escritos por "
          + "paper6-grade, lidos por paper6-conferencia antes do registro\n");
      for (final Paper6GridTask.Timed t : report.fits()) {
        out.append(t.fitted().cell().label()).append('\t').append(t.fitted().alpha())
            .append('\t').append(t.fitted().lambda()).append('\n');
      }
      try {
        java.nio.file.Files.writeString(java.nio.file.Path.of(params), out.toString());
        System.out.printf("%n  parametros gravados em %s%n", params);
      } catch (final java.io.IOException e) {
        throw new CommandFailedException(List.of("  nao consegui gravar " + params + ": " + e));
      }
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-grade");
    run.put("linhas_ajuste", report.fitRows());
    run.put("linhas_selecao", report.selectionRows());
    run.put("reservado", "nao lido");
    final List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(Paper6Printing.scoreRow("referencia", report.project()));
    rows.add(Paper6Printing.scoreRow("referencia", report.constant()));
    rows.add(Paper6Printing.scoreRow("referencia", report.pair()));
    for (final Paper6GridTask.Timed t : report.fits()) {
      final Map<String, Object> row = Paper6Printing.scoreRow("celula", t.fitted().score());
      row.put("alpha", t.fitted().alpha());
      row.put("lambda", t.fitted().lambda());
      row.put("colunas", t.fitted().columns());
      row.put("segundos", t.millis() / 1000.0);
      rows.add(row);
    }
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }
}
