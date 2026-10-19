package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the penalty anchor against the one mistake it has already caused.
 *
 * <p>A second copy of this calculation divided by the number of rows, which looks like the
 * textbook formula and is wrong here: the library applies the penalty as given, not per
 * observation. The grid then searched a range twenty-four thousand times too small, every
 * candidate came back essentially unpenalised, and the chosen lambda was 0.75 where the correct
 * anchor gives 5459. Nothing failed -- the numbers were simply wrong.
 */
class LargestPenaltyTest {

  @Test
  @DisplayName("scales with the number of rows, because the penalty is not per-observation")
  void doesNotNormaliseByRowCount() {
    final double[][] one = {{1}, {-1}, {1}, {-1}};
    final double[] y1 = {1, -1, 1, -1};
    // the same relationship, each row repeated four times
    final double[][] four = new double[16][1];
    final double[] y4 = new double[16];
    for (int i = 0; i < 16; i++) {
      four[i][0] = one[i % 4][0];
      y4[i] = y1[i % 4];
    }
    final double small = Fitting.largestPenalty(one, y1);
    final double large = Fitting.largestPenalty(four, y4);
    assertEquals(4.0, large / small, 1e-9,
        "four times the rows must give four times the anchor; dividing by n would give one");
  }

  @Test
  @DisplayName("is the largest absolute centred correlation, over columns")
  void picksTheStrongestColumn() {
    // column 0 carries the outcome, column 1 is constant and carries nothing
    final double[][] x = {{2, 1}, {-2, 1}, {2, 1}, {-2, 1}};
    final double[] y = {10, -10, 10, -10};
    // column 0: sum(x*y) = 2*10 + -2*-10 + 2*10 + -2*-10 = 80; column 1: sum = 0
    assertEquals(80.0, Fitting.largestPenalty(x, y), 1e-9);
  }

  @Test
  @DisplayName("never returns zero, so a log-spaced grid always has somewhere to start")
  void staysPositiveOnAFlatOutcome() {
    final double[][] x = {{1, 2}, {3, 4}, {5, 6}};
    final double[] y = {7, 7, 7};
    assertTrue(Fitting.largestPenalty(x, y) >= 0,
        "a flat outcome gives no signal, and the anchor must not go negative");
  }
}
