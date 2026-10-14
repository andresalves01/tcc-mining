package app.pullrequests.port;

import app.pullrequests.Tables;
import java.util.Collection;
import java.util.Set;

/**
 * Loads the event tables the replication computes from, read-only.
 *
 * <p>It returns tables rather than answering questions about a unit, and that is the design: a
 * source that filtered by {@code t0} as it read could never be handed to the time-travel test,
 * because there would be nothing dated left to take away. Everything the corpus knows comes across
 * once, and every temporal rule is applied afterwards, in one place, by code a unit test can reach.
 */
public interface TableSource {

  /**
   * @param populationIds the units whose title and body are measured. History is loaded for the
   *     whole corpus -- a creator's earlier pull requests count whether or not they are in the
   *     population -- but free text is only read for the rows that need it
   */
  Tables load(Collection<String> populationIds);

  /** The collections this reads, for the corpus check to print. */
  Set<String> reads();

  String describe();
}
