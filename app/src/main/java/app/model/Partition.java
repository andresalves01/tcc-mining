package app.model;

/**
 * The partitions the model is allowed to read.
 *
 * <p><b>The held-out test set is deliberately not here.</b> The design depends on it being opened
 * once, and "once" is only verifiable if opening it costs a code change that shows up in a diff. A
 * third constant would make it a one-character mistake.
 */
public enum Partition {

  /** Inside the training side, before the inner cut: where coefficients are estimated. */
  FIT,

  /** Inside the training side, after the inner cut: where alpha and lambda are chosen. */
  SELECTION
}
