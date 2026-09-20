package app.cost.port;

import app.cost.Contribution;
import java.util.Collection;
import java.util.List;

/** Where the ledger is kept. Mirrors {@code PersonRegistry}: the use case names no technology. */
public interface ContributionLedger {

  /** Upserts, keyed on the commit, so a re-run repairs rather than duplicates. */
  long save(Collection<Contribution> contributions);

  /** Rows currently stored. */
  long count();

  /**
   * The whole ledger, for the in-memory derivations built on top of it.
   *
   * <p>Loading all of it is the intended access pattern, not a shortcut: seniority is a rank over
   * the entire population as of an instant, so there is no useful subset to fetch. At roughly
   * 430,000 rows of fixed-width fields this is a few hundred megabytes, against a design matrix
   * measured in megabytes — the expensive stages are upstream of here, not downstream.
   */
  List<Contribution> readAll();

  /** Human-readable location, for logging. */
  String describe();
}
