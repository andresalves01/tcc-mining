package app.contribution.port;

import app.contribution.Contribution;
import java.util.Collection;
import java.util.List;

/** Where the ledger is kept. Mirrors {@code PersonRegistry}: the use case names no technology. */
public interface ContributionLedger {

  /** Upserts, keyed on the commit, so a re-run repairs rather than duplicates. */
  long save(Collection<Contribution> contributions);

  /**
   * The whole ledger, for the in-memory derivations built on top of it.
   *
   * <p>Feature generation indexes the complete history to answer queries at each issue's
   * creation time. At roughly 430,000 rows of fixed-width fields this is a few hundred megabytes.
   */
  List<Contribution> readAll();

  /** Human-readable location, for logging. */
  String describe();
}
