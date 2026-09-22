package app.paper4;

/**
 * Which interval of a pull request's life the label measures.
 *
 * <p>Measured over the 8,673 labels: the median pull request spends 22.7% of its life waiting for
 * a first review and the rest in the cycle after it, and 21.0% of them spend more than nine tenths
 * of it waiting. Those two parts answer different questions, and the one the study is actually
 * asking -- whether what a developer has done predicts how they work -- is contaminated by the
 * first, because how long a reviewer takes to arrive is not the author's doing.
 *
 * <p>The boundary between them is the earliest review submitted, falling back to the merge when
 * none was recorded. 51.3% of labels have a review; the rest fall back, which makes
 * {@link #OPEN_TO_REVIEW} equal to the whole life for them and {@link #REVIEW_TO_MERGE} exactly
 * zero -- so that arm excludes them rather than scoring a zero it would have to take the logarithm
 * of.
 */
public enum PullRequestOutcome {

  /** Opened to merged: the whole life, and what every number before this enum existed measured. */
  OPEN_TO_MERGE,

  /** Opened to the first review: how long the pull request waited to be looked at. */
  OPEN_TO_REVIEW,

  /** The first review to the merge: the part the author takes part in. */
  REVIEW_TO_MERGE
}
