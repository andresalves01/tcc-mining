package app.paper6;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything the constructs read, in memory, sorted by instant and read by binary search.
 *
 * <p>The design of the pull-request study, for the same reason: a table can be truncated, so the
 * time-travel check can rebuild the corpus with everything from {@code t0} onwards removed and
 * recompute. A source that filtered by date as it read could never be checked that way.
 *
 * <p>Two things here are <b>not</b> timed and are never truncated: the final snapshot of each
 * population issue's own fields, and the changes recorded against it. They are not history about
 * other issues; they are the issue itself, and {@link Replay} is what turns them into the value at
 * {@code t0}. Their check is a different one -- whether the changelog chains to the snapshot -- and
 * the extractor reports it field by field.
 */
public final class Tables {

  public interface Timed {
    long at();
  }

  /** An issue of a project, by creation instant. Every issue of the corpus, not only labels. */
  public record ProjectIssue(long at, String issueId) implements Timed {}

  /** An issue of a reporter, with its project, by creation instant. */
  public record ReporterIssue(long at, String issueId, String project) implements Timed {}

  /**
   * The first change of status to {@code Resolved} or {@code Closed}: when, by whom, on which issue.
   * The same rule the issue labels were built with.
   */
  public record Resolution(long at, String resolver, String issueId) implements Timed {}

  /** Somebody wrote a comment on an issue somebody else reported. */
  public record Contact(long at, String other, String issueId) implements Timed {}

  /** The project's first observable activity, a commit or an issue, as an event. */
  public record Origin(long at, String source) implements Timed {}

  /**
   * One recorded change to a field of a population issue.
   *
   * @param oldValue what the field held before, as text; {@code null} where the log says nothing
   *     was there -- for a list field, that marks an item being added
   */
  public record Change(long at, String field, String oldValue, String newValue) {}

  /**
   * A population issue's fields as the corpus stores them: the state at collection, not at creation.
   *
   * @param linkCount {@code -1} when the corpus has no {@code issue_links} field at all, which is
   *     absence and not an empty list
   */
  public record Snapshot(String title, String description, String priority, String issueType,
      String assignee, List<String> labels, int components, int affectsVersions, int fixVersions,
      int linkCount) {}

  private final Map<String, List<ProjectIssue>> issuesByProject;
  private final Map<String, List<ReporterIssue>> issuesByReporter;
  private final Map<String, Resolution> resolutionOf;
  private final Map<String, List<Resolution>> resolutionsByProject;
  private final Map<String, List<Contact>> contactsOnIssuesOf;
  private final Map<String, List<Contact>> contactsWrittenBy;
  private final Map<String, List<Origin>> originOf;
  private final Map<String, Snapshot> snapshots;
  private final Map<String, List<Change>> changes;

  private Tables(final Builder builder) {
    this.issuesByProject = sorted(builder.issuesByProject);
    this.issuesByReporter = sorted(builder.issuesByReporter);
    this.resolutionOf = Map.copyOf(builder.resolutionOf);
    this.contactsOnIssuesOf = sorted(builder.contactsOnIssuesOf);
    this.contactsWrittenBy = sorted(builder.contactsWrittenBy);
    this.originOf = sorted(builder.originOf);
    this.snapshots = Map.copyOf(builder.snapshots);
    final Map<String, List<Change>> byIssue = new HashMap<>();
    builder.changes.forEach((id, list) -> {
      final List<Change> copy = new ArrayList<>(list);
      copy.sort((a, b) -> Long.compare(a.at(), b.at()));
      byIssue.put(id, List.copyOf(copy));
    });
    this.changes = Map.copyOf(byIssue);

    // Derived from the resolutions rather than collected beside them, so a truncated view, which
    // removes resolutions, cannot disagree with this index.
    final Map<String, List<Resolution>> byProject = new HashMap<>();
    final Map<String, String> projectOfIssue = new HashMap<>();
    builder.issuesByProject.forEach((project, list) ->
        list.forEach(issue -> projectOfIssue.put(issue.issueId(), project)));
    for (final Resolution resolution : resolutionOf.values()) {
      final String project = projectOfIssue.get(resolution.issueId());
      if (project != null) {
        byProject.computeIfAbsent(project, key -> new ArrayList<>()).add(resolution);
      }
    }
    this.resolutionsByProject = sorted(byProject);
  }

  private static <T extends Timed> Map<String, List<T>> sorted(final Map<String, List<T>> raw) {
    final Map<String, List<T>> out = new HashMap<>(raw.size() * 2);
    raw.forEach((key, list) -> {
      final List<T> copy = new ArrayList<>(list);
      copy.sort((a, b) -> Long.compare(a.at(), b.at()));
      out.put(key, List.copyOf(copy));
    });
    return Map.copyOf(out);
  }

  public List<ProjectIssue> issuesOfProject(final String project) {
    return issuesByProject.getOrDefault(project, List.of());
  }

  public List<ReporterIssue> issuesOfReporter(final String reporter) {
    return issuesByReporter.getOrDefault(reporter, List.of());
  }

  /** When an issue was first resolved, or {@code null} if it never was, as far as the log says. */
  public Resolution resolutionOf(final String issueId) {
    return resolutionOf.get(issueId);
  }

  public List<Resolution> resolutionsOfProject(final String project) {
    return resolutionsByProject.getOrDefault(project, List.of());
  }

  /** Comments by other people on issues this person reported. */
  public List<Contact> contactsOnIssuesOf(final String reporter) {
    return contactsOnIssuesOf.getOrDefault(reporter, List.of());
  }

  /** Comments this person wrote on issues somebody else reported. */
  public List<Contact> contactsWrittenBy(final String person) {
    return contactsWrittenBy.getOrDefault(person, List.of());
  }

  public List<Origin> originOf(final String project) {
    return originOf.getOrDefault(project, List.of());
  }

  public Snapshot snapshotOf(final String issueId) {
    return snapshots.get(issueId);
  }

  public List<Change> changesOf(final String issueId) {
    return changes.getOrDefault(issueId, List.of());
  }

  /** Everything in {@code [from, until)}. */
  public static <T extends Timed> List<T> between(final List<T> sorted, final long from,
      final long until) {

    return sorted.subList(lowerBound(sorted, from), lowerBound(sorted, until));
  }

  /** Everything strictly before {@code until}. The rule every window is built on. */
  public static <T extends Timed> List<T> before(final List<T> sorted, final long until) {
    return sorted.subList(0, lowerBound(sorted, until));
  }

  private static <T extends Timed> int lowerBound(final List<T> sorted, final long value) {
    int low = 0;
    int high = sorted.size();
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (sorted.get(mid).at() < value) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /**
   * The corpus as it stood just before {@code t}: every timed row at or after it removed.
   *
   * <p>The snapshots and changes of the population issues are kept as they are. They describe the
   * issue itself and are what the replay undoes; truncating them would test nothing about history.
   */
  public Tables truncatedAt(final long t) {
    final Builder builder = new Builder();
    builder.issuesByProject.putAll(cut(issuesByProject, t));
    builder.issuesByReporter.putAll(cut(issuesByReporter, t));
    resolutionOf.forEach((id, resolution) -> {
      if (resolution.at() < t) {
        builder.resolutionOf.put(id, resolution);
      }
    });
    builder.contactsOnIssuesOf.putAll(cut(contactsOnIssuesOf, t));
    builder.contactsWrittenBy.putAll(cut(contactsWrittenBy, t));
    builder.originOf.putAll(cut(originOf, t));
    builder.snapshots.putAll(snapshots);
    changes.forEach((id, list) -> builder.changes.put(id, new ArrayList<>(list)));
    return builder.build();
  }

  private static <T extends Timed> Map<String, List<T>> cut(final Map<String, List<T>> source,
      final long t) {

    final Map<String, List<T>> out = new HashMap<>();
    source.forEach((key, list) -> out.put(key, new ArrayList<>(before(list, t))));
    return out;
  }

  /** Collects rows in any order; {@link #build} sorts once. */
  public static final class Builder {

    private final Map<String, List<ProjectIssue>> issuesByProject = new HashMap<>();
    private final Map<String, List<ReporterIssue>> issuesByReporter = new HashMap<>();
    private final Map<String, Resolution> resolutionOf = new HashMap<>();
    private final Map<String, List<Contact>> contactsOnIssuesOf = new HashMap<>();
    private final Map<String, List<Contact>> contactsWrittenBy = new HashMap<>();
    private final Map<String, List<Origin>> originOf = new HashMap<>();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, List<Change>> changes = new HashMap<>();

    private static <T> void add(final Map<String, List<T>> map, final String key, final T row) {
      map.computeIfAbsent(key, (Function<String, List<T>>) k -> new ArrayList<>()).add(row);
    }

    /** An issue of the corpus. {@code reporter} may be null: then it counts only for the project. */
    public Builder issue(final String issueId, final String project, final String reporter,
        final long createdAt) {

      add(issuesByProject, project, new ProjectIssue(createdAt, issueId));
      if (reporter != null) {
        add(issuesByReporter, reporter, new ReporterIssue(createdAt, issueId, project));
      }
      return this;
    }

    /** Keeps the earliest, which is the rule: the first time the issue reached a resolved state. */
    public Builder resolution(final String issueId, final long at, final String resolver) {
      resolutionOf.merge(issueId, new Resolution(at, resolver, issueId),
          (a, b) -> a.at() <= b.at() ? a : b);
      return this;
    }

    /**
     * A comment. Recorded at both ends, and only between two different people: writing on your own
     * issue is not a contact.
     */
    public Builder comment(final String issueId, final String reporter, final String author,
        final long at) {

      if (reporter == null || author == null || reporter.equals(author)) {
        return this;
      }
      add(contactsOnIssuesOf, reporter, new Contact(at, author, issueId));
      add(contactsWrittenBy, author, new Contact(at, reporter, issueId));
      return this;
    }

    public Builder origin(final String project, final long at, final String source) {
      add(originOf, project, new Origin(at, source));
      return this;
    }

    public Builder snapshot(final String issueId, final Snapshot snapshot) {
      snapshots.put(issueId, snapshot);
      return this;
    }

    public Builder change(final String issueId, final Change change) {
      add(changes, issueId, change);
      return this;
    }

    public Tables build() {
      return new Tables(this);
    }
  }
}
