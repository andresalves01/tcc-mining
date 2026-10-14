package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import app.issues.model.Forest;
import app.pipeline.IssuesEvaluationTask;
import app.pipeline.IssuesForestTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The random forest beside the Elastic Net, on the inner selection only (asked on 29/09/2026,
 * after the reserved run). Reads CACHE and the linear model's frozen PARAMETERS; POPULATIONS as the
 * other commands. Records the run in ISSUES_DB. The outer test is not read.
 */
public final class IssuesForestCommand implements CommandRunner {

  private static final int TOP = 10;

  @Override
  public void run(final Context context) throws CommandFailedException {
    final var entries = IssuesPrinting.cache();
    final String params = System.getenv("PARAMETERS");
    if (params == null || params.isBlank()) {
      throw new CommandFailedException(List.of("  PARAMETERS=<the grid's file> is required"));
    }
    final List<IssuesEvaluationTask.Frozen> frozen = IssuesCheckCommand.parse(params);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Population population : IssuesPrinting.populations()) {
      final IssuesForestTask.Report r =
          new IssuesForestTask(context.progress()).run(entries, frozen, population);
      print(r, params);
      rows.addAll(rows(r));
    }
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-forest");
    run.put("elastic_net_parameters", params);
    run.put("outer_test", "not read");
    run.put("forest_grid", Map.of("trees", Forest.TREES, "depth", Forest.MAX_DEPTH,
        "mtry_fractions", List.of(1.0 / 6, 1.0 / 3, 2.0 / 3), "min_leaf",
        List.of(5, 25, 100), "seed", Forest.SEED));
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  private static void print(final IssuesForestTask.Report r, final String params) {
    System.out.printf("%n  ISSUE DURATION -- RANDOM FOREST BESIDE THE ELASTIC NET -- INNER "
        + "SELECTION -- %s%n", IssuesPrinting.name(r.population()));
    System.out.printf("  Elastic Net with the parameters from %s; forest with the declared grid: %d "
        + "trees, depth up to %d, columns per split 1/6, 1/3 and 2/3 of the cell's, minimum "
        + "leaf 5, 25 and 100, seed %d%n", params, Forest.TREES, Forest.MAX_DEPTH,
        Forest.SEED);
    System.out.printf("  both chose parameters ON THIS SAME selection: optimistic numbers for "
        + "both, to compare estimators; the outer test is not read%n");
    System.out.printf("  fit %,d rows, scored %,d rows%n", r.fitRows(), r.scoredRows());

    System.out.printf("%n  MAIN TABLE (gain%% against the project median; vs const%% against the "
        + "constant; pairwise in %%)%n");
    IssuesPrinting.header();
    IssuesPrinting.row(r.project());
    IssuesPrinting.row(r.constant());
    for (final var c : r.cells()) {
      IssuesPrinting.row(c.linear().score());
      IssuesPrinting.row(c.forest().score());
    }

    System.out.printf("%n  GAIN WITH A 95%% INTERVAL RESAMPLING WHOLE PROJECTS (%d draws)%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    System.out.printf("  %-44s %24s %24s %24s%n", "cell", "forest over project median",
        "forest over constant", "Elastic Net over const");
    for (final var c : r.cells()) {
      System.out.printf("  %-44s %24s %24s %24s%n", c.linear().cell().label(),
          IssuesPrinting.interval(c.forestOverProject()),
          IssuesPrinting.interval(c.forestOverConstant()),
          IssuesPrinting.interval(c.linearOverConstant()));
    }

    System.out.printf("%n  FOREST AGAINST ELASTIC NET, SAME CELL: paired resampling of "
        + "PROJECTS, %d draws (interval containing zero = tie); diff. = MAE(forest) - "
        + "MAE(Elastic Net), negative when the forest errs less%n",
        app.issues.model.ProjectBootstrap.DRAWS);
    for (final var c : r.cells()) {
      final var g = c.forestMinusLinear();
      System.out.printf("    %-30s diff. %+9.2f h  CI [%+9.2f, %+9.2f]  %s%n",
          c.linear().cell().label(), g.gapHours(), g.low(), g.high(),
          g.tied() ? "TIE" : "distinguishable");
    }

    System.out.printf("%n  FOREST GRID: MAE (h) on the inner selection by columns per split x "
        + "minimum leaf; * = chosen%n");
    for (final var c : r.cells()) {
      final var f = c.forest();
      final StringBuilder line = new StringBuilder();
      for (final Forest.Trial t : f.trials()) {
        final boolean chosen = t.mtry() == f.mtry() && t.nodeSize() == f.nodeSize();
        line.append(String.format("  %d/%d %.2f%s", t.mtry(), t.nodeSize(), t.maeHours(),
            chosen ? "*" : ""));
      }
      System.out.printf("    %-26s %d columns:%s%n", f.cell().label(), f.columns().size(), line);
    }

    System.out.printf("%n  IMPORTANCE IN THE CHOSEN FOREST (drop in squared error summed over the "
        + "splits, in %% of the total; describes the fit, is not a causal effect), the %d largest%n", TOP);
    for (final var c : r.cells()) {
      final var f = c.forest();
      double total = 0;
      for (final double v : f.importance()) {
        total += v;
      }
      final List<Integer> order = new ArrayList<>();
      for (int i = 0; i < f.importance().length; i++) {
        order.add(i);
      }
      order.sort(Comparator.comparingDouble((Integer i) -> -f.importance()[i]));
      System.out.printf("  -- %s  (columns per split %d, minimum leaf %d)%n", f.cell().label(),
          f.mtry(), f.nodeSize());
      for (final int i : order.subList(0, Math.min(TOP, order.size()))) {
        System.out.printf("     %-46s %5.1f%%%n", f.columns().get(i),
            total == 0 ? 0 : 100 * f.importance()[i] / total);
      }
    }
  }

  private static List<Map<String, Object>> rows(final IssuesForestTask.Report r) {
    final List<Map<String, Object>> out = new ArrayList<>();
    out.add(IssuesPrinting.scoreRow(r.population(), "reference", r.project()));
    out.add(IssuesPrinting.scoreRow(r.population(), "reference", r.constant()));
    for (final var c : r.cells()) {
      out.add(IssuesPrinting.scoreRow(r.population(), "elastic net", c.linear().score()));
      final Map<String, Object> m =
          IssuesPrinting.scoreRow(r.population(), "forest", c.forest().score());
      m.put("mtry", c.forest().mtry());
      m.put("min_leaf", c.forest().nodeSize());
      m.put("ci_gain_project", List.of(c.forestOverProject().low(),
          c.forestOverProject().high()));
      m.put("ci_gain_constant", List.of(c.forestOverConstant().low(),
          c.forestOverConstant().high()));
      m.put("mae_diff_forest_minus_elastic_net", c.forestMinusLinear().gapHours());
      m.put("ci_projects", List.of(c.forestMinusLinear().low(), c.forestMinusLinear().high()));
      out.add(m);
    }
    return out;
  }
}
