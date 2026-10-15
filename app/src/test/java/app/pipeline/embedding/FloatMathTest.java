package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smile.math.special.Erf;

/** The float exponential and erf against the double ones, computed at each point. */
class FloatMathTest {

  @Test
  @DisplayName("exp is within 2e-7 relative of Math.exp on a fine grid from -87 to 88, and exactly "
      + "1 at 0")
  void expWithinTolerance() {
    double worst = 0;
    for (int i = -8_700_000; i <= 8_800_000; i++) {
      final float x = i * 1e-5f + 3.7e-7f;
      final double exact = Math.exp(x);
      worst = Math.max(worst, Math.abs(FloatMath.exp(x) - exact) / exact);
    }
    assertTrue(worst < 2e-7, Double.toString(worst));
    assertEquals(1f, FloatMath.exp(0f));
  }

  @Test
  @DisplayName("exp below -87 is clamped there: tiny and positive, as a softmax weight that "
      + "small changes nothing")
  void expClampedBelow() {
    assertEquals(FloatMath.exp(-87f), FloatMath.exp(-1000f));
    assertTrue(FloatMath.exp(-1000f) > 0 && FloatMath.exp(-1000f) < 1e-37f);
  }

  @Test
  @DisplayName("erf is within 4e-7 of Smile's double erf on a fine grid from -10 to 10, odd, and "
      + "0 at 0")
  void erfWithinTolerance() {
    double worst = 0;
    for (int i = -1_000_000; i <= 1_000_000; i++) {
      final float x = i * 1e-5f + 3.7e-7f;
      worst = Math.max(worst, Math.abs(FloatMath.erf(x) - Erf.erf(x)));
      assertEquals(-FloatMath.erf(x), FloatMath.erf(-x));
    }
    assertTrue(worst < 4e-7, Double.toString(worst));
    assertEquals(0f, FloatMath.erf(0f));
  }
}
