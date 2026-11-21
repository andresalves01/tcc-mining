package app.pipeline;

import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.World;
import app.issues.port.StudySource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One corpus held in memory for both extractions: issues of two projects that went to In Progress
 * and carry commits, comments, an assignee, attachments and texts, so the In Progress population
 * and the first-commit one are both non-empty and the columns have something to read.
 */
public final class ExtractionFixture {

  static final long H = World.HOUR;

  /** Noon UTC of a day of 2024: January 1 is a Monday. */
  public static long day(final int d) {
    return Instant.parse("2024-01-01T12:00:00Z").toEpochMilli() + (d - 1) * World.DAY;
  }

  public static String id(final int n) {
    return String.format("%024x", n);
  }

  final List<Corpus.Issue> issues = new ArrayList<>();
  final List<Corpus.Event> events = new ArrayList<>();
  final List<Corpus.Comment> comments = new ArrayList<>();
  final List<Corpus.Commit> commits = new ArrayList<>();
  final Map<String, Corpus.Person> people = new HashMap<>();
  final Map<String, Corpus.Contribution> contributions = new HashMap<>();
  final Map<String, int[]> files = new HashMap<>();
  final Map<String, FirstCommitFacts.Native> natives = new HashMap<>();
  final Map<String, Corpus.Text> texts = new HashMap<>();
  final Map<String, List<DurationEvidence.Activity>> activity = new HashMap<>();
  final Map<String, String> attachments = new HashMap<>();
  private int ext = 1;

  /** Noon of day {@code d} of the studied issue {@code n}: {@link #day}, {@code n} weeks later. */
  public static long at(final int n, final int d) {
    return day(d) + (long) n * 7 * World.DAY;
  }

  /**
   * The fixture with each studied issue's whole history -- creation, events, commits, comments,
   * activity and resolution -- moved a week later per issue number, so issue {@code n} starts
   * {@code n} weeks after the first: whole weeks, so each issue's business days and day boundaries
   * stay its own. The first commits spread over thirty weeks, each issue resolved about two weeks
   * after its start, so only the issues started just before a temporal cut cross it.
   */
  public ExtractionFixture() {
    people.put("dev0", new Corpus.Person("Dev Zero", false));
    people.put("dev1", new Corpus.Person("Dev One", false));
    people.put("dev0-alt", new Corpus.Person("Dev Zero", false));
    // a history before the studied issues: older issues and commits of both projects
    for (int n = 1; n <= 6; n++) {
      final String project = n % 2 == 0 ? "p" : "q";
      final int k = 900 + n;
      issue(project, k, day(1) - 40 * World.DAY + n * H, "old" + n, null);
      resolve(k, day(1) - 20 * World.DAY + n * H, "Fixed");
      commit("old" + n, project, n % 2 == 0 ? "dev0" : "dev1",
          day(1) - 30 * World.DAY + n * H, 25L + n, k);
    }
    for (int n = 1; n <= 30; n++) {
      final String project = n <= 22 ? "p" : "q";
      final String dev = "dev" + (n % 2);
      issue(project, n, at(n, 1) + n * 60_000L, "rep" + (n % 3), n % 4 == 0 ? "dev1" : null);
      if (n % 4 == 0) {
        events.add(new Corpus.Event(id(n), at(n, 2) - 2 * H, "assignee_id", "rep0", null, "dev1",
            (ext++) + "%%0"));
      }
      // In Progress the day after its creation, by its developer
      events.add(new Corpus.Event(id(n), at(n, 2) + n * 60_000L, "status", dev, "Open",
          "In Progress", (ext++) + "%%0"));
      if (n % 7 == 0) {
        events.add(new Corpus.Event(id(n), at(n, 4), "status", dev, "In Progress", "Open",
            (ext++) + "%%0"));
        events.add(new Corpus.Event(id(n), at(n, 5), "status", dev, "Open", "In Progress",
            (ext++) + "%%0"));
      }
      // Minor at the In Progress instant, Major from before the first commit on
      if (n % 4 == 2) {
        events.add(new Corpus.Event(id(n), at(n, 2) + 2 * H, "priority", "rep0", "Minor", "Major",
            (ext++) + "%%0"));
      }
      // the first commit carries a small share; the rest comes over the next days
      commit("a" + n, project, n % 5 == 0 ? "dev0-alt" : dev, at(n, 2) + 3 * H + n, 10L + n, n);
      commit("b" + n, project, dev, at(n, 3 + n % 6) + H, 40L + n, n);
      if (n % 3 == 0) {
        commit("c" + n, project, "dev1", at(n, 11) + 2 * H, 30L, n);
      }
      comments.add(new Corpus.Comment(id(n), "rev", at(n, 2) + 5 * H + n, 12, false));
      comments.add(new Corpus.Comment(id(n), "rep" + (n % 3), at(n, 1) + 2 * H + n, 8, false));
      final List<DurationEvidence.Activity> acts = new ArrayList<>();
      acts.add(new DurationEvidence.Activity(at(n, 2) + 5 * H + n, "comment", "cm" + n));
      acts.add(new DurationEvidence.Activity(at(n, 14) + 2 * H, "comment", "late" + n));
      if (n % 5 == 0) {
        acts.add(new DurationEvidence.Activity(at(n, 12), "attachment", "att" + n));
        attachments.put("att" + n, "fix-" + n + ".patch");
      }
      activity.put(id(n), acts);
      texts.put(id(n), new Corpus.Text("Issue " + n, "the parser fails on input " + n
          + " when the cache is cold and the reader retries", List.of()));
      resolve(n, n % 9 == 0 ? at(n, 2) + 6 * H : at(n, 15) + n * 60_000L, n == 13 ? "Won't Fix"
          : "Fixed");
    }
  }

  private void issue(final String project, final int n, final long created,
      final String reporter, final String assignee) {
    issues.add(new Corpus.Issue(id(n), project.toUpperCase(java.util.Locale.ROOT) + "-" + n,
        project, created, reporter, reporter, assignee, n % 2 == 0 ? "Bug" : "Improvement",
        "Major", null, List.of(), List.of()));
  }

  private void resolve(final int n, final long at, final String reason) {
    events.add(new Corpus.Event(id(n), at, "status", "rev", "In Progress", "Resolved",
        (ext++) + "%%0"));
    events.add(new Corpus.Event(id(n), at, "resolution", "rev", null, reason,
        (ext - 1) + "%%1"));
  }

  private void commit(final String sha, final String project, final String author,
      final long at, final long lines, final int issue) {
    final String cid = "c" + sha;
    commits.add(new Corpus.Commit(cid, project, sha, author, at, at + H, 1, List.of(id(issue))));
    contributions.put(cid, new Corpus.Contribution(false, 1, lines, 0));
    files.put(cid, new int[] {(int) (lines % 5), 7});
    natives.put(cid, new FirstCommitFacts.Native(lines, "work on " + sha));
  }

  /** A comment of {@code author} on issue {@code issue} at {@code at}. */
  public void comment(final String issue, final String author, final long at) {
    comments.add(new Corpus.Comment(issue, author, at, 5, false));
  }

  /** A commit of {@code lines} lines by {@code author} in {@code project}, linked to issue n. */
  public void commitOn(final String sha, final String project, final String author,
      final long at, final long lines, final int issue) {
    commit(sha, project, author, at, lines, issue);
  }

  /** The issue's description is {@code after} now; it was its fixture's until {@code at}. */
  public void describedLater(final String issue, final String after, final long at) {
    final Corpus.Text was = texts.get(issue);
    texts.put(issue, new Corpus.Text(was.title(), after, List.of(new Corpus.Event(issue, at,
        "desc", "rep0", was.description(), after, (ext++) + "%%0"))));
  }

  /** The description the fixture gives the issue at its creation. */
  public String description(final String issue) {
    return texts.get(issue).description();
  }

  Corpus corpus() {
    return new Corpus(issues, events, comments, List.of(), commits,
        Map.of("p", Set.of("P"), "q", Set.of("Q")), Map.of(), people, contributions, Map.of(),
        Map.of(), files, List.of());
  }

  public StudySource source() {
    final Corpus corpus = corpus();
    return new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        final Map<String, Corpus.Text> out = new HashMap<>();
        ids.forEach(i -> {
          if (texts.containsKey(i)) {
            out.put(i, texts.get(i));
          }
        });
        return out;
      }

      @Override
      public Map<String, DurationEvidence> durationEvidence(final List<RowCache.Entry> rows,
          final Set<String> bots) {
        final Map<String, DurationEvidence> out = new HashMap<>();
        rows.forEach(r -> out.put(r.unit().issueId(), new DurationEvidence(List.of(),
            activity.getOrDefault(r.unit().issueId(), List.of()), List.of(), "native",
            List.of(), List.of())));
        return out;
      }

      @Override
      public FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachmentIds) {
        final Map<String, FirstCommitFacts.Native> n = new HashMap<>();
        commitIds.forEach(c -> n.put(c, natives.get(c)));
        final Map<String, String> a = new HashMap<>();
        attachmentIds.forEach(x -> {
          if (attachments.containsKey(x)) {
            a.put(x, attachments.get(x));
          }
        });
        return new FirstCommitFacts(n, Map.of(), a);
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
  }
}
