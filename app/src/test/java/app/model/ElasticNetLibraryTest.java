package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.regression.ElasticNet;
import smile.regression.LinearModel;

/**
 * A contract test on the Elastic Net the study fits with, not a test of our own code.
 *
 * <p>It exists because two properties of this dependency are invisible from its signature and both
 * change how a result has to be worded.
 *
 * <p><b>The penalty is not normalised by the number of rows.</b> Measured on two thousand rows,
 * {@code lambda1} has to reach the thousands before it shrinks anything at all: from 0.001 to 10 a
 * coefficient of 3 moves by 0.08%. So the grid the parameter search sweeps has to be derived from
 * the data rather than taken from textbook values, and it scales with {@code n}.
 *
 * <p><b>Selection is not exact.</b> Between "nothing shrinks" and "everything dies" this estimator
 * leaves a coefficient that carries nothing at around {@code 1e-5}, never at zero — it solves the
 * LASSO by preconditioned conjugate gradient rather than by coordinate descent with soft
 * thresholding, and an interior-point path approaches zero without arriving. An exact zero appears
 * only in the degenerate case where every coefficient dies at once. The study says a non-zero
 * coefficient indicates selection by the estimator, so that sentence needs a declared threshold;
 * this test pins the behaviour the threshold has to be chosen against.
 */
class ElasticNetLibraryTest {

  private static DataFrame threeColumns(final long seed) {
    final Random random = new Random(seed);
    final double[][] data = new double[2_000][4];
    for (int i = 0; i < data.length; i++) {
      final double first = random.nextGaussian();
      final double second = random.nextGaussian();
      data[i] = new double[] {
          first, second, random.nextGaussian(),       // the third enters nothing but the columns
          3 * first - 2 * second + 0.05 * random.nextGaussian()};
    }
    return DataFrame.of(data, "x1", "x2", "noise", "y");
  }

  @Test
  @DisplayName("recovers a known relationship, and runs without the native BLAS being optional")
  void fitsAKnownRelationship() {
    final LinearModel model = ElasticNet.fit(Formula.lhs("y"), threeColumns(20260920L), 0.05, 0.05);
    final double[] coefficients = model.coefficients();

    assertEquals(3.0, coefficients[0], 0.05, "the first predictor's weight");
    assertEquals(-2.0, coefficients[1], 0.05, "and the second's, with its sign");
  }

  @Test
  @DisplayName("a column that carries nothing is shrunk to negligible, but not to exactly zero")
  void selectionIsNotExact() {
    final double[] coefficients =
        ElasticNet.fit(Formula.lhs("y"), threeColumns(20260920L), 0.05, 0.05).coefficients();

    assertTrue(coefficients[2] != 0.0,
        "if this ever becomes an exact zero the estimator has changed, and the declared "
            + "selection threshold can be dropped rather than quietly kept");
    assertTrue(Math.abs(coefficients[2]) < 1e-3,
        "it is still five orders of magnitude below the real weights, got " + coefficients[2]);
    assertTrue(Math.abs(coefficients[2]) < Math.abs(coefficients[0]) / 1_000,
        "negligible is relative to the coefficients it sits beside, which is what a threshold "
            + "has to be stated against");
  }

  @Test
  @DisplayName("the penalty does bite, but only on a scale that grows with the number of rows")
  void theGridHasToComeFromTheData() {
    final DataFrame frame = threeColumns(7L);
    final double light = ElasticNet.fit(Formula.lhs("y"), frame, 1.0, 0.01).coefficients()[0];
    final double heavy = ElasticNet.fit(Formula.lhs("y"), frame, 5_000.0, 0.01).coefficients()[0];
    final double crushing =
        ElasticNet.fit(Formula.lhs("y"), frame, 20_000.0, 0.01).coefficients()[0];

    assertEquals(3.0, light, 0.05, "on two thousand rows, a penalty of one is no penalty");
    assertTrue(heavy < light * 0.75, "five thousand finally shrinks it, got " + heavy);
    assertEquals(0.0, crushing, 0.0,
        "and past that everything dies at once -- the only place an exact zero appears");
  }
}
