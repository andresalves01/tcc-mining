package app.model;

/**
 * One row the regression sees: the outcome, the predictors, and enough identity to build the
 * per-pair baseline the study compares against.
 *
 * @param values in the order of the column names that came with them, already transformed but not
 *     yet standardised -- scaling uses the fit side's statistics and so belongs to the fit, not to
 *     the reader
 * @param postCommitDays resolution minus the first linked commit, in fractional days, negative
 *     when the commit postdates the resolution. {@link Population} reads it to select the sample
 *     and nothing else does: it is measured after the fact and is not a predictor. NaN means the
 *     source did not carry it, which only the unrestricted population tolerates.
 */
public record Observation(
    String issueId, String project, String developer, double ttcDays, double[] values,
    double postCommitDays) {

  /**
   * For callers that do not know the post-commit interval -- the in-memory doubles in the tests.
   * Real sources carry it, and {@link Population} refuses such a row wherever the value matters.
   */
  public Observation(final String issueId, final String project, final String developer,
      final double ttcDays, final double[] values) {
    this(issueId, project, developer, ttcDays, values, Double.NaN);
  }

  /** The outcome on the scale the model is fitted on. */
  public double logTtc() {
    // The same 1e-6 the mongosh prototypes used, so the two are comparable. A label can resolve in
    // under a second, and ln(0) is undefined.
    return Math.log(ttcDays + 1e-6);
  }

  /** The key the study's primary reference is computed over. */
  public String pair() {
    return developer + '\u0000' + project;
  }
}
