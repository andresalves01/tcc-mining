package app.labels;

/**
 * One row of the sequential exclusion table.
 *
 * <p>The denominator is carried explicitly rather than inferred, because a percentage without its
 * base is unreadable: 3% of what entered this stage and 3% of the corpus are different claims, and
 * a table that mixes them cannot be audited.
 *
 * @param denominator how many issues entered this stage, which is the previous stage's retained
 *     count
 */
public record FunnelStage(String name, long retained, long excluded, long denominator) {

  /** Share of what entered this stage that this stage removed. */
  public double incrementalShare() {
    return denominator == 0 ? 0 : (double) excluded / denominator;
  }
}
