package app.paper5.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The non-linear arm: that it learns, and that it gives the same answer twice. */
class ExtraTreesTest {

  private static final List<String> COLUMNS = List.of("a", "b", "c");

  /**
   * A function no linear model fits well: the outcome turns on whether two features agree in sign.
   * Ensembles of trees pick it up; a plane cannot.
   */
  private static double[][] predictors(final int n, final long seed) {
    final Random random = new Random(seed);
    final double[][] rows = new double[n][3];
    for (int i = 0; i < n; i++) {
      rows[i][0] = random.nextGaussian();
      rows[i][1] = random.nextGaussian();
      rows[i][2] = random.nextGaussian();
    }
    return rows;
  }

  private static double[] outcomes(final double[][] rows) {
    final double[] y = new double[rows.length];
    for (int i = 0; i < rows.length; i++) {
      y[i] = rows[i][0] * rows[i][1] > 0 ? 3.0 : -3.0;
    }
    return y;
  }

  private static double meanAbsoluteError(final double[] predicted, final double[] actual) {
    double sum = 0;
    for (int i = 0; i < predicted.length; i++) {
      sum += Math.abs(predicted[i] - actual[i]);
    }
    return sum / predicted.length;
  }

  @Test
  @DisplayName("it learns an interaction that a plane cannot represent")
  void itLearnsSomething() {
    final double[][] fit = predictors(600, 1);
    final double[][] score = predictors(200, 2);

    final ExtraTrees model = ExtraTrees.fit(fit, outcomes(fit), COLUMNS,
        new ExtraTrees.Settings(300, 12, 2, 0.5, 42));
    final double[] predicted = model.predictAll(score);
    final double[] actual = outcomes(score);

    double mean = 0;
    for (final double value : outcomes(fit)) {
      mean += value;
    }
    mean /= fit.length;
    final double[] constant = new double[score.length];
    java.util.Arrays.fill(constant, mean);

    assertTrue(meanAbsoluteError(predicted, actual) < meanAbsoluteError(constant, actual),
        "an ensemble that cannot beat the training mean on this function is not fitted at all");
  }

  @Test
  @DisplayName("the same seed gives byte-identical predictions, so a result can be reproduced")
  void theSeedMakesItDeterministic() {
    final double[][] fit = predictors(300, 3);
    final double[] y = outcomes(fit);
    final double[][] score = predictors(60, 4);
    final ExtraTrees.Settings settings = new ExtraTrees.Settings(120, 10, 4, 0.5, 7);

    final double[] first = ExtraTrees.fit(fit, y, COLUMNS, settings).predictAll(score);
    final double[] second = ExtraTrees.fit(fit, y, COLUMNS, settings).predictAll(score);

    assertArrayEquals(first, second, 0.0,
        "section 8.3 requires the run to be reproducible, including under whatever threading the "
            + "library does internally");
  }

  @Test
  @DisplayName("a different seed gives different trees, so the seed is really being used")
  void theSeedIsActuallyRead() {
    // Without this, a trainer that silently ignored the seed would pass the determinism test.
    final double[][] fit = predictors(300, 5);
    final double[] y = outcomes(fit);
    final double[][] score = predictors(60, 6);

    final double[] first =
        ExtraTrees.fit(fit, y, COLUMNS, new ExtraTrees.Settings(60, 8, 4, 0.5, 11)).predictAll(score);
    final double[] second =
        ExtraTrees.fit(fit, y, COLUMNS, new ExtraTrees.Settings(60, 8, 4, 0.5, 99)).predictAll(score);

    boolean anyDifference = false;
    for (int i = 0; i < first.length && !anyDifference; i++) {
      anyDifference = Math.abs(first[i] - second[i]) > 1e-12;
    }
    assertTrue(anyDifference, "two seeds produced identical predictions: the seed is not reaching "
        + "the random split points, and this is a Random Forest by another name");
  }

  @Test
  @DisplayName("the declared grid is small and fixed, not swept")
  void theGridIsDeclaredInAdvance() {
    final List<ExtraTrees.Settings> grid = ExtraTrees.Settings.grid(42);

    assertTrue(grid.size() <= 8, "a wide sweep over an ensemble fits the selection set");
    assertTrue(grid.stream().allMatch(s -> s.seed() == 42), "one seed across the grid");
    assertFalse(grid.isEmpty());
  }
}
