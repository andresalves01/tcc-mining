package app.paper5;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every event the constructs read, in memory, indexed and sorted by instant.
 *
 * <p>Tables rather than queries, for one reason: a table can be <b>truncated</b>. The time-travel
 * test rebuilds the whole set with every row dated at or after {@code t0} removed and recomputes;
 * if a construct changes, the half-open window was not doing the work, the absence of later data
 * was. That test is impossible against a source that filters as it reads, because there would be
 * nothing left to take away.
 *
 * <p>Sorted once, then read by binary search. Every window in this study is half-open,
 * {@code [start, t0)}, so every lookup here is a lower bound and never a scan.
 */
public final class Tables {

  /** Anything with an instant, so one binary search serves every table. */
  public interface Timed {
    long at();
  }

  /** A pull request of a project: when it opened, and which one it is. */
  public record ProjectPr(long at, String pullRequestId) implements Timed {}

  /** A pull request of a creator, carrying its project so the per-project count is one pass. */
  public record CreatorPr(long at, String pullRequestId, String project) implements Timed {}

  /** A merge event: who performed it, on which pull request. */
  public record Merge(long at, String actor, String pullRequestId) implements Timed {}

  /**
   * Somebody wrote on somebody else's pull request.
   *
   * @param other the person at the far end -- the author, when indexed by the pull request's
   *     creator, and the creator, when indexed by the author
   */
  public record Contact(long at, String other, String pullRequestId) implements Timed {}

  /**
   * The project's first observable activity, as an event rather than a constant.
   *
   * <p>Deliberately a table: the age of a project is a number, but it is derived from an instant
   * that the truncated view has to be able to remove. Stored as a constant it would survive
   * truncation and the test would pass for the wrong reason.
   *
   * @param source {@code commit} or {@code issue}, kept because which one comes first differs in
   *     24 of the 77 projects and the report says so
   */
  public record Origin(long at, String source) implements Timed {}

  /** What the three textual constructs read, counted once at load. */
  public record Text(int words, boolean hashReference, boolean jiraReference, boolean mention) {}

  /** The bare facts of a pull request, for looking up the owner of one somebody wrote on. */
  public record Pr(String creator, long openedAt, String project) {}

  /**
   * The consolidated diff of a pull request, as the corpus recorded it.
   *
   * <p>Every field here is the <b>final</b> state. {@code pull_request_file} is GitHub's list of the
   * request's files: no timestamp, and no base recorded. These are the six technical constructs, and
   * they belong to the literature-compatible profile only -- never described as known at opening.
   *
   * @param commits rows in {@code pull_request_commit}. The final count; it equals the count at
   *     {@code t0} only inside the strict subpopulation of 2.329
   */
  public record Diff(int files, long additions, long deletions, boolean touchesTest, int commits) {

    static final Diff NONE = new Diff(0, 0, 0, false, 0);
  }

  private final Map<String, Pr> pullRequests;
  private final Map<String, Long> closedAt;
  private final Map<String, List<ProjectPr>> prsByProject;
  private final Map<String, List<CreatorPr>> prsByCreator;
  private final Map<String, List<Merge>> mergesByProject;
  private final Map<String, List<Contact>> contactsByCreator;
  private final Map<String, List<Contact>> contactsByAuthor;
  private final Map<String, Text> text;
  private final Map<String, List<Origin>> origin;
  private final Map<String, Long> firstMergeOfPr;
  private final Map<String, Diff> diff;
  private final Map<String, List<String>> pathsOfPr;
  private final Map<String, long[]> activityByPath;
  private final Map<String, long[]> commitsByProject;

  private Tables(final Builder builder) {
    this.pullRequests = Map.copyOf(builder.pullRequests);
    this.closedAt = Map.copyOf(builder.closedAt);
    this.prsByProject = freeze(builder.prsByProject);
    this.prsByCreator = freeze(builder.prsByCreator);
    this.mergesByProject = freeze(builder.mergesByProject);
    this.contactsByCreator = freeze(builder.contactsByCreator);
    this.contactsByAuthor = freeze(builder.contactsByAuthor);
    this.text = Map.copyOf(builder.text);
    this.origin = freeze(builder.origin);
    this.diff = Map.copyOf(builder.diff);
    this.pathsOfPr = Map.copyOf(builder.pathsOfPr);
    this.activityByPath = packed(builder.activityByPath);
    this.commitsByProject = sortedTimes(builder.commitsByProject);

    // Derived here rather than collected, so the truncated view gets it right for free: it
    // rebuilds from merge events that truncation has already removed, and cannot disagree with
    // them the way a separately copied map could.
    final Map<String, Long> firstMerge = new HashMap<>();
    this.mergesByProject.values().forEach(merges -> merges.forEach(merge -> {
      if (merge.pullRequestId() != null) {
        firstMerge.merge(merge.pullRequestId(), merge.at(), Math::min);
      }
    }));
    this.firstMergeOfPr = Map.copyOf(firstMerge);
  }

  /**
   * When a pull request was first merged, anywhere in the corpus, or {@code null} if never.
   *
   * <p>Across every project on purpose: a creator's earlier success counts wherever it happened,
   * and {@code owner_prior_prs} is likewise counted over all projects.
   */
  public Long firstMergeOf(final String pullRequestId) {
    return firstMergeOfPr.get(pullRequestId);
  }

  private static <T extends Timed> Map<String, List<T>> freeze(final Map<String, List<T>> raw) {
    final Map<String, List<T>> sorted = new HashMap<>(raw.size() * 2);
    raw.forEach((key, values) -> {
      final List<T> copy = new ArrayList<>(values);
      copy.sort((a, b) -> Long.compare(a.at(), b.at()));
      sorted.put(key, List.copyOf(copy));
    });
    return Map.copyOf(sorted);
  }

  public Pr pullRequest(final String id) {
    return pullRequests.get(id);
  }

  public Long closedAt(final String id) {
    return closedAt.get(id);
  }

  public List<ProjectPr> prsOfProject(final String project) {
    return prsByProject.getOrDefault(project, List.of());
  }

  public List<CreatorPr> prsOfCreator(final String creator) {
    return prsByCreator.getOrDefault(creator, List.of());
  }

  public List<Merge> mergesOfProject(final String project) {
    return mergesByProject.getOrDefault(project, List.of());
  }

  public List<Contact> contactsOnPrsOf(final String creator) {
    return contactsByCreator.getOrDefault(creator, List.of());
  }

  public List<Contact> contactsWrittenBy(final String author) {
    return contactsByAuthor.getOrDefault(author, List.of());
  }

  public Text text(final String id) {
    return text.getOrDefault(id, new Text(0, false, false, false));
  }

  public List<Origin> originOf(final String project) {
    return origin.getOrDefault(project, List.of());
  }

  public Diff diffOf(final String pullRequestId) {
    return diff.getOrDefault(pullRequestId, Diff.NONE);
  }

  /** The distinct paths of a pull request's consolidated diff, in a stable order. */
  public List<String> pathsOf(final String pullRequestId) {
    return pathsOfPr.getOrDefault(pullRequestId, List.of());
  }

  /**
   * How many distinct commits touched this path of this project in {@code [from, until)}.
   *
   * <p>Distinct commits, not file actions: {@code (commit_id, file_id)} is not unique in
   * {@code file_action}, so counting rows would overcount. The instants are stored packed with the
   * commit's index and deduplicated once, at load, so this is a binary search and a subtraction.
   */
  public int commitsTouching(final String project, final String path, final long from,
      final long until) {

    final long[] packed = activityByPath.get(project + '\u0000' + path);
    if (packed == null) {
      return 0;
    }
    return packedLowerBound(packed, until) - packedLowerBound(packed, from);
  }

  /** How many commits the project recorded in {@code [from, until)}. The hotness denominator. */
  public int commitsOfProject(final String project, final long from, final long until) {
    final long[] times = commitsByProject.get(project);
    if (times == null) {
      return 0;
    }
    return timeLowerBound(times, until) - timeLowerBound(times, from);
  }

  /** Every merge event of every project, which {@code owner_merged_ratio} needs across projects. */
  public Iterable<List<Merge>> allMerges() {
    return mergesByProject.values();
  }

  /** The half-open slice {@code [from, until)} of a table already sorted by instant. */
  public static <T extends Timed> List<T> between(final List<T> sorted, final long from,
      final long until) {

    return sorted.subList(lowerBound(sorted, from), lowerBound(sorted, until));
  }

  /** Everything strictly before {@code until}. The rule the whole study is built on. */
  public static <T extends Timed> List<T> before(final List<T> sorted, final long until) {
    return sorted.subList(0, lowerBound(sorted, until));
  }

  /**
   * An instant and a commit index in one {@code long}, so the activity table is a primitive array.
   *
   * <p>Nineteen bits for the index covers the corpus's 452.286 commits with room to spare, and the
   * instant fits the rest: the largest packed value stays four orders of magnitude below overflow.
   * Sorting the packed values sorts by instant first, which is what the window search needs.
   */
  private static final int INDEX_BITS = 19;

  static long pack(final long instant, final int commitIndex) {
    if (commitIndex < 0 || commitIndex >= (1 << INDEX_BITS)) {
      throw new IllegalArgumentException("commit index does not fit in " + INDEX_BITS + " bits: "
          + commitIndex + " -- the corpus grew past what this packing assumes");
    }
    return (instant << INDEX_BITS) | commitIndex;
  }

  private static Map<String, long[]> packed(final Map<String, List<Long>> raw) {
    final Map<String, long[]> frozen = new HashMap<>(raw.size() * 2);
    raw.forEach((key, values) -> {
      final long[] array = new long[values.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = values.get(i);
      }
      java.util.Arrays.sort(array);
      // One commit touching one file twice is two rows and one commit. Deduplicated here, once.
      int kept = 0;
      for (int i = 0; i < array.length; i++) {
        if (i == 0 || array[i] != array[i - 1]) {
          array[kept++] = array[i];
        }
      }
      frozen.put(key, java.util.Arrays.copyOf(array, kept));
    });
    return Map.copyOf(frozen);
  }

  private static Map<String, long[]> sortedTimes(final Map<String, List<Long>> raw) {
    final Map<String, long[]> frozen = new HashMap<>(raw.size() * 2);
    raw.forEach((key, values) -> {
      final long[] array = new long[values.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = values.get(i);
      }
      java.util.Arrays.sort(array);
      frozen.put(key, array);
    });
    return Map.copyOf(frozen);
  }

  private static int packedLowerBound(final long[] sorted, final long instant) {
    return boundOf(sorted, instant << INDEX_BITS);
  }

  private static int timeLowerBound(final long[] sorted, final long instant) {
    return boundOf(sorted, instant);
  }

  private static int boundOf(final long[] sorted, final long key) {
    int low = 0;
    int high = sorted.length;
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (sorted[mid] < key) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  private static <T extends Timed> int lowerBound(final List<T> sorted, final long instant) {
    int low = 0;
    int high = sorted.size();
    while (low < high) {
      final int mid = (low + high) >>> 1;
      if (sorted.get(mid).at() < instant) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /**
   * The same tables with every row dated at or after {@code instant} removed.
   *
   * <p>Text is copied whole and not truncated, and that is not an oversight: the corpus keeps no
   * historical version of a title or body, so there is nothing dated to remove. It is exactly why
   * the three textual constructs are reported as not temporally validated rather than as passing.
   */
  public Tables truncatedBefore(final long instant) {
    final Builder builder = new Builder();
    pullRequests.forEach((id, pr) -> {
      if (pr.openedAt() < instant) {
        builder.pullRequests.put(id, pr);
      }
    });
    closedAt.forEach((id, at) -> {
      if (at < instant) {
        builder.closedAt.put(id, at);
      }
    });
    copyBefore(prsByProject, builder.prsByProject, instant);
    copyBefore(prsByCreator, builder.prsByCreator, instant);
    copyBefore(mergesByProject, builder.mergesByProject, instant);
    copyBefore(contactsByCreator, builder.contactsByCreator, instant);
    copyBefore(contactsByAuthor, builder.contactsByAuthor, instant);
    copyBefore(origin, builder.origin, instant);
    builder.text.putAll(text);

    // Dated, so truncatable: the activity a hotness window reads, and the project's commit counts.
    activityByPath.forEach((key, packed) -> {
      final int end = packedLowerBound(packed, instant);
      if (end > 0) {
        final List<Long> kept = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
          kept.add(packed[i]);
        }
        builder.activityByPath.put(key, kept);
      }
    });
    commitsByProject.forEach((project, times) -> {
      final int end = timeLowerBound(times, instant);
      if (end > 0) {
        final List<Long> kept = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
          kept.add(times[i]);
        }
        builder.commitsByProject.put(project, kept);
      }
    });

    // Undated, so copied whole. The consolidated diff carries no timestamp and no base -- which is
    // precisely why the six technical constructs are reported as not temporally validated rather
    // than as passing the time-travel test.
    builder.diff.putAll(diff);
    builder.pathsOfPr.putAll(pathsOfPr);
    return builder.build();
  }

  private static <T extends Timed> void copyBefore(final Map<String, List<T>> from,
      final Map<String, List<T>> into, final long instant) {

    from.forEach((key, values) -> into.put(key, new ArrayList<>(before(values, instant))));
  }

  /** A mutable view, for the injection the time-travel test performs. Never used by a real run. */
  public Builder toBuilder() {
    final Builder builder = new Builder();
    builder.pullRequests.putAll(pullRequests);
    builder.closedAt.putAll(closedAt);
    prsByProject.forEach((k, v) -> builder.prsByProject.put(k, new ArrayList<>(v)));
    prsByCreator.forEach((k, v) -> builder.prsByCreator.put(k, new ArrayList<>(v)));
    mergesByProject.forEach((k, v) -> builder.mergesByProject.put(k, new ArrayList<>(v)));
    contactsByCreator.forEach((k, v) -> builder.contactsByCreator.put(k, new ArrayList<>(v)));
    contactsByAuthor.forEach((k, v) -> builder.contactsByAuthor.put(k, new ArrayList<>(v)));
    builder.text.putAll(text);
    origin.forEach((k, v) -> builder.origin.put(k, new ArrayList<>(v)));
    builder.diff.putAll(diff);
    builder.pathsOfPr.putAll(pathsOfPr);
    activityByPath.forEach((k, v) -> {
      final List<Long> copy = new ArrayList<>(v.length);
      for (final long packed : v) {
        copy.add(packed);
      }
      builder.activityByPath.put(k, copy);
    });
    commitsByProject.forEach((k, v) -> {
      final List<Long> copy = new ArrayList<>(v.length);
      for (final long at : v) {
        copy.add(at);
      }
      builder.commitsByProject.put(k, copy);
    });
    return builder;
  }

  /** Collects the tables as the adapter walks the corpus, in any order. */
  public static final class Builder {

    private final Map<String, Pr> pullRequests = new HashMap<>();
    private final Map<String, Long> closedAt = new HashMap<>();
    private final Map<String, List<ProjectPr>> prsByProject = new HashMap<>();
    private final Map<String, List<CreatorPr>> prsByCreator = new HashMap<>();
    private final Map<String, List<Merge>> mergesByProject = new HashMap<>();
    private final Map<String, List<Contact>> contactsByCreator = new HashMap<>();
    private final Map<String, List<Contact>> contactsByAuthor = new HashMap<>();
    private final Map<String, Text> text = new HashMap<>();
    private final Map<String, List<Origin>> origin = new HashMap<>();
    private final Map<String, Diff> diff = new HashMap<>();
    private final Map<String, List<String>> pathsOfPr = new HashMap<>();
    private final Map<String, List<Long>> activityByPath = new HashMap<>();
    private final Map<String, List<Long>> commitsByProject = new HashMap<>();

    /**
     * Every pull request of the corpus, not only the population: history needs all of them.
     *
     * <p>{@code openedAt} may be null, and then the row enters only the ownership map: it can say
     * whose pull request somebody wrote on, and it cannot take part in any window, because there
     * is no instant to place it at. Dropping it entirely would lose the ownership, which is what
     * decides whether a comment is a contact between two people or somebody talking to themselves.
     */
    public Builder pullRequest(final String id, final String creator, final Long openedAt,
        final String project) {

      pullRequests.put(id, new Pr(creator, openedAt == null ? Long.MIN_VALUE : openedAt, project));
      if (openedAt == null) {
        return this;
      }
      prsByProject.computeIfAbsent(project, key -> new ArrayList<>())
          .add(new ProjectPr(openedAt, id));
      if (creator != null) {
        prsByCreator.computeIfAbsent(creator, key -> new ArrayList<>())
            .add(new CreatorPr(openedAt, id, project));
      }
      return this;
    }

    /** The project a pull request belongs to, for indexing an event that names only the request. */
    public String projectOf(final String pullRequestId) {
      final Pr pr = pullRequests.get(pullRequestId);
      return pr == null ? null : pr.project();
    }

    /** The earliest close, whatever closed it. A later one says nothing the first did not. */
    public Builder closed(final String id, final long at) {
      closedAt.merge(id, at, Math::min);
      return this;
    }

    public Builder merge(final String project, final long at, final String actor,
        final String pullRequestId) {

      mergesByProject.computeIfAbsent(project, key -> new ArrayList<>())
          .add(new Merge(at, actor, pullRequestId));
      return this;
    }

    /**
     * One comment or review, indexed from both ends.
     *
     * <p>Five kinds of row are dropped here, in this order, and the last one carries the meaning:
     * no pull request, no author, no instant, no known owner, and <b>the author is the owner</b>.
     * Writing on your own pull request is not a contact between two people, which is what makes
     * the case of a creator who is also an active integrator count once rather than twice.
     */
    public boolean contact(final String pullRequestId, final String author, final Long at) {
      if (pullRequestId == null || author == null || author.isEmpty() || at == null) {
        return false;
      }
      final Pr owner = pullRequests.get(pullRequestId);
      if (owner == null || owner.creator() == null || author.equals(owner.creator())) {
        return false;
      }
      contactsByCreator.computeIfAbsent(owner.creator(), key -> new ArrayList<>())
          .add(new Contact(at, author, pullRequestId));
      contactsByAuthor.computeIfAbsent(author, key -> new ArrayList<>())
          .add(new Contact(at, owner.creator(), pullRequestId));
      return true;
    }

    public Builder text(final String id, final Text value) {
      text.put(id, value);
      return this;
    }

    public Builder origin(final String project, final long at, final String source) {
      origin.computeIfAbsent(project, key -> new ArrayList<>()).add(new Origin(at, source));
      return this;
    }

    /** The consolidated diff and the distinct paths it touches, for one pull request. */
    public Builder diff(final String pullRequestId, final Diff value, final List<String> paths) {
      diff.put(pullRequestId, value);
      pathsOfPr.put(pullRequestId, List.copyOf(paths));
      return this;
    }

    /** One commit of the project, for the hotness denominator. */
    public Builder commit(final String project, final long at) {
      commitsByProject.computeIfAbsent(project, key -> new ArrayList<>()).add(at);
      return this;
    }

    /**
     * One commit touching one path of one project.
     *
     * @param commitIndex a dense index over the corpus's commits, so that a commit recorded twice
     *     against the same file collapses to one when the table is frozen
     */
    public Builder touched(final String project, final String path, final long at,
        final int commitIndex) {

      activityByPath.computeIfAbsent(project + '\u0000' + path, key -> new ArrayList<>())
          .add(pack(at, commitIndex));
      return this;
    }

    /** For the injected view only: a contact whose owner is stated rather than looked up. */
    public Builder rawContact(final String creator, final String author, final long at,
        final String pullRequestId) {

      contactsByCreator.computeIfAbsent(creator, key -> new ArrayList<>())
          .add(new Contact(at, author, pullRequestId));
      contactsByAuthor.computeIfAbsent(author, key -> new ArrayList<>())
          .add(new Contact(at, creator, pullRequestId));
      return this;
    }

    public int pullRequestCount() {
      return pullRequests.size();
    }

    public int contactCount() {
      return contactsByCreator.values().stream().mapToInt(List::size).sum();
    }

    public Tables build() {
      return new Tables(this);
    }
  }

  /** So that an empty table set is a value rather than a special case. */
  public static Tables empty() {
    return new Builder().build();
  }
}
