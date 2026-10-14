package app.model;

/**
 * A duration band the sample may be restricted to before fitting.
 *
 * <p>Carried as a parameter rather than applied upstream because whether to restrict is still an
 * open question of method, and the only honest way to answer it is to report both arms from the
 * same run. Filtering when the matrix was built would have made the unrestricted arm unavailable.
 *
 * <p>The restricted band is the one the decision records name and the paper's text denies: below
 * an hour a task was plausibly already under way before it was filed, and above a year the outcome
 * is dominated by things none of these predictors measure. Neither threshold was chosen by what it
 * does to a score.
 */
public record Bounds(String name, double minDays, double maxDays) {

  public static Bounds none() {
    return new Bounds("no bounds", 0, Double.MAX_VALUE);
  }

  public static Bounds oneHourToOneYear() {
    return new Bounds("1h <= TTC <= 365d", 1.0 / 24, 365);
  }

  public boolean admits(final Observation row) {
    return row.ttcDays() >= minDays && row.ttcDays() <= maxDays;
  }

  public boolean restricts() {
    return minDays > 0 || maxDays < Double.MAX_VALUE;
  }
}
