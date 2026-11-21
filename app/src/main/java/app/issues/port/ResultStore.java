package app.issues.port;

import app.issues.SplitRule;
import java.util.List;
import java.util.Map;

/**
 * Where this study's own results are persisted, which is never one of the frozen databases.
 *
 * <p>A port rather than a Mongo call inside the task, for the same reason every other stage has one:
 * the task that scores the reserved partition has to be runnable, and testable, without a database.
 *
 * <p><b>Append, never replace.</b> The reserved partition is scored once, so a second run is an
 * event worth seeing rather than an update worth absorbing. An implementation that overwrote the
 * earlier document would make two runs indistinguishable from one, which is exactly the thing the
 * whole design is arranged to prevent.
 */
public interface ResultStore {

  /**
   * Records one execution and the rows it produced.
   *
   * @param run provenance: when, under which registration, over which population
   * @param rows one document per specification and per reference, each self-describing
   * @return the identifier of the execution, for the report to print
   */
  String record(Map<String, Object> run, List<Map<String, Object>> rows);

  /**
   * Whether a run of {@code stage} is already recorded; empty when this store cannot tell, which a
   * step that runs once reads as a refusal.
   */
  default java.util.Optional<Boolean> recorded(final String stage) {
    return java.util.Optional.empty();
  }

  /**
   * The store of the runs drawn under {@code rule}, in collections named with {@link
   * SplitRule#collectionSuffix}; a store without separate collections keeps them in itself.
   */
  default ResultStore design(final SplitRule rule) {
    return this;
  }

  /**
   * The k of the wait fence the first-commit matrix was extracted with: the latest extraction run
   * of this store's collection names it in {@code wait_fence_k}, and a run without one, or a store
   * that records no such run, is the official {@link app.issues.FirstCommit#DEFAULT_WAIT_K}.
   */
  default double waitFenceK() {
    return app.issues.FirstCommit.DEFAULT_WAIT_K;
  }

  /**
   * The recorded runs of {@code stage} in this store's collection, newest first, each as it was
   * recorded; empty when this store cannot tell, which a step that must know what made its input
   * reads as a refusal.
   */
  default java.util.Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
    return java.util.Optional.empty();
  }

  /**
   * How many runs this store already holds of a design other than the revised one ({@link
   * SplitRule#recordedBy}); empty when this store cannot tell, which a stage that must not mix
   * designs reads as a refusal.
   */
  default java.util.Optional<Long> otherDesignRuns() {
    return java.util.Optional.empty();
  }
}
