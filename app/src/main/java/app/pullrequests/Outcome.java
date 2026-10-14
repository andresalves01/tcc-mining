package app.pullrequests;

/**
 * A row's project and its true duration: all the mandatory reference and the target need.
 *
 * <p>The same reason as {@link Construct}. A merged pull request and a resolved issue are different
 * records, but the per-project median and {@code log1p} of the hours are the same computation, and
 * sharing it is what makes the two studies' baselines comparable by construction.
 */
public interface Outcome {

  String project();

  double durationHours();
}
