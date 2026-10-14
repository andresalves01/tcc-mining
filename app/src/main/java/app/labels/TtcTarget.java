package app.labels;

/**
 * Which duration of a label the fit treats as the outcome.
 *
 * <p>Replaces the earlier selector, which chose the interval at labelling time. Choosing here is
 * better for a concrete reason: the three targets come to share the same population, the same
 * opening date and, as a consequence, the <b>same temporal split</b> -- which is what makes it
 * legitimate to add the predictions of the two components and call the sum the prediction of the
 * total. Choosing in the label, each arm recomputed its own cut over its own population and the
 * predictions were not commensurable.
 *
 * <p>The three intervals are the same as before: {@link #TOTAL} was {@code OPEN_TO_MERGE},
 * {@link #WAIT} was {@code OPEN_TO_REVIEW} and {@link #AUTHOR} was {@code REVIEW_TO_MERGE}.
 */
public enum TtcTarget {

  /** Opening to merge. The whole life, and the outcome the article reports. */
  TOTAL,

  /**
   * Opening to the first external examination. Predominantly waiting for a reviewer: in this
   * corpus the code already exists when the pull request opens -- 72.0% of the datavies pull
   * requests have all their commits before the opening, the median is one commit per pull request,
   * and drafts appear in 0.8% of them. It serves as a negative control: the history of whoever
   * wrote it should not predict how long a reviewer takes to show up.
   */
  WAIT,

  /** Opening to merge, with weekends discounted. See {@link WorkingTime}. */
  TOTAL_WORKING,

  /** Opening to the first examination, with weekends discounted. */
  WAIT_WORKING,

  /** First examination to merge, with weekends discounted. */
  AUTHOR_WORKING,

  /**
   * First external examination to merge. Where the author's work concentrates, without being only
   * that -- it also holds waiting for re-review and for whoever presses the button. 24.2% of the
   * datavies pull requests have commits on both sides of the opening, so neither segment isolates
   * anyone's work; they are predominances, and the article must say so.
   */
  AUTHOR
}
