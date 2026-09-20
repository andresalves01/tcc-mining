package app.pipeline;

import app.identity.CanonicalResolver;
import app.identity.PersonRecord;
import app.identity.Strictness;
import app.identity.port.PersonRegistry;
import java.util.Map;

/**
 * Recomputes the person-equivalence relation from whatever the registry currently holds.
 *
 * <p>Separate from the two mining tasks on purpose: it is cheap and reads nothing but the registry,
 * so a different {@link Strictness} can be evaluated without re-reading 7 GB of repositories or
 * re-querying the issue tracker.
 */
public final class CanonicalizeTask {

  /**
   * @param merged how many identities were absorbed into another person
   * @param bridgesRefused Jira links declined because the handle named more than one person
   */
  public record Report(int identities, int canonicalPeople, int merged, long bots, long written,
      int bridgesRefused) {

    public double mergedShare() {
      return identities == 0 ? 0 : (double) merged / identities;
    }
  }

  private final PersonRegistry registry;
  private final Strictness strictness;

  public CanonicalizeTask(final PersonRegistry registry, final Strictness strictness) {
    this.registry = registry;
    this.strictness = strictness;
  }

  public Report run(final Map<String, Integer> authoredCommits) {
    final Map<String, PersonRecord> records = registry.readAll();
    if (records.isEmpty()) {
      return new Report(0, 0, 0, 0, 0, 0);
    }

    final CanonicalResolver resolver = new CanonicalResolver(strictness);
    resolver.addAll(records.values());
    final Map<String, String> canonical = resolver.resolve();

    final long written = registry.saveCanonical(canonical, records, authoredCommits);
    registry.saveRefusedBridges(resolver.refusedBridges());

    return new Report(
        resolver.identityCount(),
        resolver.canonicalCount(),
        resolver.identityCount() - resolver.canonicalCount(),
        PersonRegistry.countBots(records.values()),
        written,
        resolver.refusedBridges().size());
  }
}
