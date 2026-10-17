package app.issues;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An issue's fix versions at an instant, recovered from the current record and the changelog
 * (ticket 19). Each {@code fix_versions} change adds a version (its new value) or removes one (its
 * old value). As {@link Initial} reads the value at creation, every recorded change is undone, the
 * ones stamped at the creation instant too; a blank name counts as absent ({@link Initial#norm}).
 */
final class FixVersions {

  static final String FIELD = "fix_versions";

  private FixVersions() {}

  /** The fix versions at creation: the current ones with every change undone, last first. */
  static Set<String> atCreation(final Corpus.Issue issue, final List<Corpus.Event> events) {
    final Set<String> out = new LinkedHashSet<>();
    if (issue.fixVersions() != null) {
      issue.fixVersions().stream().map(Initial::norm).filter(v -> v != null).forEach(out::add);
    }
    final List<Corpus.Event> changes = Initial.field(events, FIELD);
    for (int k = changes.size() - 1; k >= 0; k--) {
      final String added = Initial.norm(changes.get(k).newValue());
      final String removed = Initial.norm(changes.get(k).oldValue());
      if (added != null) {
        out.remove(added);
      }
      if (removed != null) {
        out.add(removed);
      }
    }
    return out;
  }

  /**
   * The fix versions in force at {@code t}: those at creation with the changes stamped at or before
   * {@code t} applied, so a version set in the same action as the resolution counts at it.
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

  /**
   * Whether the issue had a fix version at creation or got one strictly before {@code t}, even if
   * it was removed later.
   */
  static boolean setBefore(final Corpus.Issue issue, final List<Corpus.Event> events,
      final long t) {
    if (!atCreation(issue, events).isEmpty()) {
      return true;
    }
    for (final Corpus.Event e : Initial.field(events, FIELD)) {
      if (e.at() != null && e.at() < t && Initial.norm(e.newValue()) != null) {
        return true;
      }
    }
    return false;
  }
}
