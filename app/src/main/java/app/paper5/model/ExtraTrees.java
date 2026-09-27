package app.paper5.model;

import java.util.ArrayList;
import java.util.List;
import org.tribuo.Dataset;
import org.tribuo.Example;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.impl.ArrayExample;
import org.tribuo.common.tree.ExtraTreesTrainer;
import org.tribuo.ensemble.EnsembleCombiner;
import org.tribuo.provenance.SimpleDataSourceProvenance;
import org.tribuo.regression.RegressionFactory;
import org.tribuo.regression.Regressor;
import org.tribuo.regression.ensemble.AveragingCombiner;
import org.tribuo.regression.rtree.CARTRegressionTrainer;
import org.tribuo.regression.rtree.impurity.MeanSquaredError;

/**
 * The non-linear contrast: extremely randomised trees.
 *
 * <p>Wrapped so that the rest of the study never names a library, the same way {@code
 * ElasticNetModel} wraps the linear estimator. The estimator is Tribuo's, because Smile -- which
 * this codebase already uses for the Elastic Net -- ships Random Forest and gradient boosting and
 * has no extremely randomised variant at all.
 *
 * <p><b>What makes it Extra Trees and not a Random Forest.</b> The inner tree is a CART regression
 * tree built with {@code useRandomSplitPoints}: at each node a subset of features is considered and
 * the split threshold is <i>drawn at random</i> within the feature's range rather than chosen to
 * minimise impurity. The ensemble draws no bootstrap sample -- every tree sees every row, and the
 * variance comes from the random thresholds. Getting either half wrong would produce a Random
 * Forest wearing the wrong name.
 *
 * <p><b>Reproducibility is a requirement, not a nicety.</b> Section 8.3 asks for a fixed seed, a
 * fixed number of trees, depth and leaf size chosen on training data only, and predictions that do
 * not move when the work is spread over threads. The seed is a constructor argument, every
 * hyperparameter arrives from the caller's declared grid, and {@code ExtraTreesModelTest} fits the
 * same data twice and requires the predictions to be identical.
 */
public final class ExtraTrees {

  /** What the specification asks to be declared, and what the report records next to each result. */
  public record Settings(int trees, int maxDepth, int minChildWeight, double featureFraction,
      long seed) {

    /**
     * The grid, declared before any of it was run.
     *
     * <p>Small on purpose: a wide sweep over an ensemble is a way of fitting the selection set, and
     * the inner split is the only thing standing between this study and the reserved side.
     *
     * <p><b>Every feature is considered at each node</b> ({@code featureFraction = 1}), so the
     * randomisation lives entirely in the split thresholds. That is what distinguishes this
     * estimator from a Random Forest, and holding the feature subset fixed keeps the comparison
     * about the thresholds rather than about two kinds of randomness at once.
     *
     * <p><b>Leaves below eight are not in the grid, and that is a limitation rather than a
     * choice.</b> Tribuo 4.3.2 throws {@code bound must be positive} from
     * {@code RegressorTrainingNode.buildRandomTree} on small leaves at depth twelve and beyond: the
     * method guards a feature with one distinct value at the node and not one with none, so
     * {@code rng.nextInt(feature.size() - 1)} is called with {@code -1}. Measured here across the
     * grid: every point with a leaf of two failed at depth twelve or twenty, and the leaf of eight
     * survived. Those points are excluded because they cannot be evaluated, and the fit records any
     * that still fail rather than quietly dropping them.
     */
    public static List<Settings> grid(final long seed) {
      final List<Settings> grid = new ArrayList<>();
      for (final int depth : new int[] {6, 12, 20}) {
        for (final int leaf : new int[] {8, 32}) {
          grid.add(new Settings(300, depth, leaf, 1.0, seed));
        }
      }
      return List.copyOf(grid);
    }

    @Override
    public String toString() {
      return "arvores=" + trees + " prof=" + maxDepth + " folha=" + minChildWeight
          + " frac=" + featureFraction + " semente=" + seed;
    }
  }

  private final Model<Regressor> model;
  private final Settings settings;
  private final String[] columns;

  private ExtraTrees(final Model<Regressor> model, final Settings settings,
      final String[] columns) {

    this.model = model;
    this.settings = settings;
    this.columns = columns.clone();
  }

  public static ExtraTrees fit(final double[][] predictors, final double[] outcome,
      final List<String> columnNames, final Settings settings) {

    if (predictors.length != outcome.length) {
      throw new IllegalArgumentException("rows and outcomes must line up");
    }
    final String[] columns = columnNames.toArray(new String[0]);
    final RegressionFactory factory = new RegressionFactory();
    final MutableDataset<Regressor> data = new MutableDataset<>(
        new SimpleDataSourceProvenance("paper5", factory), factory);
    for (int i = 0; i < predictors.length; i++) {
      data.add(example(columns, predictors[i], outcome[i]));
    }

    final CARTRegressionTrainer tree = new CARTRegressionTrainer(
        settings.maxDepth(),
        settings.minChildWeight(),
        0.0f,
        (float) settings.featureFraction(),
        /* useRandomSplitPoints */ true,
        new MeanSquaredError(),
        settings.seed());
    final EnsembleCombiner<Regressor> combiner = new AveragingCombiner();
    final ExtraTreesTrainer<Regressor> trainer =
        new ExtraTreesTrainer<>(tree, combiner, settings.trees(), settings.seed());

    return new ExtraTrees(trainer.train((Dataset<Regressor>) data), settings, columns);
  }

  public double predict(final double[] row) {
    final Example<Regressor> example = example(columns, row, 0.0);
    return model.predict(example).getOutput().getValues()[0];
  }

  public double[] predictAll(final double[][] rows) {
    final double[] predictions = new double[rows.length];
    for (int i = 0; i < rows.length; i++) {
      predictions[i] = predict(rows[i]);
    }
    return predictions;
  }

  public Settings settings() {
    return settings;
  }

  private static Example<Regressor> example(final String[] columns, final double[] values,
      final double outcome) {

    // A dense row: Tribuo's sparse example drops zeros, and a standardised zero is a real value
    // sitting at the fit side's mean, not an absent feature.
    final double[] copy = values.clone();
    return new ArrayExample<>(new Regressor("y", outcome), columns, copy);
  }
}
