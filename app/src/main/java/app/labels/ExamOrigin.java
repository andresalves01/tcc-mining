package app.labels;

/**
 * How the first external examination of a task was recognised.
 *
 * <p>Measured over the 18,786 merged pull requests: a formal review exists in 56.4% of them, and a
 * third party's comment takes the coverage to 78.1%. Counting only formal reviews is not
 * conservative, it is biased -- the median fraction of the life spent before the boundary falls
 * from 0.290 to 0.126 when the comment enters, because in 21.6% of the cases someone commented well
 * before any review was submitted.
 *
 * <p>The authorship filter is not a detail: 12,716 pull requests have some comment, but only 10,570
 * have a comment from someone who is not an author of the commits. The rest is the author talking
 * alone, which is nobody examining anything.
 *
 * <p>Absence is not a value of this enum. The 21.9% with no recorded examination carry an empty
 * examination, and the two parts of the duration are absent for them -- saying they waited their
 * whole life would claim nobody looked, and saying they waited zero would claim the opposite.
 */
public enum ExamOrigin {

  /** A submitted formal review, from {@code pull_request_review}. */
  REVIEW,

  /** A comment from someone who is not an author of the commits, from {@code pull_request_comment}. */
  COMMENT
}
