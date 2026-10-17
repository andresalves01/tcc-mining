package app.issues;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An issue's fix versions at an instant, recovered from the current record and the changelog
 * (ticket 19). Each {@code fix_versions} change adds a version (its new value) or removes one (its
 * old value); a blank name counts as absent ({@link Initial#norm}).
 *
 * <p><b>By name</b> ({@link #atCreation}, {@link #at}): every change undone from the current
 * record, last first, by the rule the links are read with ({@link Initial#undo}). Urgency reads
 * this, since it needs the names to match tags.
 *
 * <p><b>Whether there was one at creation</b> ({@link #setBefore}) does not depend on the names.
 * Jira renames or merges a version without a changelog entry, so a version added after the start
 * under its old name can sit in the current record under its new one, and undoing by name would
 * leave it there at creation. Counting does not: the number of versions at creation is the number
 * now, less those added, plus those removed.
 */
public final class FixVersions {

  static final String FIELD = "fix_versions";

  private FixVersions() {}

  /** The current names, blank ones left out, each once. */
  private static Set<String> current(final Corpus.Issue issue) {
    final Set<String> out = new java.util.LinkedHashSet<>();
    if (issue.fixVersions() != null) {
      issue.fixVersions().stream().map(Initial::norm).filter(Objects::nonNull).forEach(out::add);
    }
    return out;
  }

  private static Initial.Undone<String> undone(final Corpus.Issue issue,
      final List<Corpus.Event> events) {
    return Initial.undo(current(issue), Initial.field(events, FIELD),
        e -> Initial.norm(e.newValue()), e -> Initial.norm(e.oldValue()));
  }

  /** The fix versions at creation, by name: the current ones with every change undone. */
  static Set<String> atCreation(final Corpus.Issue issue, final List<Corpus.Event> events) {
    return undone(issue, events).held();
  }

  /**
   * The fix versions in force at {@code t}, by name: those at creation with the changes stamped
   * at or before {@code t} applied, so a version set in the same action as the resolution counts
   * at it.
   */
  static Set<String> at(final Corpus.Issue issue, final List<Corpus.Event> events, final long t) {
    final Set<String> out = atCreation(issue, events);
    for (final Corpus.Event e : Initial.field(events, FIELD)) {
      if (e.at() == null || e.at() > t) {
        continue;
      }
      final String removed = Initial.norm(e.oldValue());
      final String added = Initial.norm(e.newValue());
      if (removed != null) {
        out.remove(removed);
      }
      if (added != null) {
        out.add(added);
      }
    }
    return out;
  }

  /** How many fix versions the issue had at creation, by count: now, less added, plus removed. */
  static int countAtCreation(final Corpus.Issue issue, final List<Corpus.Event> events) {
    int n = current(issue).size();
    for (final Corpus.Event e : Initial.field(events, FIELD)) {
      if (Initial.norm(e.newValue()) != null) {
        n--;
      }
      if (Initial.norm(e.oldValue()) != null) {
        n++;
      }
    }
    return n;
  }

  /**
   * Whether the issue had a fix version at creation ({@link #countAtCreation} above 0) or got one
   * strictly before {@code t}, even if it was removed later. No name is compared.
   */
  static boolean setBefore(final Corpus.Issue issue, final List<Corpus.Event> events,
      final long t) {
    if (countAtCreation(issue, events) > 0) {
      return true;
    }
    for (final Corpus.Event e : Initial.field(events, FIELD)) {
      if (e.at() != null && e.at() < t && Initial.norm(e.newValue()) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * How far the changelog explains the fix versions, for the gate, as {@link Initial#fidelity}
   * reports its fields: of the issues with a change, those whose undoing by name finds every
   * added version held and agrees with the count at creation; and the issues whose versions at
   * creation differ from the current ones.
   */
  public static Initial.Fidelity fidelity(final Universe universe,
      final Collection<String> ids) {
    int changed = 0;
    int consistent = 0;
    int differs = 0;
    for (final String id : ids) {
      final Corpus.Issue issue = universe.issue(id);
      final List<Corpus.Event> events = universe.events(id);
      final Initial.Undone<String> u = undone(issue, events);
      if (!Initial.field(events, FIELD).isEmpty()) {
        changed++;
        final int byName = u.initial().values().stream().mapToInt(Integer::intValue).sum();
        if (u.consistent() && byName == countAtCreation(issue, events)) {
          consistent++;
        }
      }
      if (!u.held().equals(current(issue))) {
        differs++;
      }
    }
    return new Initial.Fidelity(FIELD, ids.size(), changed, consistent, differs);
  }
}
