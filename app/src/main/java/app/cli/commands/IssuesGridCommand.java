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
 * chose and what it weighed. Reads the cache; writes {@code PARAMETERS}; records the run in
 * {@code ISSUES_DB}. Never reaches the outer test rows.
 */
public final class IssuesGridCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    sweep(context, IssuesPrinting.cache(), IssuesPrinting.populations(), "issues-grid");
  }

  /** The grid on the given populations of a cache; the secondary study calls it too. */
  static void sweep(final Context context, final List<app.issues.RowCache.Entry> entries,
      final java.util.Collection<Population> populations, final String stage)
      throws CommandFailedException {
    final StringBuilder params = new StringBuilder("# population\tcell\talpha\tlambda -- "
        + "written by issues-grid on the inner selection\n");
    final List<Map<String, Object>> rows = new ArrayList<>();
    final Map<Population, IssuesGridTask.Report> reports =
        new java.util.EnumMap<>(Population.class);
    for (final Population population : populations) {
      final IssuesGridTask.Report report =
          new IssuesGridTask(context.progress()).run(entries, population);
      reports.put(population, report);
      System.out.printf("%n  ISSUE DURATION -- GRID ON THE INNER SELECTION -- %s%n",
          IssuesPrinting.name(population));
      System.out.printf("  inner fit %,d rows, inner selection %,d rows; the outer test "
          + "is not read%n", report.fitRows(), report.selectionRows());
      System.out.printf("  Elastic Net grid: alpha %s x %d lambdas, anchor 2*max|X'r|/alpha%n",
          java.util.Arrays.toString(Fitting.ALPHAS), Fitting.LAMBDA_STEPS);
      IssuesPrinting.outcome(report.outcome());
      System.out.println();
      IssuesPrinting.header();
      IssuesPrinting.row(report.project());
      IssuesPrinting.row(report.constant());
      rows.add(IssuesPrinting.scoreRow(population, "reference", report.project()));
      rows.add(IssuesPrinting.scoreRow(population, "reference", report.constant()));
      for (final IssuesGridTask.Timed t : report.fits()) {
        IssuesPrinting.row(t.fitted().score());
        final Fitting.Fitted f = t.fitted();
        params.append(population.name()).append('\t').append(f.cell().label()).append('\t')
            .append(f.alpha()).append('\t').append(f.lambda()).append('\n');
        final Map<String, Object> row = IssuesPrinting.scoreRow(population, "cell", f.score());
        row.put("alpha", f.alpha());
        row.put("lambda", f.lambda());
        row.put("step", f.step());
        row.put("columns", f.columns());
        row.put("seconds", t.millis() / 1000.0);
        rows.add(row);
      }
      System.out.printf("%n  CHOSEN PARAMETERS%n");
      for (final IssuesGridTask.Timed t : report.fits()) {
        final Fitting.Fitted f = t.fitted();
        final double[] spread = f.spread();
        System.out.printf("    %-32s alpha %.2f  lambda %-12.6g step %2d of %d%s; predictions "
            + "p10/p50/p90 %.0f/%.0f/%.0f h; %d columns, %.0f s%n", f.cell().label(), f.alpha(),
            f.lambda(), f.step(), Fitting.LAMBDA_STEPS,
            f.step() == 0 ? " = TOP, ALL ZEROED" : f.step() == Fitting.LAMBDA_STEPS - 1
                ? " = GRID FLOOR" : "", spread[0], spread[1], spread[2], f.columns().size(),
            t.millis() / 1000.0);
      }
    }
    System.out.printf("%n  WHOLE GRID: MAE (h) on the inner selection of each alpha at each step "
        + "(0 = top, constant model), and the distance from each alpha's best to the chosen one%n");
    for (final Population population : populations) {
      final IssuesGridTask.Report report = reports.get(population);
      System.out.printf("%n  -- %s%n", IssuesPrinting.name(population));
      for (final IssuesGridTask.Timed t : report.fits()) {
        final Fitting.Fitted f = t.fitted();
        System.out.printf("    %s (chosen: alpha %.2f, step %d, MAE %.2f h)%n",
            f.cell().label(), f.alpha(), f.step(), f.score().mae());
        for (final double alpha : Fitting.ALPHAS) {
          final StringBuilder line = new StringBuilder(String.format("      alpha %.2f:", alpha));
          double best = Double.POSITIVE_INFINITY;
          for (final Fitting.Trial trial : f.trials()) {
            if (trial.alpha() == alpha) {
              line.append(String.format(" %7.1f", trial.mae()));
              best = Math.min(best, trial.mae());
            }
          }
          line.append(String.format("   best %+6.2f h from the chosen",
              best - f.score().mae()));
          System.out.println(line);
        }
      }
    }

    final String out = System.getenv("PARAMETERS");
    if (out != null && !out.isBlank()) {
      try {
        java.nio.file.Files.writeString(java.nio.file.Path.of(out), params.toString());
        System.out.printf("%n  parameters written to %s%n", out);
      } catch (final java.io.IOException e) {
        throw new CommandFailedException(List.of("  could not write " + out + ": " + e));
      }
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", stage);
    run.put("outer_test", "not read");
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }
}
