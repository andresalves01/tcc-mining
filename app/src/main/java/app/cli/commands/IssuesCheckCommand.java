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
 * grid wrote to {@code PARAMETERS}. The numbers are optimistic -- the parameters were chosen on
 * this same selection -- and the command says so above every table.
 */
public final class IssuesCheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    check(context, IssuesPrinting.cache(), IssuesPrinting.populations(), "issues-check");
  }

  /** Every reading on the given populations of a cache; the secondary study calls it too. */
  static void check(final Context context, final List<app.issues.RowCache.Entry> entries,
      final java.util.Collection<Population> populations, final String stage)
      throws CommandFailedException {
    final String params = System.getenv("PARAMETERS");
    if (params == null || params.isBlank()) {
      throw new CommandFailedException(List.of("  no parameters: run issues-grid with "
          + "PARAMETERS=<file> first"));
    }
    final List<IssuesEvaluationTask.Frozen> frozen = parse(params);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : populations) {
      final IssuesEvaluationTask.Report report = new IssuesEvaluationTask(context.progress())
          .run(entries, frozen, population, Fitting.Mode.INNER);
      System.out.printf("%n  ISSUE DURATION -- INNER SELECTION -- %s%n",
          IssuesPrinting.name(population));
      System.out.printf("  parameters: %s (chosen ON THIS SAME selection: optimistic numbers, to "
          + "compare families, scenarios and blocks, not to estimate performance)%n", params);
      System.out.printf("  fit %,d rows, scored %,d rows; the outer test is not read%n",
          report.fitRows(), report.scoredRows());
      if (population == Population.IN_PROGRESS) {
        System.out.printf("  main reference by the rule (P12: lowest MAE on the inner selection "
            + "among three): %s (constant %.2f h, project median %.2f h, pair median "
            + "%.2f h)%n", report.main().name().toUpperCase(), report.main().constantMaeHours(),
            report.main().projectMaeHours(), report.main().pairMaeHours());
      }
      rows.addAll(IssuesPrinting.evaluation(report));
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", stage);
    run.put("parameters", params);
    run.put("outer_test", "not read");
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /** Lines of {@code population<TAB>cell<TAB>alpha<TAB>lambda}, as the grid writes them. */
  static List<IssuesEvaluationTask.Frozen> parse(final String path)
      throws CommandFailedException {
    try {
      final List<IssuesEvaluationTask.Frozen> out = new ArrayList<>();
      for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        final String[] f = line.split("\t");
        final String cell = Cell.byLabel(f[1], Population.valueOf(f[0])).label();
        out.add(new IssuesEvaluationTask.Frozen(Population.valueOf(f[0]), cell,
            Double.parseDouble(f[2]), Double.parseDouble(f[3])));
      }
      return out;
    } catch (final java.io.IOException | RuntimeException e) {
      throw new CommandFailedException(List.of("  PARAMETERS unreadable: " + e));
    }
  }
}
