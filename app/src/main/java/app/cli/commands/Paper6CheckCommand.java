package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.model.Cell;
import app.paper6.model.Fitting;
import app.pipeline.Paper6EvaluationTask;
import app.pipeline.Paper6Registration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every reading the registration names, on the inner selection, with frozen parameters.
 *
 * <p>Before the registration exists it reads the parameters the grid wrote to {@code PARAMETROS},
 * so the whole report can be read and checked before anything is frozen -- which is the point of
 * running it: every table the reserved run will print is printed here first, on rows that are not
 * the reserved ones, so a defect in the report is found while it still costs nothing.
 */
public final class Paper6CheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = Paper6Printing.cache();
    List<Paper6EvaluationTask.Frozen> frozen = Paper6Registration.FROZEN;
    String origin = "literais do registro previo";
    final String params = System.getenv("PARAMETROS");
    if (frozen.isEmpty() && params != null && !params.isBlank()) {
      frozen = parse(params);
      origin = "arquivo PARAMETROS (antes do registro): " + params;
    } else if (frozen.isEmpty()) {
      throw new CommandFailedException(List.of("  sem parametros: rode paper6-grade com "
          + "PARAMETROS=<arquivo> primeiro, ou congele-os no registro"));
    }
    final Paper6EvaluationTask.Report report = new Paper6EvaluationTask(context.progress())
        .run(entries, frozen, Fitting.Mode.INNER);

    System.out.printf("%n  TRANSPOSICAO PARA ISSUES -- CONFERENCIA NA SELECAO INTERNA%n");
    System.out.printf("  parametros: %s%n", origin);
    System.out.printf("  ajuste %,d linhas, pontuadas %,d linhas; o reservado nao e lido%n",
        report.fitRows(), report.scoredRows());
    final List<Map<String, Object>> rows = Paper6Printing.evaluation(report);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-conferencia");
    run.put("parametros", origin);
    run.put("reservado", "nao lido");
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }

  /** Lines of {@code celula<TAB>alpha<TAB>lambda}, as the grid prints them. */
  static List<Paper6EvaluationTask.Frozen> parse(final String path) throws CommandFailedException {
    try {
      final List<Paper6EvaluationTask.Frozen> out = new ArrayList<>();
      for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        final String[] f = line.split("\t");
        Cell.byLabel(f[0]);
        out.add(new Paper6EvaluationTask.Frozen(f[0], Double.parseDouble(f[1]),
            Double.parseDouble(f[2])));
      }
      return out;
    } catch (final java.io.IOException | RuntimeException e) {
      throw new CommandFailedException(List.of("  PARAMETROS ilegivel: " + e));
    }
  }
}
