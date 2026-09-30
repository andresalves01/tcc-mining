package app.model;

import java.util.ArrayList;
import java.util.List;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.regression.RandomForest;

/**
 * A random forest of regression trees on the same outcome and the same columns as
 * {@link ElasticNetModel}.
 *
 * <p><b>Why it exists.</b> A small gain from a linear model can be read two ways: the columns
 * carry little, or the estimator could not use what they carry. A non-linear learner on identical
 * rows, columns and sides separates those readings. It is a comparison, not a proposal. It is also
 * the estimator family Kikas et al. (2016) used, there for classification.
 *
 * <p>Trees are invariant to the per-column affine rescaling the study applies, so this model is fed
 * the standardised matrix the linear model sees, missing values already replaced by the fitting
 * side's median with an indicator beside them.
 *
 * <p>Every tree has its own seed, derived from one, so two fits on the same rows are the same
 * forest.
 */
public final class RandomForestModel {

  private static final String OUTCOME = "__outcome";

  private final RandomForest model;
  private final List<String> columns;
  private final int mtry;
  private final int nodeSize;

  private RandomForestModel(final RandomForest model, final List<String> columns, final int mtry,
      final int nodeSize) {
    this.model = model;
    this.columns = List.copyOf(columns);
    this.mtry = mtry;
    this.nodeSize = nodeSize;
  }

  /**
   * @param trees how many trees
   * @param mtry how many columns each split draws from
   * @param maxDepth the deepest a tree may grow
   * @param nodeSize the smallest leaf
   * @param seed the first tree's seed; tree {@code t} gets {@code seed + t}
   */
  public static RandomForestModel fit(final double[][] predictors, final double[] outcome,
      final List<String> columns, final int trees, final int mtry, final int maxDepth,
      final int nodeSize, final long seed) {
    final long[] seeds = new long[trees];
    for (int t = 0; t < trees; t++) {
      seeds[t] = seed + t;
    }
    final RandomForest fitted = RandomForest.fit(Formula.lhs(OUTCOME),
        frame(predictors, outcome, columns),
        new RandomForest.Options(trees, mtry, maxDepth, 0, nodeSize, 1.0, seeds, null));
    return new RandomForestModel(fitted, columns, mtry, nodeSize);
  }

  /** The same shape {@link ElasticNetModel} builds: columns then the outcome, named alike. */
  private static DataFrame frame(final double[][] predictors, final double[] outcome,
      final List<String> columns) {
    final double[][] rows = new double[predictors.length][columns.size() + 1];
    for (int i = 0; i < predictors.length; i++) {
      System.arraycopy(predictors[i], 0, rows[i], 0, columns.size());
      rows[i][columns.size()] = outcome == null ? 0 : outcome[i];
    }
    final List<String> names = new ArrayList<>(columns);
    names.add(OUTCOME);
    return DataFrame.of(rows, names.toArray(String[]::new));
  }

  /**
   * Predicts every row, on the outcome's scale. The outcome slot is present but unread: the formula
   * names it as the left-hand side, and a row missing it would not match the fitted schema.
   */
  public double[] predict(final double[][] rows) {
    final DataFrame frame = frame(rows, null, columns);
    final double[] out = new double[rows.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = model.predict(frame.get(i));
    }
    return out;
  }

  /** How much each column lowered the squared error over all splits, in the columns' order. */
  public double[] importance() {
    return model.importance().clone();
  }

  public int mtry() {
    return mtry;
  }

  public int nodeSize() {
    return nodeSize;
  }
}
