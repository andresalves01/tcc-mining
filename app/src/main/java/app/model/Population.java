package app.model;

/**
 * Which labels the sample keeps, judged by where the resolution falls relative to the first linked
 * commit.
 *
 * <p><b>This is a filter, never a predictor.</b> The interval it reads is measured after the work
 * happened and so could not be known when the task was created; letting it into the design matrix
 * would be leakage. It selects the population being modelled and stops there.
 *
 * <p>Carried as a parameter beside {@link Bounds} for the same reason that one is: the question is
 * which population a study of issue lifetime is actually modelling, and the only honest way to
 * answer it is to report every arm from one run. Filtering upstream would make the others
 * unavailable.
 *
 * <p><b>The thresholds were fixed before the arms were run</b>, in the analysis 5
 * pre-registration, and the commit order is what makes that
 * checkable. One minute is the primary cut because it is the only one where no human decision fits
 * between the commit and the close; one hour is carried beside it as a declared sensitivity arm,
 * not as an alternative to be chosen once the scores are in.
 */
public record Population(String name, double minPostCommitDays) {

  /** A minute, in fractional days -- the unit {@code postCommitDays} is measured in. */
  public static final double ONE_MINUTE = 1.0 / 1440.0;

  public static final double ONE_HOUR = 1.0 / 24.0;

  /** Every label, including those whose first linked commit postdates the resolution. */
  public static Population all() {
    return new Population("todos", Double.NEGATIVE_INFINITY);
  }

  /** Drops the labels resolved before their first linked commit was authored. */
  public static Population withoutPostdated() {
    return new Population("no postdated", 0);
  }

  /** Primary arm: also drops the labels closed within a minute of the first linked commit. */
  public static Population withoutImmediateByMinute() {
    return new Population("no postdated nor <1min", ONE_MINUTE);
  }

  /** Declared sensitivity arm, reported beside the primary one rather than instead of it. */
  public static Population withoutImmediateByHour() {
    return new Population("no postdated nor <1h", ONE_HOUR);
  }

  public boolean restricts() {
    return minPostCommitDays > Double.NEGATIVE_INFINITY;
  }

  /**
   * Whether this population keeps the row.
   *
   * <p>A row whose interval is unknown is kept only by the unrestricted population. Scoring it as
   * zero would silently sort it into the postdated group, and dropping it everywhere would shrink
   * the unrestricted arm that the others are compared against — so it is admitted where no claim
   * depends on the value and refused where one does. {@link app.pipeline.FitModelTask} counts the
   * refusals so that a source which forgot to carry the interval cannot pass unnoticed.
   */
  public boolean admits(final Observation row) {
    if (!restricts()) {
      return true;
    }
    return !Double.isNaN(row.postCommitDays()) && row.postCommitDays() >= minPostCommitDays;
  }

  /** True when the row would be refused only because the interval is missing. */
  public boolean refusesForMissing(final Observation row) {
    return restricts() && Double.isNaN(row.postCommitDays());
  }
}
