package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The corpus indexed in time: how much of each thing existed before an instant.
 *
 * <p>The Java twin of {@code papers/issues/viabilidade/analises/mundo.py}. The rules fixed here,
 * once, for every column:
 *
 * <ul>
 *   <li>the history counts over the {@link Universe#universe()}: home, one per key, with a creation
 *       instant;
 *   <li>an issue counts as resolved at its first transition to Resolved/Closed, and as fixed
 *       (Al-Zubaidi) when the reason at that instant is Fixed;
 *   <li>a commit is <b>visible</b> at the first merge of a pull request that contains it, else at its
 *       commit date, never before it was authored; merge commits do not count;
 *   <li>a person is the thesis's canonical identity when there is one, else the raw id;
 *   <li>a person's Git identity is <b>known at t</b> when they had a visible commit, in any project,
 *       before t -- the Jira–Git bridge alone would use commits made later.
 * </ul>
 *
 * <p>Every window is half open, {@code [lo, hi)}, and read by binary search.
 */
public final class World {

  public static final long HOUR = 3_600_000L;
  public static final long DAY = 86_400_000L;

  /** A commit as the columns see it: visibility decided, person resolved. */
  public record Commit(String id, String project, String hash, String who, boolean bot,
      long authoredAt, long visible, boolean java, long loc, Double mccc, List<String> linked) {}

  /** One issue of a reporter's history. {@code fixedAt} is the first resolution when it was Fixed. */
  record Reported(long created, Long resolved, Long fixedAt, String project, String id) {}

  final Universe universe;
  final Corpus corpus;

  private final Map<String, long[]> projectCreated = new HashMap<>();
  private final Map<String, long[]> projectResolved = new HashMap<>();
  private final Map<String, List<Created>> projectWindow = new HashMap<>();
  private final Map<String, long[]> projectWindowCreated = new HashMap<>();
  private final Map<String, List<Reported>> reporter = new HashMap<>();
  private final Map<String, Commit[]> projectCommits = new HashMap<>();
  private final Map<String, long[]> projectCommitTimes = new HashMap<>();
  private final Map<String, History> history = new HashMap<>();
  private final Map<String, Long> firstCommitAny = new HashMap<>();
  private final Map<String, List<Commit>> linked = new HashMap<>();
  private final Map<String, long[]> mentions = new HashMap<>();
  private final Map<String, List<Corpus.Comment>> comments = new HashMap<>();
  private final List<Commit> allCommits = new ArrayList<>();

  /** An issue in a project's creation order, with its first resolution when it has one. */
  public record Created(long created, Long resolved, String id) {}

  /** A person's commits in one project, in visibility order, with running sums. */
  private record History(long[] times, long[] loc, double[] mccc, int[] java, int[] javaCovered,
      String[] ids) {}

  public World(final Universe universe, final Corpus corpus) {
    this.universe = universe;
    this.corpus = corpus;
    indexIssues();
    indexCommits();
    indexComments();
  }

  // ------------------------------------------------------------------ people

  public String person(final String id) {
    if (id == null) {
      return null;
    }
    final Corpus.Person p = corpus.people().get(id);
    return p != null && p.canonical() != null ? p.canonical() : id;
  }

  private boolean personIsBot(final String id) {
    final Corpus.Person p = id == null ? null : corpus.people().get(id);
    return p != null && p.bot();
  }

  public boolean gitKnownAt(final String who, final long t) {
    if (who == null) {
      return false;
    }
    final Long first = firstCommitAny.get(who);
    return first != null && first < t;
  }

  // ------------------------------------------------------------------ issues

  private void indexIssues() {
    final Map<String, List<Long>> created = new HashMap<>();
    final Map<String, List<Long>> resolved = new HashMap<>();
    for (final String id : universe.universe()) {
      final Corpus.Issue issue = universe.issue(id);
      final List<Corpus.Event> events = universe.events(id);
      final Long first = Universe.firstResolution(events);
      created.computeIfAbsent(issue.project(), k -> new ArrayList<>()).add(issue.created());
      if (first != null) {
        resolved.computeIfAbsent(issue.project(), k -> new ArrayList<>()).add(first);
      }
      projectWindow.computeIfAbsent(issue.project(), k -> new ArrayList<>())
          .add(new Created(issue.created(), first, id));
      final String who = person(issue.reporter());
      if (who != null) {
        final Long fixedAt = first != null && "Fixed".equals(Universe.reasonAt(events, first))
            ? first : null;
        reporter.computeIfAbsent(who, k -> new ArrayList<>())
            .add(new Reported(issue.created(), first, fixedAt, issue.project(), id));
      }
    }
    created.forEach((p, v) -> projectCreated.put(p, sorted(v)));
    resolved.forEach((p, v) -> projectResolved.put(p, sorted(v)));
    projectWindow.forEach((p, v) -> {
      v.sort((a, b) -> a.created() != b.created() ? Long.compare(a.created(), b.created())
          : a.id().compareTo(b.id()));
      projectWindowCreated.put(p, v.stream().mapToLong(Created::created).toArray());
    });
    reporter.values().forEach(v -> v.sort((a, b) -> a.created() != b.created()
        ? Long.compare(a.created(), b.created()) : a.id().compareTo(b.id())));
  }

  private static long[] sorted(final List<Long> values) {
    final long[] out = values.stream().mapToLong(Long::longValue).toArray();
    Arrays.sort(out);
    return out;
  }

  static int lowerBound(final long[] a, final long key) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (a[mid] < key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static int count(final long[] a, final long lo, final long hi) {
    return a == null ? 0 : lowerBound(a, hi) - lowerBound(a, lo);
  }

  public int countCreated(final String project, final long lo, final long hi) {
    return count(projectCreated.get(project), lo, hi);
  }

  public int countResolved(final String project, final long lo, final long hi) {
    return count(projectResolved.get(project), lo, hi);
  }

  /** The project's issues created in {@code [lo, hi)}, in creation order. */
  public List<Created> createdIn(final String project, final long lo, final long hi) {
    final long[] a = projectWindowCreated.get(project);
    if (a == null) {
      return List.of();
    }
    return projectWindow.get(project).subList(lowerBound(a, lo), lowerBound(a, hi));
  }

  /** Issues of the project created before {@code t} and not yet resolved at {@code t}. */
  public int openAt(final String project, final long t) {
    return countCreated(project, Long.MIN_VALUE, t) - countResolved(project, Long.MIN_VALUE, t);
  }

  public long projectFirstCreated(final String project) {
    return projectCreated.get(project)[0];
  }

  List<Reported> reported(final String who) {
    return who == null ? List.of() : reporter.getOrDefault(who, List.of());
  }

  // ------------------------------------------------------------------ commits

  private void indexCommits() {
    final Map<String, List<Commit>> byProject = new HashMap<>();
    final Map<String, List<Commit>> byPerson = new HashMap<>();
    for (final Corpus.Commit c : corpus.commits()) {
      if (c.parents() > 1 || c.project() == null || c.authoredAt() == null) {
        continue;
      }
      final long at = c.authoredAt();
      final long committed = c.committedAt() != null ? c.committedAt() : at;
      final Long merge = corpus.prMerge().get(c.hash());
      final long visible = visible(at, committed, merge);
      final String who = person(c.author());
      final Corpus.Contribution k = corpus.contributions().get(c.id());
      final boolean bot = (k != null && k.bot()) || personIsBot(c.author());
      final Commit commit = new Commit(c.id(), c.project(), c.hash(), who, bot, at, visible,
          k != null && k.javaFiles() > 0, k == null ? 0 : k.javaAdded() + k.javaDeleted(),
          corpus.complexity().get(c.id()), c.linked());
      allCommits.add(commit);
      byProject.computeIfAbsent(c.project(), p -> new ArrayList<>()).add(commit);
      if (who != null) {
        byPerson.computeIfAbsent(who + "\u0000" + c.project(), p -> new ArrayList<>()).add(commit);
        firstCommitAny.merge(who, visible, Math::min);
      }
      for (final String issue : c.linked()) {
        linked.computeIfAbsent(issue, i -> new ArrayList<>()).add(commit);
      }
    }
    byProject.forEach((p, list) -> {
      list.sort((a, b) -> a.visible() != b.visible() ? Long.compare(a.visible(), b.visible())
          : a.id().compareTo(b.id()));
      projectCommits.put(p, list.toArray(new Commit[0]));
      projectCommitTimes.put(p, list.stream().mapToLong(Commit::visible).toArray());
    });
    byPerson.forEach((k, list) -> {
      list.sort((a, b) -> a.visible() != b.visible() ? Long.compare(a.visible(), b.visible())
          : a.id().compareTo(b.id()));
      final int n = list.size();
      final long[] times = new long[n];
      final long[] loc = new long[n + 1];
      final double[] mccc = new double[n + 1];
      final int[] java = new int[n + 1];
      final int[] covered = new int[n + 1];
      final String[] ids = new String[n];
      for (int i = 0; i < n; i++) {
        final Commit c = list.get(i);
        times[i] = c.visible();
        ids[i] = c.id();
        loc[i + 1] = loc[i] + c.loc();
        // decision 8: a commit without McCC adds nothing, and the coverage says how much was seen
        mccc[i + 1] = mccc[i] + (c.mccc() == null ? 0.0 : c.mccc());
        java[i + 1] = java[i] + (c.java() ? 1 : 0);
        covered[i + 1] = covered[i] + (c.java() && c.mccc() != null ? 1 : 0);
      }
      history.put(k, new History(times, loc, mccc, java, covered, ids));
    });
  }

  /**
   * When a commit reached the repository: at the first merge of a pull request that contains it,
   * else at its commit date, never before it was authored. A commit brought by a pull request has a
   * commit date from the contributor's copy, usually days before the merge -- taking the earlier of
   * the two would date it by that copy, which is the defect this rule exists to avoid.
   */
  public static long visible(final long authored, final long committed, final Long merge) {
    return Math.max(merge != null ? merge : committed, authored);
  }

  public int countCommits(final String project, final long lo, final long hi) {
    return count(projectCommitTimes.get(project), lo, hi);
  }

  /** Distinct human people with a visible commit in the project in {@code [lo, hi)}. */
  public Set<String> authorsIn(final String project, final long lo, final long hi) {
    final long[] times = projectCommitTimes.get(project);
    final Set<String> out = new HashSet<>();
    if (times == null) {
      return out;
    }
    final Commit[] commits = projectCommits.get(project);
    for (int i = lowerBound(times, lo); i < lowerBound(times, hi); i++) {
      if (commits[i].who() != null && !commits[i].bot()) {
        out.add(commits[i].who());
      }
    }
    return out;
  }

  /** A person's history in a project before t: commits, Java LOC, McCC, first commit, coverage. */
  public record PersonHistory(int commits, long loc, double mccc, Long first, int javaCommits,
      int javaCovered) {}

  public PersonHistory personHistory(final String who, final String project, final long t) {
    final History h = who == null ? null : history.get(who + "\u0000" + project);
    if (h == null) {
      return new PersonHistory(0, 0, 0.0, null, 0, 0);
    }
    final int k = lowerBound(h.times(), t);
    return new PersonHistory(k, h.loc()[k], h.mccc()[k], k > 0 ? h.times()[0] : null,
        h.java()[k], h.javaCovered()[k]);
  }

  public int personCommitsIn(final String who, final String project, final long lo,
      final long hi) {
    final History h = who == null ? null : history.get(who + "\u0000" + project);
    return h == null ? 0 : count(h.times(), lo, hi);
  }

  /** Every counted commit (not a merge, with a project and an author date), visibility decided. */
  public List<Commit> commits() {
    return allCommits;
  }

  public List<Commit> linked(final String issue) {
    return linked.getOrDefault(issue, List.of());
  }

  // ------------------------------------------------------------------ comments and mentions

  private void indexComments() {
    for (final Corpus.Comment c : corpus.comments()) {
      comments.computeIfAbsent(c.issue(), k -> new ArrayList<>()).add(c);
    }
    comments.values().forEach(v -> v.sort((a, b) -> {
      if (a.at() == null || b.at() == null) {
        return a.at() == null ? (b.at() == null ? 0 : 1) : -1;
      }
      return Long.compare(a.at(), b.at());
    }));
    final Map<String, String> keyToIssue = new HashMap<>();
    final Set<String> inUniverse = new HashSet<>(universe.universe());
    for (final String id : universe.universe()) {
      keyToIssue.put(universe.issue(id).key(), id);
    }
    final Map<String, List<Long>> by = new HashMap<>();
    for (final Corpus.Mention m : corpus.mentions()) {
      final String target = keyToIssue.get(m.key());
      if (target == null || m.at() == null || target.equals(m.sourceIssue())
          || !inUniverse.contains(m.sourceIssue())) {
        continue;
      }
      by.computeIfAbsent(target, k -> new ArrayList<>()).add(m.at());
    }
    by.forEach((k, v) -> mentions.put(k, sorted(v)));
  }

  public List<Corpus.Comment> comments(final String issue) {
    return comments.getOrDefault(issue, List.of());
  }

  public int mentionsIn(final String issue, final long lo, final long hi) {
    return count(mentions.get(issue), lo, hi);
  }
}
