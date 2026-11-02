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
 * the same design matrix learned on the fitting side, the target {@code log1p} of the duration and the
 * prediction back through {@code expm1}, held at zero. The grid below was declared before the
 * forest ran; the point with the smallest mean absolute error on the scored side is kept, as the
 * linear sweep keeps its own.
 *
 * <p>The study (ticket 13, 15/10/2026) uses the same grid as a robustness
 * check beside each of its Elastic Net models: swept on the inner sides with the columns that
 * Elastic Net kept, weighted or not ({@link #sweep}), refitted once on the outer training set at
 * the chosen point ({@link #frozen}), and read on the scored side by permutation importance
 * ({@link #permutationImportance}).
 */
public final class Forest {

  /**
   * Trees per forest, fixed rather than swept: more trees only steady the average. The count of
   * every file of the random split; the parameters file may give another ({@link
   * app.issues.ElasticNetSelection#trees}).
   */
  public static final int TREES = 300;

  /**
   * Trees per forest in the first-commit study's temporal split: the Smile library's default,
   * decided by the author on 02/11/2026, before any temporal result, in place of the 300 inherited
   * from the pull-request study's gradient-boosting control, which were never evaluated.
   */
  public static final int LIBRARY_TREES = 500;

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
  public record Trial(double share, int mtry, int nodeSize, double mae) {}

  /**
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry}
   * @param importance per column, how much it lowered the squared error over all splits
   */
  public record Fitted(Cell cell, double share, int mtry, int nodeSize, List<String> columns,
      List<String> dropped, double[] importance, double[] predicted, Score score,
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
   * the target's unit, rises when that column's values are shuffled among the scored rows.
   *
   * @param increases the rise of each repeat, in the target's unit, in the order drawn
   */
  public record Importance(String column, double meanIncrease, double[] increases) {}

  /**
   * One forest refitted with given parameters and scored once. Searches nothing.
   *
   * @param share the entry of {@link #MTRY_SHARES} that gave {@code mtry} on the inner sweep
   * @param importance per column, in the columns' order, its permutation importance on the scored
   *     side
   */
  public record Frozen(Cell cell, double share, int mtry, int nodeSize, List<String> columns,
      List<String> dropped, double[] predicted, Score score, List<Importance> importance) {}

  private Forest() {}

  /** Each column share, as a count of the cell's columns: at least one. */
  public static int mtry(final double share, final int columns) {
    return Math.max(1, (int) Math.round(share * columns));
  }

  /**
   * Fits every point of the grid on the fitting side and keeps the first one that errs least on
   * the scored side. A share that gives a count already tried is skipped. Only ever
   * called with {@link Fitting.Mode#INNER} sides.
   */
  public static Fitted sweep(final Cell cell, final Fitting.Sides sides, final double referenceMae,
      final double constantMae) {
    return sweep(cell, sides, referenceMae, constantMae, TREES);
  }

  /**
   * {@link #sweep(Cell, Fitting.Sides, double, double)} with {@code trees} trees in every forest
   * of the grid, as the split rule's selection asks ({@link
   * app.issues.ElasticNetSelection#trees(app.issues.SplitRule)}).
   */
  public static Fitted sweep(final Cell cell, final Fitting.Sides sides, final double referenceMae,
      final double constantMae, final int trees) {
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
        final RandomForestModel model = RandomForestModel.fit(x, y, matrix.columns(), trees, mtry,
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
        Score.of(cell.label() + " [floresta]", bestHours, sides.observed(), referenceMae,
            constantMae), List.copyOf(trials));
  }

  /**
   * One forest at the chosen point of the grid, its permutation importance
   * read only when {@code importance}: without it the list is empty and the scored side's
   * outcomes are read for the error alone. The outer test reads none, since shuffling a column
   * among its issues and reading the rise in error would read its labels column by column.
   */
  public static Frozen frozen(final Cell cell, final Fitting.Sides sides, final Choice choice,
      final double referenceMae, final double constantMae, final boolean importance) {
    return frozen(cell, sides, choice, TREES, referenceMae, constantMae, importance);
  }

  /**
   * {@link #frozen(Cell, Fitting.Sides, Choice, double, double, boolean)} with {@code trees}
   * trees, as the parameters file gives them ({@link app.issues.ElasticNetSelection#trees}).
   */
  public static Frozen frozen(final Cell cell, final Fitting.Sides sides, final Choice choice,
      final int trees, final double referenceMae, final double constantMae,
      final boolean importance) {
    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    if (choice.mtry() > matrix.columns().size()) {
      throw new IllegalArgumentException(cell.label() + "'s forest draws " + choice.mtry()
          + " columns at each split, but its design has " + matrix.columns().size());
    }
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final RandomForestModel model = RandomForestModel.fit(x, Fitting.target(sides.fitUnits()),
        matrix.columns(), trees, choice.mtry(), MAX_DEPTH, choice.nodeSize(), SEED);
    final double[] hours = hours(model.predict(xs));
    return new Frozen(cell, choice.share(), choice.mtry(), choice.nodeSize(), matrix.columns(),
        matrix.dropped(), hours,
        Score.of(cell.label(), hours, sides.observed(), referenceMae, constantMae),
        importance ? permutationImportance(model, matrix.columns(), xs, hours, sides.observed())
            : List.of());
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
