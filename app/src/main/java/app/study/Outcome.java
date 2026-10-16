package app.study;

/** A row's project and its true duration: all the mandatory reference and the target need. */
public interface Outcome {

  String project();

  double durationHours();

  /**
   * The duration the models fit and are scored on, in the unit of the study's target: the hours,
   * or the in-progress similarity chain's business days.
   */
  double duration();
}
