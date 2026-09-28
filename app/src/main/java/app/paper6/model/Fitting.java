package app.paper6.model;

import app.model.ElasticNetModel;
import app.model.Metrics;
import app.paper5.Row;
import app.paper5.model.DesignMatrix;
import app.paper5.model.Score;
import app.paper6.IssueUnit;
import app.paper6.RowCache;
import java.util.ArrayList;
import java.util.List;

/**
 * One way to fit and one way to score, shared by the grid, the check and the reserved run.
 *
 * <p>The pull-request study's rules without change: the design matrix learns every parameter on the
 * fitting side only; the target is {@code log1p} of hours; predictions go back with {@code expm1}
 * and are held at zero; the penalty grid's shape is declared and its scale is anchored on the
 * largest penalty that still leaves a coefficient alive, because the estimator applies lambda as
 * given and a fixed list would be inert at this many rows.
 */
public final class Fitting {

  /** Declared before anything ran: the same three mixtures as the pull-request study. */
  public static final double[] ALPHAS = {0.10, 0.50, 0.95};

  /**
   * Twelve penalties per mixture, not the pull-request study's twenty-four -- declared before the
   * grid ran, because there are six times as many fitting rows here and the budget was one long run.
   */
  public static final int LAMBDA_STEPS = 12;

  public static final double LAMBDA_FLOOR_RATIO = 1e-4;

  public static final long SEED = 20260927L;

  /** Which rows fit and which are scored. */
  public enum Mode {
    /** Development: the inner cut of the outer training side. The reserved rows are refused. */
    INNER,
    /** The one reserved run: the whole training side fits, the reserved side is scored. */
    RESERVED
  }

  public record Sides(List<IssueUnit> fitUnits, List<Row> fitRows, List<IssueUnit> scoreUnits,
      List<Row> scoreRows, double[] observed) {

    public List<String> projects() {
      return scoreUnits.stream().map(IssueUnit::project).toList();
    }
  }

  public record Contribution(String column, double coefficient, double ownSpearman,
      boolean selected) {}

  public record Fitted(Cell cell, double alpha, double lambda, List<String> columns,
      List<String> dropped, List<Contribution> contributions, int selected,
      double[] predictedHours, Score score) {}

  private Fitting() {}

  public static Sides sides(final List<RowCache.Entry> entries, final Mode mode) {
    final List<IssueUnit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<IssueUnit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (final RowCache.Entry entry : entries) {
      final boolean fit;
      final boolean score;
      if (mode == Mode.INNER) {
        // The reserved side is refused here, not filtered by a caller: under INNER nothing on the
        // far side of the outer cut has any business in the fit or the score.
        fit = "TRAIN".equals(entry.outer()) && "TRAIN".equals(entry.inner());
        score = "TRAIN".equals(entry.outer()) && "TEST".equals(entry.inner());
      } else {
        fit = "TRAIN".equals(entry.outer());
        score = "TEST".equals(entry.outer());
      }
      if (fit) {
        fitUnits.add(entry.unit());
        fitRows.add(entry.row());
      } else if (score) {
        scoreUnits.add(entry.unit());
        scoreRows.add(entry.row());
      }
    }
    if (fitUnits.isEmpty() || scoreUnits.isEmpty()) {
      throw new IllegalStateException(fitUnits.size() + " rows to fit and " + scoreUnits.size()
          + " to score under " + mode);
    }
    final double[] observed = new double[scoreUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = scoreUnits.get(i).durationHours();
    }
    return new Sides(fitUnits, fitRows, scoreUnits, scoreRows, observed);
  }

  /** The largest penalty that still leaves a coefficient alive: the anchor of the grid. */
  static double largestPenalty(final double[][] x, final double[] y) {
    double mean = 0;
    for (final double value : y) {
      mean += value;
    }
    mean /= y.length;
    double largest = 0;
    for (int c = 0; c < x[0].length; c++) {
      double dot = 0;
      for (int r = 0; r < x.length; r++) {
        dot += x[r][c] * (y[r] - mean);
      }
      largest = Math.max(largest, Math.abs(dot));
    }
    return largest;
  }

  /** The penalties the sweep tries, for one fitting side. */
  public static double[] lambdas(final double[][] x, final double[] y) {
    final double max = largestPenalty(x, y);
    final double[] out = new double[LAMBDA_STEPS];
    for (int s = 0; s < LAMBDA_STEPS; s++) {
      out[s] = max * Math.pow(LAMBDA_FLOOR_RATIO, (double) s / (LAMBDA_STEPS - 1));
    }
    return out;
  }

  /**
   * Sweeps the declared grid on the fitting side and keeps the pair with the lowest absolute error
   * on the scored side. Only ever called with {@link Mode#INNER} sides.
   */
  public static Fitted sweep(final Cell cell, final Sides sides, final double baselineMae,
      final double constantMae) {

    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final double[] y = target(sides.fitUnits());
    ElasticNetModel best = null;
    double bestMae = Double.POSITIVE_INFINITY;
    for (final double alpha : ALPHAS) {
      for (final double lambda : lambdas(x, y)) {
        final ElasticNetModel model = ElasticNetModel.fit(x, y, matrix.columns(), alpha, lambda);
        double sum = 0;
        for (int i = 0; i < xs.length; i++) {
          sum += Math.abs(DesignMatrix.hours(model.predict(xs[i])) - sides.observed()[i]);
        }
        if (sum / xs.length < bestMae) {
          bestMae = sum / xs.length;
          best = model;
        }
      }
    }
    return result(cell, matrix, x, y, xs, best, sides.observed(), baselineMae, constantMae);
  }

  /** One fit with given parameters: what the check and the reserved run do. Searches nothing. */
  public static Fitted frozen(final Cell cell, final Sides sides, final double alpha,
      final double lambda, final double baselineMae, final double constantMae) {

    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[] y = target(sides.fitUnits());
    final ElasticNetModel model = ElasticNetModel.fit(x, y, matrix.columns(), alpha, lambda);
    return result(cell, matrix, x, y, matrix.applyAll(sides.scoreRows()), model,
        sides.observed(), baselineMae, constantMae);
  }

  private static Fitted result(final Cell cell, final DesignMatrix matrix, final double[][] x,
      final double[] y, final double[][] xs, final ElasticNetModel model, final double[] observed,
      final double baselineMae, final double constantMae) {

    final double[] weights = model.coefficients();
    final List<Contribution> contributions = new ArrayList<>(weights.length);
    for (int c = 0; c < weights.length; c++) {
      final double[] column = new double[x.length];
      for (int i = 0; i < x.length; i++) {
        column[i] = x[i][c];
      }
      contributions.add(new Contribution(matrix.columns().get(c), weights[c],
          Metrics.spearman(column, y), model.selected(c)));
    }
    final double[] predicted = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      predicted[i] = DesignMatrix.hours(model.predict(xs[i]));
    }
    return new Fitted(cell, model.alpha(), model.lambda(), matrix.columns(), matrix.dropped(),
        List.copyOf(contributions), model.selectedCount(), predicted,
        Score.of(cell.label(), predicted, observed, baselineMae, constantMae));
  }

  static double[] target(final List<IssueUnit> units) {
    final double[] y = new double[units.size()];
    for (int i = 0; i < y.length; i++) {
      y[i] = DesignMatrix.target(units.get(i));
    }
    return y;
  }

  /** Mean absolute error of a prediction, in hours. */
  public static double mae(final double[] predicted, final double[] observed) {
    double sum = 0;
    for (int i = 0; i < observed.length; i++) {
      sum += Math.abs(predicted[i] - observed[i]);
    }
    return sum / observed.length;
  }
}
