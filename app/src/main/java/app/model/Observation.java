package app.model;

/**
 * One row the regression sees: the outcome, the predictors, and enough identity to build the
 * per-pair baseline the study compares against.
 *
 * @param values in the order of the column names that came with them, already transformed but not
 *     yet standardised -- scaling uses the fit side's statistics and so belongs to the fit, not to
 *     the reader
 */
public record Observation(
    String issueId, String project, String developer, double ttcDays, double[] values) {

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
