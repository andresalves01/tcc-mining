package app.pipeline;

import app.study.model.Score;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.References;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.List;

/**
 * Sweeps every cell of the grid against the inner selection of one population. The development
 * stage.
 *
 * <p>There is no code path from here to the outer test rows: {@link Fitting#sides} with
 * {@link Fitting.Mode#INNER} refuses them. What comes out is one (alpha, lambda) per cell.
 */
public final class IssuesGridTask {

  public record Timed(Fitting.Fitted fitted, long millis) {}

  public record Report(Population population, Score project, Score constant, List<Timed> fits,
      int fitRows, int selectionRows, Score.Outcome outcome) {}

  private final Progress progress;

  public IssuesGridTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final Population population) {
    final Fitting.Sides sides = Fitting.sides(entries, Fitting.Mode.INNER, population);
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] observed = sides.observed();
    final double constantMae = Fitting.mae(refs.constant(observed.length), observed);
    final Score project = Score.reference("reference: project median",
        refs.projectMedian(sides.scoreUnits()), observed, constantMae);
    final Score constant = Score.reference(String.format("reference: constant = fit "
        + "median (%.1f h)", refs.constantMedian()), refs.constant(observed.length), observed,
        constantMae);
    final List<Cell> cells = Cell.grid(population);
    final List<Timed> fits = new ArrayList<>();
    int done = 0;
    for (final Cell cell : cells) {
      progress.item(++done, cells.size(), population + " " + cell.label(), "sweeping");
      final long start = System.currentTimeMillis();
      final Fitting.Fitted fitted = Fitting.sweep(cell, sides, project.mae(), constantMae);
      fits.add(new Timed(fitted, System.currentTimeMillis() - start));
      progress.detail("%s: MAE %.2f h, alpha %.2f, lambda %.4g, step %d, %.0f s", cell.label(),
          fitted.score().mae(), fitted.alpha(), fitted.lambda(), fitted.step(),
          (System.currentTimeMillis() - start) / 1000.0);
    }
    return new Report(population, project, constant, List.copyOf(fits), sides.fitUnits().size(),
        sides.scoreUnits().size(), Score.Outcome.of(observed));
  }
}
