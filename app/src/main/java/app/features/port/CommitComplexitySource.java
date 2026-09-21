package app.features.port;

import app.features.CommitComplexity;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Reads the complexity of the methods each commit changed, straight from the corpus.
 *
 * <p>Results are handed to a sink rather than returned in a list. This is the one stage that reads
 * three collections per commit across a quarter of a million of them, and a run that accumulates
 * everything in memory and writes at the end loses all of it when the last hour fails -- which the
 * architecture notes already flag as this pipeline's standing weakness.
 */
public interface CommitComplexitySource {

  /**
   * @param commitIds the commits to read, already narrowed by the caller to the ones any predictor
   *     could ask about
   * @param sink receives one row per commit read, including commits that changed no recoverable
   *     method -- storing those is what lets a later run tell "read, found nothing" from "not read"
   */
  Report scan(Collection<String> commitIds, Consumer<CommitComplexity> sink);

  /**
   * @param withMethods commits that yielded at least one recoverable method
   * @param unrecoverable methods reached whose metrics the corpus never recorded, summed; reported
   *     rather than folded into the totals, because incomplete coverage is not zero complexity
   */
  record Report(int scanned, int withMethods, long methods, long unrecoverable) {}
}
