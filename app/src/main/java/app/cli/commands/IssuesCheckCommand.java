package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.pipeline.IssuesEvaluationTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every reading of the plan, on the inner selection of each population, with the parameters the
 * grid wrote to {@code PARAMETROS}. The numbers are optimistic -- the parameters were chosen on
 * this same selection -- and the command says so above every table.
 */
public final class IssuesCheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = IssuesPrinting.cache();
    final String params = System.getenv("PARAMETROS");
    if (params == null || params.isBlank()) {
      throw new CommandFailedException(List.of("  sem parametros: rode issues-grade com "
          + "PARAMETROS=<arquivo> primeiro"));
    }
    final List<IssuesEvaluationTask.Frozen> frozen = parse(params);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : IssuesPrinting.populations()) {
      final IssuesEvaluationTask.Report report = new IssuesEvaluationTask(context.progress())
          .run(entries, frozen, population, Fitting.Mode.INNER);
      System.out.printf("%n  DURACAO DE ISSUES -- SELECAO INTERNA -- %s%n",
          IssuesPrinting.name(population));
      System.out.printf("  parametros: %s (escolhidos NESTA MESMA selecao: numeros otimistas, para "
          + "comparar familias, cenarios e blocos, nao para estimar desempenho)%n", params);
      System.out.printf("  ajuste %,d linhas, pontuadas %,d linhas; o teste externo nao e lido%n",
          report.fitRows(), report.scoredRows());
      rows.addAll(IssuesPrinting.evaluation(report));
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("etapa", "issues-conferencia");
    run.put("parametros", params);
    run.put("teste_externo", "nao lido");
    System.out.printf("%n  execucao registrada em ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /** Lines of {@code populacao<TAB>celula<TAB>alpha<TAB>lambda}, as the grid writes them. */
  static List<IssuesEvaluationTask.Frozen> parse(final String path)
      throws CommandFailedException {
    try {
      final List<IssuesEvaluationTask.Frozen> out = new ArrayList<>();
      for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        final String[] f = line.split("\t");
        Cell.byLabel(f[1]);
        out.add(new IssuesEvaluationTask.Frozen(Population.valueOf(f[0]), f[1],
            Double.parseDouble(f[2]), Double.parseDouble(f[3])));
      }
      return out;
    } catch (final java.io.IOException | RuntimeException e) {
      throw new CommandFailedException(List.of("  PARAMETROS ilegivel: " + e));
    }
  }
}
