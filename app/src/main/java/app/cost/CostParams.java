package app.cost;

/**
 * The two numbers that fix the whole cost curve.
 *
 * <p>Nothing here is mined. Requiring {@code C(0) = minimum}, {@code C(1) = minimum * ratio} and a
 * flat top ({@code dC/dq = 0} at {@code q = 1}) determines the quadratic completely, so the curve
 * carries no free parameters beyond these two. That is why cost is <b>not</b> stored in the ledger:
 * {@code ratio} is a scenario knob swept over {@code {1, 1.25, 1.5, 1.7283, 2, 3}}, and a
 * materialised cost column would freeze one scenario and disguise an assumption as a measurement.
 *
 * @param minimum cost of the least senior developer, {@code C_min}; the unit is arbitrary and
 *     cancels in any ratio the search reports
 * @param ratio {@code C_max / C_min}; {@code 1} is the degenerate flat curve, and it must work
 *     rather than divide by zero, because it is the natural null case for the sensitivity sweep
 */
public record CostParams(double minimum, double ratio) {

  public CostParams {
    if (!(minimum > 0) || !Double.isFinite(minimum)) {
      throw new IllegalArgumentException("minimum cost must be finite and positive: " + minimum);
    }
    if (!(ratio >= 1) || !Double.isFinite(ratio)) {
      throw new IllegalArgumentException("ratio C_max/C_min must be finite and >= 1: " + ratio);
    }
  }

  public double maximum() {
    return minimum * ratio;
  }

  /** {@code ln C(0)}. */
  public double delta0() {
    return Math.log(minimum);
  }

  /** Coefficient of {@code q}. */
  public double delta1() {
    return 2 * Math.log(ratio);
  }

  /** Coefficient of {@code q^2}; negative, which is what bends the curve flat at the top. */
  public double delta2() {
    return -Math.log(ratio);
  }
}
