package app.model;

import java.util.ArrayList;
import java.util.List;
import smile.base.cart.Loss;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.regression.GradientTreeBoost;

/**
 * A gradient-boosted tree ensemble on the same outcome and the same columns as
 * {@link ElasticNetModel}.
 *
 * <p><b>Why it exists.</b> A null from a linear model fitted on columns correlated at 0.985 can be
 * read two ways: the indicators carry nothing, or the estimator could not use what they carry. A
 * non-linear learner on identical rows, columns and splits separates those readings. It is a
 * control, not a proposal — nothing here argues that boosting is the right estimator for the study.
 *
 * <p>Trees are invariant to the per-column affine rescaling the study applies, so this model is fed
 * the standardised matrix the linear model sees. That keeps "the same predictors" literally true
 * rather than approximately true, at no cost to the ensemble.
 *
 * <p>Hyperparameters are chosen on the inner split only, exactly as the linear model's are. The
 * held-out partition never selects anything.
 */
public final class GradientBoostingModel {

  /** Squared error, to match the loss the linear model minimises on the same scale. */
  private static final Loss LOSS = Loss.ls();

  /** Unlimited depth is left to {@code maxNodes}, which is the knob actually swept. */
  private static final int MAX_DEPTH = 20;

  /** Smallest leaf. Fixed rather than swept: the sweep is over trees, nodes and shrinkage. */
  private static final int NODE_SIZE = 5;

  /** Rows drawn per tree. Below one this is stochastic boosting, which regularises. */
  private static final double SUBSAMPLE = 0.7;

  private final GradientTreeBoost model;
  private final List<String> columns;
  private final int trees;
  private final int maxNodes;
  private final double shrinkage;

  private GradientBoostingModel(final GradientTreeBoost model, final List<String> columns,
      final int trees, final int maxNodes, final double shrinkage) {

    this.model = model;
    this.columns = List.copyOf(columns);
    this.trees = trees;
    this.maxNodes = maxNodes;
    this.shrinkage = shrinkage;
  }

  public static GradientBoostingModel fit(final double[][] predictors, final double[] outcome,
      final List<String> columns, final int trees, final int maxNodes, final double shrinkage) {

    final GradientTreeBoost fitted = GradientTreeBoost.fit(
        Formula.lhs(OUTCOME), frame(predictors, outcome, columns),
        new GradientTreeBoost.Options(LOSS, trees, MAX_DEPTH, maxNodes, NODE_SIZE,
            shrinkage, SUBSAMPLE, null, null));
    return new GradientBoostingModel(fitted, columns, trees, maxNodes, shrinkage);
  }

  private static final String OUTCOME = "__outcome";

  /** The same shape {@link ElasticNetModel} builds: columns then the outcome, named alike. */
  private static DataFrame frame(final double[][] predictors, final double[] outcome,
      final List<String> columns) {

    final double[][] rows = new double[predictors.length][columns.size() + 1];
    for (int i = 0; i < predictors.length; i++) {
      System.arraycopy(predictors[i], 0, rows[i], 0, columns.size());
      rows[i][columns.size()] = outcome[i];
    }
    final List<String> names = new ArrayList<>(columns);
    names.add(OUTCOME);
    return DataFrame.of(rows, names.toArray(String[]::new));
  }

  /**
   * Predicts one row.
   *
   * <p>The outcome slot is present but unread — the formula names it as the left-hand side, and a
   * tuple missing it would not match the schema the ensemble was fitted against.
   */
  public double predict(final double[] row) {
    final double[] padded = new double[columns.size() + 1];
    System.arraycopy(row, 0, padded, 0, Math.min(row.length, columns.size()));
    final List<String> names = new ArrayList<>(columns);
    names.add(OUTCOME);
    return model.predict(Tuple.of(DataFrame.of(new double[][] {padded},
        names.toArray(String[]::new)).schema(), padded));
  }

  /** Relative contribution of each column, in the order the columns were given. */
  public double[] importance() {
    return model.importance().clone();
  }

  public int trees() {
    return trees;
  }

  public int maxNodes() {
    return maxNodes;
  }

  public double shrinkage() {
    return shrinkage;
  }
}
