package app.cost;

/**
 * {@code ln C(q) = delta0 + delta1 * q + delta2 * q^2}, evaluated.
 *
 * <p>Equivalently {@code C(q) = C_min * ratio^(2q - q^2)}. Both forms are computed below and the
 * tests assert they agree: the log form is the one the paper states, the power form is the one that
 * stays exact at the endpoints.
 *
 * <p>Deliberately free of state. The search evaluates this for every candidate in every generation
 * of every run, so an instance that could be mutated mid-search would make results depend on
 * evaluation order.
 */
public final class CostCurve {

  private final CostParams params;

  public CostCurve(final CostParams params) {
    this.params = params;
  }

  /**
   * Cost of a developer at relative seniority {@code q}.
   *
   * @param q in {@code [0, 1]}; outside that range the curve turns back downward, so it is rejected
   *     rather than clamped — a {@code q} out of range means the ranking upstream is wrong, and
   *     silently clamping would hide it
   */
  public double at(final double q) {
    if (!(q >= 0 && q <= 1)) {
      throw new IllegalArgumentException("q must lie in [0, 1]: " + q);
    }
    return params.minimum() * Math.pow(params.ratio(), 2 * q - q * q);
  }

  /** The same value by the stated log-linear form; used to hold the two definitions together. */
  public double byLogForm(final double q) {
    if (!(q >= 0 && q <= 1)) {
      throw new IllegalArgumentException("q must lie in [0, 1]: " + q);
    }
    return Math.exp(params.delta0() + params.delta1() * q + params.delta2() * q * q);
  }

  public CostParams params() {
    return params;
  }
}
