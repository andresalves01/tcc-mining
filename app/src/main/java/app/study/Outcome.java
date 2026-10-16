package app.study;

/** A row's project and its true duration: all the mandatory reference and the target need. */
public interface Outcome {

  String project();

  double durationHours();

  /**
   * The duration the models fit and are scored on: the hours, unless the study measures its
   * target in another unit (the in-progress similarity chain's business days).
   */
  default double duration() {
    return durationHours();
  }
}
