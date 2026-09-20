package app.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cost curve is small enough to be proved rather than sampled, so these tests pin every
 * property the search downstream is entitled to assume.
 */
class CostCurveTest {

  /** The grid the sensitivity analysis sweeps; every property below must hold across all of it. */
  private static final List<Double> RATIOS = List.of(1.0, 1.25, 1.5, 1.7283, 2.0, 3.0);
  private static final double EPS = 1e-12;

  @Nested
  @DisplayName("endpoints")
  class Endpoints {

    @Test
    @DisplayName("C(0) is C_min and C(1) is C_max, for every ratio in the sweep")
    void anchorsAtBothEnds() {
      for (final double ratio : RATIOS) {
        final CostParams params = new CostParams(1_000, ratio);
        final CostCurve curve = new CostCurve(params);
        assertEquals(params.minimum(), curve.at(0), EPS, "C(0) at ratio " + ratio);
        assertEquals(params.maximum(), curve.at(1), EPS, "C(1) at ratio " + ratio);
      }
    }

    @Test
    @DisplayName("C(0.5) = C_min * r^0.75")
    void matchesTheClosedFormMidpoint() {
      // Independent of the implementation: derived by hand from 2q - q^2 at q = 0.5.
      for (final double ratio : RATIOS) {
        final CostCurve curve = new CostCurve(new CostParams(1_000, ratio));
        assertEquals(1_000 * Math.pow(ratio, 0.75), curve.at(0.5), 1e-9, "ratio " + ratio);
      }
    }
  }

  @Nested
  @DisplayName("shape")
  class Shape {

    @Test
    @DisplayName("non-decreasing across the whole range")
    void neverTurnsDownward() {
      for (final double ratio : RATIOS) {
        final CostCurve curve = new CostCurve(new CostParams(1_000, ratio));
        double previous = curve.at(0);
        for (int step = 1; step <= 1_000; step++) {
          final double current = curve.at(step / 1_000.0);
          assertTrue(current >= previous - EPS,
              "cost fell between " + (step - 1) / 1_000.0 + " and " + step / 1_000.0);
          previous = current;
        }
      }
    }

    @Test
    @DisplayName("flattens at the top -- the marginal cost of seniority vanishes at q = 1")
    void derivativeIsZeroAtTheTop() {
      final CostCurve curve = new CostCurve(new CostParams(1_000, 3.0));
      final double h = 1e-6;
      final double slopeAtTop = (curve.at(1) - curve.at(1 - h)) / h;
      final double slopeAtBottom = (curve.at(h) - curve.at(0)) / h;
      assertEquals(0, slopeAtTop, 1e-2);
      assertTrue(slopeAtBottom > 1_000, "the curve must actually climb at the bottom");
    }

    @Test
    @DisplayName("ratio 1 degenerates to a flat curve instead of NaN")
    void flatWhenThereIsNoSpread() {
      // The null scenario of the sweep. ln(1) = 0 zeroes both coefficients, which has to produce
      // C_min everywhere rather than a division by zero downstream in normalisation.
      final CostParams params = new CostParams(500, 1.0);
      final CostCurve curve = new CostCurve(params);
      assertEquals(0, params.delta1(), EPS);
      assertEquals(0, params.delta2(), EPS);
      for (double q = 0; q <= 1; q += 0.1) {
        assertEquals(500, curve.at(q), EPS);
      }
    }
  }

  @Test
  @DisplayName("the power form and the log-linear form the paper states agree")
  void bothFormsAgree() {
    for (final double ratio : RATIOS) {
      final CostCurve curve = new CostCurve(new CostParams(1_234.5, ratio));
      for (double q = 0; q <= 1.0000001; q += 0.05) {
        final double at = Math.min(q, 1);
        assertEquals(curve.at(at), curve.byLogForm(at), 1e-9, "ratio " + ratio + " q " + at);
      }
    }
  }

  @Test
  @DisplayName("a q outside [0, 1] is rejected, not clamped")
  void refusesRanksOutsideTheUnitInterval() {
    // Past q = 1 the parabola turns back down, so clamping would quietly return a cost that is
    // both wrong and plausible. A q out of range means the ranking upstream is broken.
    final CostCurve curve = new CostCurve(new CostParams(1_000, 2.0));
    assertThrows(IllegalArgumentException.class, () -> curve.at(1.0001));
    assertThrows(IllegalArgumentException.class, () -> curve.at(-1e-9));
    assertThrows(IllegalArgumentException.class, () -> curve.at(Double.NaN));
  }

  @Test
  @DisplayName("parameters that cannot describe a cost curve are rejected at construction")
  void rejectsImpossibleParameters() {
    assertThrows(IllegalArgumentException.class, () -> new CostParams(0, 2));
    assertThrows(IllegalArgumentException.class, () -> new CostParams(-1, 2));
    assertThrows(IllegalArgumentException.class, () -> new CostParams(1, 0.99));
    assertThrows(IllegalArgumentException.class, () -> new CostParams(1, Double.NaN));
  }

  @Test
  @DisplayName("evaluating candidates never mutates the parameters")
  void evaluationIsPure() {
    // The search calls this once per candidate per generation per run; state here would make the
    // reported front depend on evaluation order.
    final CostParams params = new CostParams(1_000, 1.7283);
    final CostCurve curve = new CostCurve(params);
    for (int i = 0; i < 100_000; i++) {
      curve.at((i % 1_001) / 1_000.0);
    }
    assertEquals(new CostParams(1_000, 1.7283), curve.params());
  }
}
