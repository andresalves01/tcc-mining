package app.features.port;

import app.features.FeatureRow;
import java.time.Duration;
import java.util.Collection;

/**
 * Where the design matrix is kept, so that the regression fits a fixed set rather than one derived
 * afresh on every run.
 */
public interface FeatureStore {

  /**
   * Replaces the stored matrix with {@code rows}, recording the run alongside it.
   *
   * <p>Replaces rather than merges, for the reason the label store gives: the key space follows the
   * labels, and the labels shrink when a rule tightens. Rows from a looser run left behind would be
   * indistinguishable from current ones.
   *
   * @param teamWindow recorded with the rows, because a consumer that cannot read it back is a
   *     consumer that will hardcode ninety days and silently measure something else when it moves
   */
  long save(Collection<FeatureRow> rows, Duration teamWindow);

  /** Human-readable location, for logging and for naming itself in a refusal. */
  String describe();
}
