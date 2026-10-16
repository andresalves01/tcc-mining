package app.pipeline;

import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.study.model.Score;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.List;

/**
 * The random forest beside the Elastic Net, cell by cell, on the inner selection of one population
 * (asked on 29/09/2026, after the reserved run). The linear model is refitted with the parameters
 * the grid froze; the forest sweeps its own declared grid ({@link Forest}). Both are read on the
 * same scored rows, against the same references learned on the same fitting side. Optimistic for
 * both, by the same construction: each chose its parameters on these rows. The outer test is never
 * read -- {@link Fitting.Mode#INNER} refuses it.
 */
public final class IssuesForestTask {

  /** One cell: the linear model, the forest, and the forest against the linear model. */
  public record Pair(Fitting.Fitted linear, Forest.Fitted forest,
      ProjectBootstrap.Interval forestOverProject, ProjectBootstrap.Interval forestOverConstant,
      ProjectBootstrap.Interval linearOverConstant, ProjectBootstrap.Gap forestMinusLinear) {}

  public record Report(Population population, int fitRows, int scoredRows, Score project,
      Score constant, List<Pair> cells) {}

  private final Progress progress;

  public IssuesForestTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries,
      final List<IssuesEvaluationTask.Frozen> frozen, final Population population) {
    final Fitting.Sides sides = Fitting.sides(entries, Fitting.Mode.INNER, population);
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] projectHours = refs.projectMedian(sides.scoreUnits());
    final double[] constantHours = refs.constant(observed.length);
    final double projectMae = Fitting.mae(projectHours, observed);
    final double constantMae = Fitting.mae(constantHours, observed);

    final List<IssuesEvaluationTask.Frozen> mine =
        frozen.stream().filter(f -> f.population() == population).toList();
    final List<Pair> cells = new ArrayList<>();
    int done = 0;
    for (final IssuesEvaluationTask.Frozen f : mine) {
      progress.item(++done, mine.size(), population + " " + f.cell(), "inner selection");
      final Cell cell = Cell.byLabel(f.cell());
      final Fitting.Fitted linear = Fitting.frozen(cell, sides, f.alpha(), f.lambda(),
          projectMae, constantMae);
      final Forest.Fitted forest = Forest.sweep(cell, sides, projectMae, constantMae);
      cells.add(new Pair(linear, forest,
          ProjectBootstrap.gain(projects, forest.predicted(), projectHours, observed),
          ProjectBootstrap.gain(projects, forest.predicted(), constantHours, observed),
          ProjectBootstrap.gain(projects, linear.predicted(), constantHours, observed),
          ProjectBootstrap.compare(projects, forest.score().label(),
              errors(forest.predicted(), observed), cell.label(),
              errors(linear.predicted(), observed))));
    }
    return new Report(population, sides.fitUnits().size(), sides.scoreUnits().size(),
        Score.reference("reference: project median", projectHours, observed, constantMae),
        Score.reference(String.format("reference: constant = fit median (%.1f h)",
            refs.constantMedian()), constantHours, observed, constantMae),
        List.copyOf(cells));
  }

  static double[] errors(final double[] predicted, final double[] observed) {
    final double[] out = new double[observed.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = Math.abs(predicted[i] - observed[i]);
    }
    return out;
  }
}
