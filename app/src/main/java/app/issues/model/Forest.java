package app.issues.model;

import app.model.RandomForestModel;
import app.study.model.DesignMatrix;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.List;

/**
 * The random forest beside the Elastic Net, on the inner selection only (asked on 29/09/2026,
 * after the reserved run: a comparison of estimators, not a new registered test).
 *
 * <p>Everything the linear model gets, this gets: the same cell, the same fitting and scored sides,
 * the same design matrix learned on the fitting side, the target {@code log1p} of hours and the
 * prediction back through {@code expm1}, held at zero. The grid below was declared before the
 * forest ran; the point with the smallest mean absolute error on the scored side is kept, as the
 * linear sweep keeps its own.
 */
public final class Forest {

  /** Trees per forest, fixed rather than swept: more trees only steady the average. */
  public static final int TREES = 300;

  /** The library's default depth limit, kept. */
  public static final int MAX_DEPTH = 20;

  /** Columns drawn at each split, as a share of the cell's columns: a third is the usual default. */
  public static final double[] MTRY_SHARES = {1.0 / 6, 1.0 / 3, 2.0 / 3};

  /** Smallest leaf: five is the library's default; the larger ones smooth the forest. */
  public static final int[] NODE_SIZES = {5, 25, 100};

  public static final long SEED = 20260927L;

  /** One point of the grid: the scored side's error with these parameters. */
  public record Trial(int mtry, int nodeSize, double maeHours) {}

  /**
   * @param importance per column, how much it lowered the squared error over all splits
   */
  public record Fitted(Cell cell, int mtry, int nodeSize, List<String> columns,
      List<String> dropped, double[] importance, double[] predictedHours, Score score,
      List<Trial> trials) {}

  private Forest() {}

  /** Each column share, as a count of the cell's columns: at least one. */
  public static int mtry(final double share, final int columns) {
    return Math.max(1, (int) Math.round(share * columns));
  }

  /**
   * Fits every point of the grid on the fitting side and keeps the one that errs least on the
   * scored side. Only ever called with {@link Fitting.Mode#INNER} sides.
   */
  public static Fitted sweep(final Cell cell, final Fitting.Sides sides, final double baselineMae,
      final double constantMae) {
    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final double[] y = Fitting.target(sides.fitUnits());
    final int p = matrix.columns().size();

    RandomForestModel best = null;
    double[] bestHours = null;
    double bestMae = Double.POSITIVE_INFINITY;
    final List<Trial> trials = new ArrayList<>();
    final List<Integer> tried = new ArrayList<>();
    for (final double share : MTRY_SHARES) {
      final int mtry = mtry(share, p);
      if (tried.contains(mtry)) {
        continue;
      }
      tried.add(mtry);
      for (final int nodeSize : NODE_SIZES) {
        final RandomForestModel model = RandomForestModel.fit(x, y, matrix.columns(), TREES, mtry,
            MAX_DEPTH, nodeSize, SEED);
        final double[] hours = hours(model.predict(xs));
        final double mae = Fitting.mae(hours, sides.observed());
        trials.add(new Trial(mtry, nodeSize, mae));
        if (mae < bestMae) {
          bestMae = mae;
          best = model;
          bestHours = hours;
        }
      }
    }
    return new Fitted(cell, best.mtry(), best.nodeSize(), matrix.columns(), matrix.dropped(),
        best.importance(), bestHours,
        Score.of(cell.label() + " [floresta]", bestHours, sides.observed(), baselineMae,
            constantMae), List.copyOf(trials));
  }

  static double[] hours(final double[] log) {
    final double[] out = new double[log.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = DesignMatrix.hours(log[i]);
    }
    return out;
  }
}
