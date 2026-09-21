package app.model;

/**
 * The partitions the model reads.
 *
 * <p><b>The held-out set was opened on 2026-09-21, at André's explicit direction, before the
 * specification was final.</b> This constant is the code change that records it — the design
 * depended on the set being opened once, and the only thing that makes "once" checkable is that
 * reaching it shows up in a diff. It now has. What was measured after this point is exploratory and
 * has to be reported as such: the accumulated complexity did not exist yet, the duration bound was
 * undecided, and the scope of the study was being narrowed the same day.
 */
public enum Partition {

  /** Inside the training side, before the inner cut: where coefficients are estimated. */
  FIT,

  /** Inside the training side, after the inner cut: where alpha and lambda are chosen. */
  SELECTION,

  /** The whole training side, which the final refit uses once the parameters are chosen. */
  TRAIN_ALL,

  /** Created after the outer cut. Opened once; see the note above for when and in what state. */
  HELD_OUT
}
