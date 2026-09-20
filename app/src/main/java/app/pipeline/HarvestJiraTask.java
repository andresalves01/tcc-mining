package app.pipeline;

import app.identity.JiraResolution;
import app.identity.PersonRecord;
import app.identity.Strictness;
import app.identity.port.JiraIdentitySource;
import app.identity.port.PersonRegistry;
import java.util.List;
import java.util.Map;

/**
 * Resolves the Jira half of the registry, then canonicalises so the two id spaces are linked.
 *
 * <p>Requires {@link ReconstructPeopleTask} to have run: without git identities present there is
 * nothing for a Jira username or display name to match against, and the harvest would produce an
 * isolated island of people rather than a bridge.
 */
public final class HarvestJiraTask {

  /** Thrown when the registry is empty, so the harvest would have nothing to link to. */
  public static final class RegistryEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RegistryEmptyException(final String registry) {
      super(registry + " holds no identities -- run reconstruct-people first");
    }
  }

  public record Report(
      JiraResolution resolution, long identitiesWritten, CanonicalizeTask.Report canonical) {}

  private final JiraIdentitySource source;
  private final PersonRegistry registry;
  private final Strictness strictness;

  public HarvestJiraTask(
      final JiraIdentitySource source, final PersonRegistry registry, final Strictness strictness) {
    this.source = source;
    this.registry = registry;
    this.strictness = strictness;
  }

  public Report run() throws Exception {
    final Map<String, PersonRecord> known = registry.readAll();
    if (known.isEmpty()) {
      throw new RegistryEmptyException(registry.describe());
    }

    final JiraResolution resolution = source.resolve(known.keySet());

    // Defence in depth. The adapter is asked not to return ids we already hold, but this task owns
    // what reaches storage, so it enforces the rule rather than trusting the source. A Jira record
    // carries a display name and no address; writing one over a git identity discards that person's
    // email, which is the strongest merge key available.
    final List<PersonRecord> newcomers = resolution.records().stream()
        .filter(record -> !known.containsKey(record.personId()))
        .toList();

    final long written = registry.saveIdentities(newcomers);

    // Nothing new was authored, but the counts must still be passed through: saveCanonical
    // rewrites every row, so an empty map here would reset all of them to zero.
    final CanonicalizeTask.Report canonical =
        new CanonicalizeTask(registry, strictness).run(registry.readAuthoredCommits());

    return new Report(resolution, written, canonical);
  }
}
