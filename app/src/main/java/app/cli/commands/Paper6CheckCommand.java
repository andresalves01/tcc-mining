package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.paper6.Universe.Population;
import app.paper6.model.Cell;
import app.paper6.model.Fitting;
import app.pipeline.Paper6EvaluationTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every reading of the plan, on the inner selection of each population, with the parameters the
 * grid wrote to {@code PARAMETROS}. The numbers are optimistic -- the parameters were chosen on
 * this same selection -- and the command says so above every table.
 */
public final class Paper6CheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = Paper6Printing.cache();
    final String params = System.getenv("PARAMETROS");
    if (params == null || params.isBlank()) {
      throw new CommandFailedException(List.of("  sem parametros: rode paper6-grade com "
          + "PARAMETROS=<arquivo> primeiro"));
    }
    final List<Paper6EvaluationTask.Frozen> frozen = parse(params);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : Paper6Printing.populations()) {
      final Paper6EvaluationTask.Report report = new Paper6EvaluationTask(context.progress())
          .run(entries, frozen, population, Fitting.Mode.INNER);
      System.out.printf("%n  DURACAO DE ISSUES -- SELECAO INTERNA -- %s%n",
          Paper6Printing.name(population));
      System.out.printf("  parametros: %s (escolhidos NESTA MESMA selecao: numeros otimistas, para "
          + "comparar familias, cenarios e blocos, nao para estimar desempenho)%n", params);
      System.out.printf("  ajuste %,d linhas, pontuadas %,d linhas; o teste externo nao e lido%n",
          report.fitRows(), report.scoredRows());
      rows.addAll(Paper6Printing.evaluation(report));
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "paper6-conferencia");
    run.put("parametros", params);
    run.put("teste_externo", "nao lido");
    System.out.printf("%n  execucao registrada em PAPER6_DB: %s%n",
        context.paper6Results().record(run, rows));
  }

  /** Lines of {@code populacao<TAB>celula<TAB>alpha<TAB>lambda}, as the grid writes them. */
  static List<Paper6EvaluationTask.Frozen> parse(final String path)
      throws CommandFailedException {
    try {
      final List<Paper6EvaluationTask.Frozen> out = new ArrayList<>();
      for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        final String[] f = line.split("\t");
        Cell.byLabel(f[1]);
        out.add(new Paper6EvaluationTask.Frozen(Population.valueOf(f[0]), f[1],
            Double.parseDouble(f[2]), Double.parseDouble(f[3])));
      }
      return out;
    } catch (final java.io.IOException | RuntimeException e) {
      throw new CommandFailedException(List.of("  PARAMETROS ilegivel: " + e));
    }
  }
}
