package app.pipeline;

import app.identity.port.PersonRegistry;
import app.labels.LabelScan;
import app.labels.port.LabelSource;
import app.labels.port.LabelStore;
import java.util.Map;

/**
 * Runs the global funnel and keeps what survives it.
 *
 * <p>Refuses to run against an empty registry for the same reason the other mining stages do: the
 * author of every commit is resolved through it, so an empty one would silently drop every issue at
 * the bot stage and report a funnel that looks like a finding rather than a missing prerequisite.
 */
public final class BuildFunnelTask {

  public record Report(LabelScan scan, long written) {}

  private final LabelSource source;
  private final LabelStore store;
  private final PersonRegistry registry;

  public BuildFunnelTask(final LabelSource source, final LabelStore store,
      final PersonRegistry registry) {
    this.source = source;
    this.store = store;
    this.registry = registry;
  }

  public Report run() {
    return run(null);
  }

  /** @param pilot restricts the scan to one project; {@code null} scans the corpus */
  public Report run(final String pilot) {
    final Map<String, String> canonical = registry.readCanonical();
    if (canonical.isEmpty()) {
      throw new BuildLedgerTask.RegistryEmptyException(
          "person_canonical is empty: run reconstruct-people and harvest-jira first");
    }
    final LabelScan scan = source.scan(pilot, canonical, registry.readBots());
    return new Report(scan, store.save(scan.labels()));
  }
}
