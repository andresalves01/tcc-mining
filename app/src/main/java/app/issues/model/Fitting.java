package app.issues.model;

import app.model.ElasticNetModel;
import app.model.Metrics;
import app.model.Parallel;
import app.study.Row;
import app.study.model.DesignMatrix;
import app.study.model.Score;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * One way to fit and one way to score, shared by the grid, the check and the reserved run.
 *
 * <p>The rules: the design matrix learns every parameter on the
 * fitting side only; the target is {@code log1p} of hours; predictions go back with {@code expm1}
 * and are held at zero; the penalty grid's shape is declared and its scale is anchored on the
 * largest penalty that still leaves a coefficient alive, because the estimator applies lambda as
 * given and a fixed list would be inert at this many rows.
 */
public final class Fitting {

  /** Declared before anything ran: three mixtures. */
  public static final double[] ALPHAS = {0.10, 0.50, 0.95};

  /**
   * Twelve penalties per mixture, declared before the grid ran, because the budget was one long
   * run over this many fitting rows.
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

  /**
   * @param step which penalty of its mixture's grid the sweep chose: 0 is the top, where every
   *     coefficient is zero and the model is a constant; -1 when the parameters were given, not
   *     swept
   */
  public record Fitted(Cell cell, double alpha, double lambda, List<String> columns,
      List<String> dropped, List<Contribution> contributions, int selected,
      double[] predictedHours, Score score, int step, List<Trial> trials,
      Recalibration recalibration) {

    /** A reference or a given prediction: nothing was swept and nothing recalibrated. */
    public Fitted(final Cell cell, final double alpha, final double lambda,
        final List<String> columns, final List<String> dropped,
        final List<Contribution> contributions, final int selected, final double[] predictedHours,
        final Score score, final int step) {
      this(cell, alpha, lambda, columns, dropped, contributions, selected, predictedHours, score,
          step, List.of(), null);
    }

    /** The 10th, 50th and 90th percentiles of the predictions, in hours: how much they spread. */
    public double[] spread() {
      final double[] sorted = predictedHours.clone();
      java.util.Arrays.sort(sorted);
      final int n = sorted.length;
      return new double[] {sorted[n / 10], sorted[n / 2], sorted[Math.min(n - 1, 9 * n / 10)]};
    }
  }

  /** One point of the grid: the scored side's error of the fit with this mixture and penalty. */
  public record Trial(double alpha, int step, double lambda, double maeHours) {}

  /**
   * A linear recalibration on the log scale, measured beside the model and never in place of it
   * (asked on 29/09/2026, to be judged after seeing): {@code log1p(hours) = a + b * z}, where
   * {@code z} is the model's own prediction on that scale, with {@code a} and {@code b} fitted by
   * least squares on the fitting side's in-sample predictions. With {@code b > 1} it undoes part of
   * the penalty's shrinking. It is monotone, so the ordering -- Spearman, pairwise -- cannot
   * change; only the error in hours and its spread over bands can.
   */
  public record Recalibration(double a, double b, double[] predictedHours) {}

  private Fitting() {}

  public static Sides sides(final List<RowCache.Entry> entries, final Mode mode,
      final Universe.Population population) {
    final List<IssueUnit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<IssueUnit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (final RowCache.Entry entry : entries) {
      final String outer = entry.outer(population);
      final String inner = entry.inner(population);
      final boolean fit;
      final boolean score;
      if (mode == Mode.INNER) {
        // The reserved side is refused here, not filtered by a caller: under INNER nothing on the
        // far side of the outer cut has any business in the fit or the score.
        fit = "TRAIN".equals(outer) && "TRAIN".equals(inner);
        score = "TRAIN".equals(outer) && "TEST".equals(inner);
      } else {
        fit = "TRAIN".equals(outer);
        score = "TEST".equals(outer);
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
          + " to score under " + mode + " in " + population);
    }
    final double[] observed = new double[scoreUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = scoreUnits.get(i).duration();
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

  /**
   * The penalties the sweep tries for one mixture, from the point where every coefficient is zero
   * down four decades.
   *
   * <p>The top is {@code 2 * max|X'(y - mean)| / alpha}, not
   * {@code max|X'(y - mean)|}. The estimator hands {@code alpha * lambda} to the L1 term of an
   * objective whose squared error is not halved, so that is where the last coefficient dies --
   * {@code LambdaAnchorTest} pins it. Anchored without the factor, the grid stopped between two and
   * twenty times short of it, and on the first run thirteen of twenty cells chose its topmost value:
   * the selection was pressing against the edge of a grid that ended too early. Found on the inner
   * selection, before any registration and before the reserved rows existed for this study.
   */
  public static double[] lambdas(final double[][] x, final double[] y, final double alpha) {
    final double top = 2 * largestPenalty(x, y) / alpha;
    final double[] out = new double[LAMBDA_STEPS];
    for (int s = 0; s < LAMBDA_STEPS; s++) {
      out[s] = top * Math.pow(LAMBDA_FLOOR_RATIO, (double) s / (LAMBDA_STEPS - 1));
    }
    return out;
  }

  /**
   * Sweeps the declared grid on the fitting side and keeps the pair with the lowest absolute error
   * on the scored side. Only ever called with {@link Mode#INNER} sides. The points are fitted on
   * the threads {@link Parallel#ELASTIC_NET_THREADS} asks for (see {@link #sweep(Cell, Sides,
   * double, double, int)}).
   */
  public static Fitted sweep(final Cell cell, final Sides sides, final double baselineMae,
      final double constantMae) {
    return sweep(cell, sides, baselineMae, constantMae,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS));
  }

  /**
   * The sweep with its points fitted on up to {@code threads} threads. Each point is one fit and
   * one error, computed alone on one thread from the same matrices, which every point only reads;
   * the choice then reads the errors in the grid's order, mixture by mixture and penalty by
   * penalty, and keeps a point only when its error is strictly lower, so a tie keeps the earlier
   * point. One thread is the sequential sweep, and any number gives its result bit for bit.
   */
  public static Fitted sweep(final Cell cell, final Sides sides, final double baselineMae,
      final double constantMae, final int threads) {

    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final double[] y = target(sides.fitUnits());
    final List<Trial> points = new ArrayList<>();
    for (final double alpha : ALPHAS) {
      final double[] grid = lambdas(x, y, alpha);
      for (int step = 0; step < grid.length; step++) {
        points.add(new Trial(alpha, step, grid[step], Double.NaN));
      }
    }
    final List<Callable<Point>> jobs = new ArrayList<>(points.size());
    for (final Trial p : points) {
      jobs.add(() -> {
        final ElasticNetModel model =
            ElasticNetModel.fit(x, y, matrix.columns(), p.alpha(), p.lambda());
        double sum = 0;
        for (int i = 0; i < xs.length; i++) {
          sum += Math.abs(DesignMatrix.hours(model.predict(xs[i])) - sides.observed()[i]);
        }
        return new Point(model, sum / xs.length);
      });
    }
    final List<Point> fitted = Parallel.map(threads, jobs);
    ElasticNetModel best = null;
    double bestMae = Double.POSITIVE_INFINITY;
    int bestStep = -1;
    final List<Trial> trials = new ArrayList<>(points.size());
    for (int k = 0; k < points.size(); k++) {
      final Trial p = points.get(k);
      final Point f = fitted.get(k);
      trials.add(new Trial(p.alpha(), p.step(), p.lambda(), f.maeHours()));
      if (f.maeHours() < bestMae) {
        bestMae = f.maeHours();
        best = f.model();
        bestStep = p.step();
      }
    }
    return result(cell, matrix, x, y, xs, best, sides.observed(), baselineMae, constantMae,
        bestStep, List.copyOf(trials));
  }

  /** One point of the sweep fitted: the model and its error on the scored side, in hours. */
  private record Point(ElasticNetModel model, double maeHours) {}

  /**
   * Decision 20, registered before the reserved rows are scored: the penalty of the final fit is
   * the chosen one times the ratio of rows. The estimator sums the squared error over rows instead
   * of averaging it, so the same lambda on the whole training side would penalise less per row than
   * the selection chose; scaled, each row weighs against the penalty as it did when it was chosen.
   * Alpha is kept. The final predictions are the model's own: no recalibration (decision 22).
   *
   * @param chosen the lambda chosen on the inner selection
   * @param finalRows the rows the final model is fitted on: the whole outer training side
   * @param innerFitRows the rows the chosen lambda was fitted on: the inner fitting side
   */
  public static double finalLambda(final double chosen, final int finalRows,
      final int innerFitRows) {
    if (finalRows <= 0 || innerFitRows <= 0) {
      throw new IllegalArgumentException("row counts must be positive");
    }
    return chosen * finalRows / (double) innerFitRows;
  }

  /** One fit with given parameters: what the check and the reserved run do. Searches nothing. */
  public static Fitted frozen(final Cell cell, final Sides sides, final double alpha,
      final double lambda, final double baselineMae, final double constantMae) {

    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[] y = target(sides.fitUnits());
    final ElasticNetModel model = ElasticNetModel.fit(x, y, matrix.columns(), alpha, lambda);
    return result(cell, matrix, x, y, matrix.applyAll(sides.scoreRows()), model,
        sides.observed(), baselineMae, constantMae, -1, List.of());
  }

  private static Fitted result(final Cell cell, final DesignMatrix matrix, final double[][] x,
      final double[] y, final double[][] xs, final ElasticNetModel model, final double[] observed,
      final double baselineMae, final double constantMae, final int step,
      final List<Trial> trials) {

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
        Score.of(cell.label(), predicted, observed, baselineMae, constantMae), step, trials,
        recalibrate(model, x, y, xs));
  }

  /** Least squares of the fitting side's target on the model's in-sample predictions, applied. */
  static Recalibration recalibrate(final ElasticNetModel model, final double[][] x,
      final double[] y, final double[][] xs) {
    final double[] z = new double[x.length];
    double mz = 0;
    double my = 0;
    for (int i = 0; i < x.length; i++) {
      z[i] = model.predict(x[i]);
      mz += z[i];
      my += y[i];
    }
    mz /= x.length;
    my /= x.length;
    double cov = 0;
    double var = 0;
    for (int i = 0; i < x.length; i++) {
      cov += (z[i] - mz) * (y[i] - my);
      var += (z[i] - mz) * (z[i] - mz);
    }
    // a constant model has nothing to stretch: it stays as it is
    final double b = var <= 1e-12 ? 1.0 : cov / var;
    final double a = var <= 1e-12 ? 0.0 : my - b * mz;
    final double[] hours = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      hours[i] = DesignMatrix.hours(a + b * model.predict(xs[i]));
    }
    return new Recalibration(a, b, hours);
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
