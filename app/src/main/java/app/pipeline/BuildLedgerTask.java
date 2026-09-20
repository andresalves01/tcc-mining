package app.pipeline;

import app.cost.ContributionScan;
import app.cost.port.ContributionLedger;
import app.cost.port.ContributionSource;
import app.identity.port.PersonRegistry;
import app.pipeline.port.Progress;
import java.util.Map;
import java.util.Set;

/**
 * Builds the per-commit contribution ledger from the corpus and the reconstructed identities.
 *
 * <p>The ledger is where the two halves of the model get their raw material: {@code H_j} is an
 * accumulation over these rows, and the seniority {@code q} behind the cost curve is a ranking of
 * the same accumulation. Cost itself is deliberately <b>not</b> computed here — see
 * {@code CostParams} for why a stored cost column would be an assumption wearing the costume of a
 * measurement.
 *
 * <p>Refuses to run without identities, for the same reason the Jira harvest does: with an empty
 * equivalence relation every commit author is unresolvable, so the scan would read 84 GB and
 * produce an empty ledger that looks like a corpus problem rather than an ordering mistake.
 */
public final class BuildLedgerTask {

  private final ContributionSource source;
  private final ContributionLedger ledger;
  private final PersonRegistry registry;
  private final Progress progress;

  public BuildLedgerTask(
      final ContributionSource source,
      final ContributionLedger ledger,
      final PersonRegistry registry,
      final Progress progress) {
    this.source = source;
    this.ledger = ledger;
    this.registry = registry;
    this.progress = progress;
  }

  public Report run() throws Exception {
    return run(null);
  }

  /** @param pilot restricts the scan to one project; {@code null} scans the corpus */
  public Report run(final String pilot) throws Exception {
    final Map<String, String> canonical = registry.readCanonical();
    if (canonical.isEmpty()) {
      throw new RegistryEmptyException(
          "no canonical identities in " + registry.describe()
              + " -- run reconstruct-people before building the ledger");
    }
    final Set<String> bots = registry.readBots();
    progress.detail("%,d identities -> %,d canonical people (%,d flagged as bots)",
        canonical.size(), canonical.values().stream().distinct().count(), bots.size());

    final ContributionScan scan = source.scan(canonical, bots, pilot);
    final long written = ledger.save(scan.contributions());
    return new Report(scan, written);
  }

  public record Report(ContributionScan scan, long written) {}

  /** Thrown rather than returning an empty ledger, which would look like a corpus problem. */
  public static final class RegistryEmptyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RegistryEmptyException(final String message) {
      super(message);
    }
  }
}
