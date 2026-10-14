package app.pullrequests;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * How much the creator had dealt with the project's active integrators, in the ninety days before
 * {@code t0}.
 *
 * <p>A contact is a directed exchange between two <b>different</b> people: somebody wrote a pull
 * request comment or a formal review on a pull request somebody else opened. Writing on your own is
 * not a contact, which is what makes the case of a creator who is also an active integrator count
 * once instead of twice.
 *
 * <p><b>Two sources, not three.</b> {@code pull_request_review_comment} is excluded by
 * specification: 31,8% of its rows have no author and 76.595 have no {@code created_at}, so a
 * ninety-day window over it could not be defended. The two sources used are complete on both
 * fields.
 *
 * <p><b>Absent is not zero.</b> With no active integrator in the window there is no set to measure
 * connection to, and the value is absent with an indicator beside it. With integrators and no
 * contact the value is zero, and that zero is real. Summing the two would report a stranger and an
 * unobservable as the same thing.
 */
public final class SocialConnection {

  /** Ninety days, the window Yu defines and the same one {@code team_size_90d} uses. */
  public static final long WINDOW_MILLIS = 90L * 86_400_000L;

  private SocialConnection() {}

  /** The distinct people who merged something in this project during the window. */
  public static Set<String> activeIntegrators(final List<Tables.Merge> merges, final long t0) {
    final Set<String> active = new HashSet<>();
    for (final Tables.Merge merge : Tables.between(merges, t0 - WINDOW_MILLIS, t0)) {
      if (merge.actor() != null && !merge.actor().isEmpty()) {
        active.add(merge.actor());
      }
    }
    return active;
  }

  /**
   * @param pullRequestId the row being predicted, excluded by identifier and not only by window --
   *     a comment written on it before {@code t0} is still about it
   * @return the number of contacts, or empty when the project had no active integrator
   */
  public static OptionalInt of(final String pullRequestId, final String creator, final long t0,
      final List<Tables.Merge> projectMerges, final Tables tables) {

    final Set<String> active = activeIntegrators(projectMerges, t0);
    if (active.isEmpty()) {
      return OptionalInt.empty();
    }
    final long from = t0 - WINDOW_MILLIS;
    int contacts = 0;

    // One end: an active integrator wrote on a pull request of this creator. Duplicates within
    // the end are collapsed -- the same person, instant and target is one contact however many
    // rows recorded it -- and the two ends are counted separately, because a creator and an
    // integrator writing to each other in the same second did interact twice.
    final Set<String> seen = new HashSet<>();
    for (final Tables.Contact contact
        : Tables.between(tables.contactsOnPrsOf(creator), from, t0)) {

      if (contact.pullRequestId().equals(pullRequestId) || !active.contains(contact.other())) {
        continue;
      }
      if (seen.add(contact.at() + "\u0000" + contact.other() + "\u0000"
          + contact.pullRequestId())) {
        contacts++;
      }
    }

    // The other end: the creator wrote on a pull request of an active integrator.
    final Set<String> seenBack = new HashSet<>();
    for (final Tables.Contact contact
        : Tables.between(tables.contactsWrittenBy(creator), from, t0)) {

      if (contact.pullRequestId().equals(pullRequestId) || !active.contains(contact.other())) {
        continue;
      }
      if (seenBack.add(contact.at() + "\u0000" + contact.pullRequestId())) {
        contacts++;
      }
    }
    return OptionalInt.of(contacts);
  }
}
