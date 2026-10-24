package app.contribution.port;

import app.contribution.ContributionScan;
import java.util.Map;
import java.util.Set;

/**
 * Where the per-commit volume comes from.
 *
 * <p>Takes the identity map as an argument instead of fetching it, so the dependency runs one way:
 * the ledger is derived from the reconstruction, never the other way round. It also makes the
 * failure mode explicit — hand it an empty map and it can resolve nobody, which the task checks
 * before the scan rather than after an hour of reading.
 */
public interface ContributionSource {

  /**
   * Restricted to projects whose name contains {@code pilot}.
   *
   * <p>Present on the port rather than only on the adapter because "run the small version first" is
   * a property of the use case, not of MongoDB: the stage is validated on one repository before it
   * is trusted with the corpus.
   */
  ContributionScan scan(Map<String, String> canonicalByPerson, Set<String> bots, String pilot)
      throws Exception;
}
