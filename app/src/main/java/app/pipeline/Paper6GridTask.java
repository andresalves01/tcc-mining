package app.pipeline;

import app.paper5.model.Score;
import app.paper6.RowCache;
import app.paper6.model.Cell;
import app.paper6.model.Fitting;
import app.paper6.model.References;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.List;

/**
 * Sweeps every cell of the grid against the inner selection. The development stage.
 *
 * <p>There is no code path from here to the reserved rows: {@link Fitting#sides} with
 * {@link Fitting.Mode#INNER} refuses them. What comes out is one (alpha, lambda) per cell, which the
 * registration then copies as literals -- this stage chooses, the reserved stage only obeys.
 */
public final class Paper6GridTask {

  public record Timed(Fitting.Fitted fitted, long millis) {}

  public record Report(Score project, Score constant, Score pair, List<Timed> fits,
      List<String> skipped, int fitRows, int selectionRows, Score.Outcome outcome) {}

  private final Progress progress;

  public Paper6GridTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries) {
    final Fitting.Sides sides = Fitting.sides(entries, Fitting.Mode.INNER);
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] observed = sides.observed();
    final double constantMae = Fitting.mae(refs.constant(observed.length), observed);
    final Score project = Score.reference("referencia obrigatoria: mediana do projeto",
        refs.projectHours(sides.scoreUnits()), observed, constantMae);
    final Score constant = Score.reference(String.format("referencia: constante = mediana do "
        + "ajuste (%.1f h)", refs.constantHours()), refs.constant(observed.length), observed,
        constantMae);
    final Score pair = Score.reference("referencia condicionada: mediana do par autor-projeto",
        refs.pairHours(sides.scoreUnits()), observed, constantMae);

    final List<String> skipped = new ArrayList<>();
    final List<Cell> cells = Cell.grid(skipped);
    final List<Timed> fits = new ArrayList<>();
    int done = 0;
    for (final Cell cell : cells) {
      progress.item(++done, cells.size(), cell.label(), "varrendo");
      final long start = System.currentTimeMillis();
      final Fitting.Fitted fitted =
          Fitting.sweep(cell, sides, project.maeHours(), constantMae);
      fits.add(new Timed(fitted, System.currentTimeMillis() - start));
      progress.detail("%s: MAE %.2f h, alpha %.2f, lambda %.4g, %.0f s", cell.label(),
          fitted.score().maeHours(), fitted.alpha(), fitted.lambda(),
          (System.currentTimeMillis() - start) / 1000.0);
    }
    return new Report(project, constant, pair, List.copyOf(fits), List.copyOf(skipped),
        sides.fitUnits().size(), sides.scoreUnits().size(), Score.Outcome.of(observed));
  }
}
