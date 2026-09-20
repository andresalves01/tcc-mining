package app.pipeline;

import app.identity.GitReconstruction;
import app.identity.Identity;
import app.identity.PersonRecord;
import app.identity.Strictness;
import app.identity.port.GitIdentitySource;
import app.identity.port.PersonRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the git half of the registry, then canonicalises.
 *
 * <p>A batch stage with a durable result, deliberately not a lazy fallback inside a repository
 * lookup: it reads roughly 7 GB out of GridFS. Hiding that behind a getter would make an ordinary
 * lookup unpredictably expensive.
 */
public final class ReconstructPeopleTask {

  /** Thrown when the recovered mapping is not one-to-one, which invalidates every later merge. */
  public static final class NotBijectiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> violations;

    NotBijectiveException(final List<String> violations) {
      super(violations.size() + " person id(s) map to more than one identity");
      this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
      return violations;
    }
  }

  public record Report(
      GitReconstruction reconstruction, long identitiesWritten, CanonicalizeTask.Report canonical) {}

  private final GitIdentitySource source;
  private final PersonRegistry registry;
  private final Strictness strictness;

  public ReconstructPeopleTask(
      final GitIdentitySource source, final PersonRegistry registry, final Strictness strictness) {
    this.source = source;
    this.registry = registry;
    this.strictness = strictness;
  }

  public Report run() throws Exception {
    final GitReconstruction reconstruction = source.load();

    // pycoshark declares People.email as unique_with=["name"], so one ObjectId denotes exactly one
    // identity. If that does not hold here, stop: silently picking a winner would bury the problem
    // inside every downstream merge.
    if (!reconstruction.isBijective()) {
      throw new NotBijectiveException(reconstruction.bijectionViolations());
    }

    final List<PersonRecord> records = new ArrayList<>(reconstruction.identities().size());
    for (final Map.Entry<String, Identity> entry : reconstruction.identities().entrySet()) {
      records.add(PersonRecord.git(entry.getKey(), entry.getValue()));
    }

    final long written = registry.saveIdentities(records);
    final CanonicalizeTask.Report canonical =
        new CanonicalizeTask(registry, strictness).run(reconstruction.authoredCommits());

    return new Report(reconstruction, written, canonical);
  }
}
