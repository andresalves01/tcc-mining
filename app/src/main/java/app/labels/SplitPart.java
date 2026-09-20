package app.labels;

/**
 * Which side of the chronological split a label falls on.
 *
 * <p>The two exclusions are named rather than lumped into one, because they exclude for unrelated
 * reasons and their sizes are read differently: one is a property of the issue's own timing, the
 * other a property of its author's history.
 */
public enum SplitPart {
  /** Created and resolved before the cut, by a developer with enough prior history. */
  TRAIN,
  /** Created at or after the cut, by a developer with enough prior history. */
  TEST,
  /**
   * Created before the cut, resolved after it. Excluded from both sides: its outcome was not known
   * at the cut, so training on it would use the future; and it was not created in the test period,
   * so it does not belong to the future either.
   */
  CROSSES_CUT,
  /** Its author had fewer than the required resolutions before the cut. */
  INSUFFICIENT_HISTORY
}
