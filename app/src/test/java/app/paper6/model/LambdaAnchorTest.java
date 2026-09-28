package app.paper6.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import app.model.ElasticNetModel;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the grid's top belongs: at the smallest penalty that sets every coefficient to zero, for
 * each mixture separately. Pinned empirically, because the estimator multiplies lambda by alpha
 * before it reaches the L1 term, and a grid anchored without that factor stops short of it.
 */
class LambdaAnchorTest {

  @Test
  @DisplayName("each mixture's top penalty zeroes everything, and a little less does not")
  void theTopOfTheGridIsTheAllZeroPoint() {
    final Random random = new Random(1);
    final int n = 4_000;
    final double[][] x = new double[n][3];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      for (int c = 0; c < 3; c++) {
        x[i][c] = random.nextGaussian();
      }
      y[i] = 0.8 * x[i][0] - 0.3 * x[i][1] + random.nextGaussian();
    }
    for (final double alpha : Fitting.ALPHAS) {
      final double top = Fitting.lambdas(x, y, alpha)[0];
      final ElasticNetModel atTop = ElasticNetModel.fit(x, y, List.of("a", "b", "c"), alpha, top);
      final ElasticNetModel below =
          ElasticNetModel.fit(x, y, List.of("a", "b", "c"), alpha, top / 10);
      double largestAtTop = 0;
      double largestBelow = 0;
      for (final double c : atTop.coefficients()) {
        largestAtTop = Math.max(largestAtTop, Math.abs(c));
      }
      for (final double c : below.coefficients()) {
        largestBelow = Math.max(largestBelow, Math.abs(c));
      }
      // Measured with the real library (28/09/2026): at 2 * max|X'r| / alpha the largest
      // coefficient is 0,0000 for all three mixtures on 4.000 rows; at 1,5 times it is still 0,02 to
      // 0,19. On 400 rows the solver leaves a residue of 0,002 to 0,015 exactly at the boundary,
      // which is why the fixture is not smaller.
      assertTrue(largestAtTop < 1e-3, "alpha " + alpha + ": the top should leave only solver "
          + "residue, got " + largestAtTop);
      assertTrue(largestBelow > 10 * largestAtTop + 0.05,
          "alpha " + alpha + ": a decade below the top the effects are alive, got " + largestBelow);
    }
  }
}
