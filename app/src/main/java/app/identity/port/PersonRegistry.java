package app.identity.port;

import app.identity.Identity;
import app.identity.PersonRecord;
import app.identity.RefusedBridge;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Where reconstructed identities are kept.
 *
 * <p>A port, so the domain and the use cases never name a storage technology. The MongoDB adapter
 * lives in {@code app.pipeline.mongo}; swapping it for files or a test double changes nothing above
 * this interface.
 */
public interface PersonRegistry {

  /** Everything currently known, keyed by person id. Empty on a first run. */
  Map<String, PersonRecord> readAll();

  /**
   * Commit counts already stored, keyed by person id.
   *
   * <p>Needed because only the git reconstruction knows them: a later stage that recanonicalises
   * must carry them forward rather than pass an empty map, which would silently reset every count
   * to zero.
   */
  Map<String, Integer> readAuthoredCommits();

  /**
   * The equivalence relation as stored: {@code personId -> canonicalPersonId}.
   *
   * <p>Read back rather than recomputed because everything downstream must key on the <i>same</i>
   * merge the reconstruction wrote. Recanonicalising on the fly would let a later stage silently
   * disagree with the collection its own results are joined against.
   */
  Map<String, String> readCanonical();

  /** Canonical ids whose commits are automated. */
  Set<String> readBots();

  /** Upserts the registry itself: {@code personId -> (name, email, username)}. */
  long saveIdentities(Collection<PersonRecord> records);

  /**
   * Upserts the equivalence relation: {@code personId -> canonicalPersonId}.
   *
   * <p>Stored apart from the registry because the two answer different questions. A registry does
   * not deduplicate — its natural key is {@code (name, email)}, so one human legitimately holds
   * several rows. That is equally true of a natively-mined {@code people}, which is why
   * canonicalisation is always required and not merely a workaround for the missing collection.
   */
  long saveCanonical(
      Map<String, String> canonical,
      Map<String, PersonRecord> records,
      Map<String, Integer> authoredCommits);

  /**
   * Stores the Jira-to-git links the ambiguity guard declined.
   *
   * <p>Written next to the equivalence relation rather than logged and forgotten, because a refusal
   * is an assertion about the data that a reader should be able to check. Like the relation itself
   * it holds nothing but opaque identifiers already present in the public release, so it is
   * publishable alongside it.
   */
  long saveRefusedBridges(Collection<RefusedBridge> refusals);

  /** Human-readable location, for logging. */
  String describe();

  /** Where the identities themselves are kept, named for a report. */
  String describeIdentities();

  /** Where the equivalence relation is kept, named for a report. */
  String describeCanonical();

  /** Convenience for reporting. */
  static long countBots(final Collection<PersonRecord> records) {
    return records.stream().map(PersonRecord::identity).filter(Identity::isBot).count();
  }
}
