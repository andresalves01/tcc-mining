package app.paper6;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The issue's own fields as they were at {@code t0}, recovered by undoing the recorded changes.
 *
 * <p>The rule, stated once: start from the stored final value, and undo every change recorded
 * <b>strictly after</b> {@code t0}, newest first. For a field that holds one value, that means the
 * value at {@code t0} is the <i>old</i> value of the earliest change after {@code t0}, or the stored
 * value when nothing changed. For a field that is a list, the log records one change per item --
 * old empty and new filled is an item added, the reverse is an item removed -- so the count at
 * {@code t0} is the final count minus what was added and plus what was removed after {@code t0}.
 *
 * <p>"Strictly after" includes changes made seconds after creation: 388 of 9.559 field changes in a
 * sample of 3.000 labels fell in the first minute. They are undone like any other. An edit in the
 * first minute is still an edit, and keeping it would be choosing, per case, which edits count.
 *
 * <p><b>What this rests on is that the log is complete.</b> {@link Fidelity} measures that per
 * field and the extractor prints it; where a list's count would go below zero, the log has lost an
 * addition, the count is held at zero, and the case is counted rather than hidden.
 */
public final class Replay {

  /** The fields of one issue at {@code t0}. */
  public record AtOpen(String title, String description, String priority, String issueType,
      boolean hasAssignee, int labels, int components, int affectsVersions, int fixVersions,
      int linkCount) {}

  /**
   * How far the log can be trusted for one field, over the population.
   *
   * @param withChanges issues with at least one change of this field recorded
   * @param chained of those, the ones whose last recorded change arrives at the stored value. The
   *     independent check: a log that lost a change will, most of the time, end somewhere else.
   *     Not measurable for list fields, which the log records item by item
   * @param changedAfterOpen issues whose value at {@code t0} differs from the stored final value
   * @param negative list fields only: issues whose count would have gone below zero
   */
  public record Fidelity(String field, int withChanges, int chained, int changedAfterOpen,
      int negative) {}

  static final List<String> SCALAR = List.of("title", "desc", "priority", "issue_type",
      "assignee_id", "labels");
  static final List<String> LIST = List.of("components", "affects_versions", "fix_versions",
      "issue_links");

  private Replay() {}

  public static AtOpen of(final Tables.Snapshot snapshot, final List<Tables.Change> changes,
      final long t0) {

    final String title = scalar(snapshot.title(), changes, "title", t0);
    final String description = scalar(snapshot.description(), changes, "desc", t0);
    final String priority = scalar(snapshot.priority(), changes, "priority", t0);
    final String type = scalar(snapshot.issueType(), changes, "issue_type", t0);
    final String assignee = scalar(snapshot.assignee(), changes, "assignee_id", t0);
    final String labels = scalar(String.join(" ", snapshot.labels()), changes, "labels", t0);
    return new AtOpen(title, description, priority, type, assignee != null && !assignee.isEmpty(),
        words(labels),
        Math.max(0, count(snapshot.components(), changes, "components", t0)),
        Math.max(0, count(snapshot.affectsVersions(), changes, "affects_versions", t0)),
        Math.max(0, count(snapshot.fixVersions(), changes, "fix_versions", t0)),
        snapshot.linkCount() < 0 ? -1
            : Math.max(0, count(snapshot.linkCount(), changes, "issue_links", t0)));
  }

  /** The old value of the earliest change after {@code t0}, or the stored value if none. */
  static String scalar(final String stored, final List<Tables.Change> changes, final String field,
      final long t0) {

    for (final Tables.Change change : changes) {
      if (change.at() > t0 && change.field().equals(field)) {
        return change.oldValue();
      }
    }
    return stored;
  }

  /** The final count, with every addition after {@code t0} taken back and every removal restored. */
  static int count(final int stored, final List<Tables.Change> changes, final String field,
      final long t0) {

    int count = stored;
    for (final Tables.Change change : changes) {
      if (change.at() <= t0 || !change.field().equals(field)) {
        continue;
      }
      final boolean hadOld = change.oldValue() != null && !change.oldValue().isEmpty();
      final boolean hasNew = change.newValue() != null && !change.newValue().isEmpty();
      if (!hadOld && hasNew) {
        count--;
      } else if (hadOld && !hasNew) {
        count++;
      }
    }
    return count;
  }

  /** Space-separated labels, counted. The log stores the whole set as one string. */
  static int words(final String joined) {
    if (joined == null || joined.isBlank()) {
      return 0;
    }
    return (int) Arrays.stream(joined.trim().split("\\s+")).filter(s -> !s.isEmpty()).count();
  }

  /**
   * The fidelity of the log, per field, over a set of issues.
   *
   * <p>Accumulated by the caller one issue at a time, because the population is fifty thousand
   * issues and the tables are already in memory.
   */
  public static final class Audit {

    private final int[] withChanges = new int[SCALAR.size() + LIST.size()];
    private final int[] chained = new int[withChanges.length];
    private final int[] changedAfterOpen = new int[withChanges.length];
    private final int[] negative = new int[withChanges.length];

    public void add(final Tables.Snapshot snapshot, final List<Tables.Change> changes,
        final long t0) {

      for (int f = 0; f < SCALAR.size(); f++) {
        final String field = SCALAR.get(f);
        final String stored = storedScalar(snapshot, field);
        String last = null;
        boolean any = false;
        for (final Tables.Change change : changes) {
          if (change.field().equals(field)) {
            any = true;
            last = change.newValue();
          }
        }
        if (any) {
          withChanges[f]++;
          if (Objects.equals(normalise(field, last), normalise(field, stored))) {
            chained[f]++;
          }
        }
        if (!Objects.equals(normalise(field, scalar(stored, changes, field, t0)),
            normalise(field, stored))) {
          changedAfterOpen[f]++;
        }
      }
      for (int l = 0; l < LIST.size(); l++) {
        final int f = SCALAR.size() + l;
        final String field = LIST.get(l);
        final int stored = storedCount(snapshot, field);
        if (stored < 0) {
          continue;
        }
        boolean any = false;
        for (final Tables.Change change : changes) {
          if (change.field().equals(field)) {
            any = true;
            break;
          }
        }
        if (any) {
          withChanges[f]++;
        }
        final int atOpen = count(stored, changes, field, t0);
        if (atOpen < 0) {
          negative[f]++;
        }
        if (Math.max(0, atOpen) != stored) {
          changedAfterOpen[f]++;
        }
      }
    }

    public List<Fidelity> result() {
      final List<Fidelity> out = new ArrayList<>();
      for (int f = 0; f < SCALAR.size(); f++) {
        out.add(new Fidelity(SCALAR.get(f), withChanges[f], chained[f], changedAfterOpen[f], 0));
      }
      for (int l = 0; l < LIST.size(); l++) {
        final int f = SCALAR.size() + l;
        out.add(new Fidelity(LIST.get(l), withChanges[f], -1, changedAfterOpen[f], negative[f]));
      }
      return List.copyOf(out);
    }
  }

  private static String storedScalar(final Tables.Snapshot snapshot, final String field) {
    return switch (field) {
      case "title" -> snapshot.title();
      case "desc" -> snapshot.description();
      case "priority" -> snapshot.priority();
      case "issue_type" -> snapshot.issueType();
      case "assignee_id" -> snapshot.assignee();
      case "labels" -> String.join(" ", snapshot.labels());
      default -> throw new IllegalArgumentException(field);
    };
  }

  private static int storedCount(final Tables.Snapshot snapshot, final String field) {
    return switch (field) {
      case "components" -> snapshot.components();
      case "affects_versions" -> snapshot.affectsVersions();
      case "fix_versions" -> snapshot.fixVersions();
      case "issue_links" -> snapshot.linkCount();
      default -> throw new IllegalArgumentException(field);
    };
  }

  /**
   * Empty and absent are the same for this comparison, since the log writes an empty field both
   * ways; and a label set is a set, so its order is not a difference.
   */
  private static String normalise(final String field, final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (field.equals("labels")) {
      final String[] tokens = value.trim().split("\\s+");
      Arrays.sort(tokens);
      return String.join(" ", tokens);
    }
    return value.strip();
  }
}
