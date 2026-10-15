package app.issues.model;

import app.model.RandomForestModel;
import app.study.model.DesignMatrix;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The random forest beside the Elastic Net, on the inner selection only (asked on 29/09/2026,
 * after the reserved run: a comparison of estimators, not a new registered test).
 *
 * <p>Everything the linear model gets, this gets: the same cell, the same fitting and scored sides,
 * the same design matrix learned on the fitting side, the target {@code log1p} of hours and the
 * prediction back through {@code expm1}, held at zero. The grid below was declared before the
 * forest ran; the point with the smallest mean absolute error on the scored side is kept, as the
 * linear sweep keeps its own.
 *
 * <p>The description-similarity chain (ticket 13, 15/10/2026) uses the same grid as a robustness
 * check beside each of its Elastic Net models: swept on the inner sides with the columns that
 * Elastic Net kept, weighted or not ({@link #sweep}), refitted once on the outer training set at
 * the chosen point ({@link #frozen}), and read on the scored side by permutation importance
 * ({@link #permutationImportance}).
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

  /**
   * How many times each column is shuffled for its permutation importance, the rises averaged:
   * five, scikit-learn's default.
   */
  public static final int PERMUTATION_REPEATS = 5;

  /**
   * One point of the grid: the scored side's error with these parameters.
   *
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry}
   */
  public record Trial(double share, int mtry, int nodeSize, double maeHours) {}

  /**
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry}
   * @param importance per column, how much it lowered the squared error over all splits
   */
  public record Fitted(Cell cell, double share, int mtry, int nodeSize, List<String> columns,
      List<String> dropped, double[] importance, double[] predictedHours, Score score,
      List<Trial> trials) {}

  /**
   * One forest's parameters as the selection chose them, as the test refits it.
   *
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry}, for the reader
   * @param mtry the columns drawn at each split, as the sweep counted them on the inner fitting
   *     design; the refit draws exactly this many
   * @param nodeSize the chosen smallest leaf, one of {@link #NODE_SIZES}
   */
  public record Choice(double share, int mtry, int nodeSize) {

    /** Refuses a share or a leaf the grid does not declare, or a count below one. */
    public Choice {
      if (Arrays.stream(MTRY_SHARES).noneMatch(s -> s == share)
          || Arrays.stream(NODE_SIZES).noneMatch(l -> l == nodeSize) || mtry < 1) {
        throw new IllegalArgumentException("mtry share " + share + ", mtry " + mtry + " and leaf "
            + nodeSize + " are not a point of the declared grid, shares "
            + Arrays.toString(MTRY_SHARES) + " and leaves " + Arrays.toString(NODE_SIZES));
      }
    }
  }

  /**
   * A column's permutation importance on the scored side: how much the mean absolute error, in
   * hours, rises when that column's values are shuffled among the scored rows.
   *
   * @param increases the rise of each repeat, in hours, in the order drawn
   */
  public record Importance(String column, double meanIncreaseHours, double[] increases) {}

  /**
   * One forest refitted with given parameters and scored once. Searches nothing.
   *
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry} on the inner sweep
   * @param importance per column, in the columns' order, its permutation importance on the scored
   *     side
   */
  public record Frozen(Cell cell, double share, int mtry, int nodeSize, List<String> columns,
      List<String> dropped, double[] predictedHours, Score score, List<Importance> importance) {}

  private Forest() {}

  /** Each column share, as a count of the cell's columns: at least one. */
  public static int mtry(final double share, final int columns) {
    return Math.max(1, (int) Math.round(share * columns));
  }

  /**
   * Fits every point of the grid on the fitting side and keeps the one that errs least on the
   * scored side ({@link #chosen}). A share that gives a count already tried is skipped. Only ever
   * called with {@link Fitting.Mode#INNER} sides.
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
    double bestShare = Double.NaN;
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
        trials.add(new Trial(share, mtry, nodeSize, mae));
        // strictly lower, as chosen() reads the trials: a tie stays with the earlier point
        if (mae < bestMae) {
          bestMae = mae;
          best = model;
          bestHours = hours;
          bestShare = share;
        }
      }
    }
    return new Fitted(cell, bestShare, best.mtry(), best.nodeSize(), matrix.columns(),
        matrix.dropped(), best.importance(), bestHours,
        Score.of(cell.label() + " [floresta]", bestHours, sides.observed(), baselineMae,
            constantMae), List.copyOf(trials));
  }

  /**
   * Which of the trials, in grid order, {@link #sweep} keeps: the first with the lowest error. A
   * point is kept only when its error is strictly lower, so of points whose errors are exactly
   * equal the earlier one wins -- the smaller share first, then the smaller leaf. No tolerance.
   */
  public static int chosen(final List<Trial> trials) {
    int best = -1;
    double bestMae = Double.POSITIVE_INFINITY;
    for (int t = 0; t < trials.size(); t++) {
      if (trials.get(t).maeHours() < bestMae) {
        bestMae = trials.get(t).maeHours();
        best = t;
      }
    }
    return best;
  }

  /**
   * One forest on the fitting side at the given point of the grid, scored once on the scored side,
   * with each column's permutation importance there. The same design matrix, target and
   * back-transform as {@link #sweep}. The forest draws exactly the chosen count of columns at each
   * split, the point the selection validated; a count larger than this fit's columns refuses.
   */
  public static Frozen frozen(final Cell cell, final Fitting.Sides sides, final Choice choice,
      final double baselineMae, final double constantMae) {
    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    if (choice.mtry() > matrix.columns().size()) {
      throw new IllegalArgumentException(cell.label() + "'s forest draws " + choice.mtry()
          + " columns at each split, but its design has " + matrix.columns().size());
    }
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final RandomForestModel model = RandomForestModel.fit(x, Fitting.target(sides.fitUnits()),
        matrix.columns(), TREES, choice.mtry(), MAX_DEPTH, choice.nodeSize(), SEED);
    final double[] hours = hours(model.predict(xs));
    return new Frozen(cell, choice.share(), choice.mtry(), choice.nodeSize(), matrix.columns(),
        matrix.dropped(), hours,
        Score.of(cell.label(), hours, sides.observed(), baselineMae, constantMae),
        permutationImportance(model, matrix.columns(), xs, hours, sides.observed()));
  }

  /**
   * Per design column, in the columns' order: the scored side's mean absolute error, in hours,
   * with that column's values shuffled among the scored rows, minus the error unshuffled, for each
   * of {@link #PERMUTATION_REPEATS} shuffles and on average. Column {@code c} is shuffled by its
   * own {@code Random(SEED + c)}, the repeats in turn, so no column's draws depend on another's.
   * Each design column is shuffled alone, as the ticket asks: a categorical attribute's level
   * indicators and a column's missing indicator are read one by one, not as one attribute.
   *
   * @param hours the model's predictions of {@code xs}, unshuffled, in hours
   */
  public static List<Importance> permutationImportance(final RandomForestModel model,
      final List<String> columns, final double[][] xs, final double[] hours,
      final double[] observed) {
    final double base = Fitting.mae(hours, observed);
    final double[][] shuffled = new double[xs.length][];
    for (int i = 0; i < xs.length; i++) {
      shuffled[i] = xs[i].clone();
    }
    final List<Importance> out = new ArrayList<>(columns.size());
    for (int c = 0; c < columns.size(); c++) {
      final Random random = new Random(SEED + c);
      final double[] increases = new double[PERMUTATION_REPEATS];
      double sum = 0;
      for (int r = 0; r < PERMUTATION_REPEATS; r++) {
        // Fisher-Yates over the rows, on column c alone, from its unshuffled order each repeat
        for (int i = 0; i < xs.length; i++) {
          shuffled[i][c] = xs[i][c];
        }
        for (int i = xs.length - 1; i > 0; i--) {
          final int j = random.nextInt(i + 1);
          final double v = shuffled[i][c];
          shuffled[i][c] = shuffled[j][c];
          shuffled[j][c] = v;
        }
        increases[r] = Fitting.mae(hours(model.predict(shuffled)), observed) - base;
        sum += increases[r];
      }
      for (int i = 0; i < xs.length; i++) {
        shuffled[i][c] = xs[i][c];
      }
      out.add(new Importance(columns.get(c), sum / PERMUTATION_REPEATS, increases));
    }
    return List.copyOf(out);
  }

  static double[] hours(final double[] log) {
    final double[] out = new double[log.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = DesignMatrix.hours(log[i]);
    }
    return out;
  }
}
