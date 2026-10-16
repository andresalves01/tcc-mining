package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.labels.ChangedMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eight attributes of the retrospective scenario (ticket 15), observed between the first In
 * Progress (t0) and the first resolution (t1), on corpora built by hand.
 */
class RetrospectiveTest {

  private static final long H = World.HOUR;
  private static final long D = World.DAY;
  private static final long CREATED = 1_000 * D;
  private static final long T0 = CREATED + 2 * D;
  private static final long T1 = T0 + 10 * D;

  /** A small corpus under construction, every issue in project p. */
  private static final class Fixture {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Comment> comments = new ArrayList<>();
    final List<Corpus.Commit> commits = new ArrayList<>();
    final Map<String, Long> prMerge = new HashMap<>();
    final Map<String, Corpus.Person> people = new HashMap<>();
    final Map<String, Corpus.Contribution> contributions = new HashMap<>();
    final Map<String, int[]> files = new HashMap<>();
    final Map<String, Map<String, ChangedMethod>> methods = new HashMap<>();
    int ext = 1;

    /** An issue created at CREATED, moved to In Progress at T0 by dev, resolved Fixed at T1. */
    void started(final String id, final String key, final String reporter) {
      issues.add(new Corpus.Issue(id, key, "p", CREATED, reporter, reporter, null, "Bug",
          "Major", null, List.of(), List.of()));
      event(id, T0, "status", "dev", "Open", "In Progress");
      event(id, T1, "status", "closer", "In Progress", "Resolved");
      event(id, T1, "resolution", "closer", null, "Fixed");
    }

    void event(final String issue, final long at, final String field, final String author,
        final String from, final String to) {
      events.add(new Corpus.Event(issue, at, field, author, from, to, (ext++) + "%%0"));
    }

    void comment(final String issue, final String author, final long at) {
      comments.add(new Corpus.Comment(issue, author, at, 3));
    }

    /** A commit authored and committed at {@code at}, its Java lines and files. */
    Corpus.Commit commit(final String id, final String author, final long at, final long java,
        final int[] touched, final String... linked) {
      final Corpus.Commit c = new Corpus.Commit(id, "p", "h" + id, author, at, at, 1,
          List.of(linked));
      commits.add(c);
      contributions.put(id, new Corpus.Contribution(false, java > 0 ? 1 : 0, java, 0));
      files.put(id, touched);
      return c;
    }

    void method(final String commit, final String name, final double mccc, final double nl) {
      methods.computeIfAbsent(commit, k -> new HashMap<>())
          .put(name, new ChangedMethod(name, mccc, nl));
    }

    Corpus build() {
      return new Corpus(issues, events, comments, List.of(), commits,
          Map.of("p", Set.of("P")), prMerge, people, contributions, Map.of(), Map.of(), files);
    }

    Map<String, Double> row(final String id, final Set<String> bots) {
      final Corpus corpus = build();
      final Universe universe = Universe.of(corpus);
      final Retrospective r = new Retrospective(new World(universe, corpus), bots);
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      assertNotNull(u, id + " is in the In-Progress population");
      return r.row(u, methods);
    }

    Map<String, Double> row(final String id) {
      return row(id, Set.of());
    }
  }

  @Test
  @DisplayName("no commit visible at or after t1 enters any content attribute; commits before t0 "
      + "do, as do those of t0 to t1, bots' never")
  void implementationCommitsAreVisibleBeforeTheResolution() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.commit("before", "dev", T0 - D, 10, new int[] {1}, "a");
    f.method("before", "M.a()", 2, 1);
    f.commit("during", "dev", T0 + D, 20, new int[] {2}, "a");
    f.method("during", "M.b()", 4, 3);
    // authored before t1, visible only when its pull request was merged, at t1
    f.commit("merged", "dev", T1 - D, 400, new int[] {3}, "a");
    f.prMerge.put("hmerged", T1);
    f.method("merged", "M.c()", 100, 100);
    f.commit("after", "dev", T1 + D, 800, new int[] {4}, "a");
    f.method("after", "M.d()", 100, 100);
    f.commit("robot", "ci", T0 + D, 1600, new int[] {5}, "a");
    f.contributions.put("robot", new Corpus.Contribution(true, 1, 1600, 0));
    f.method("robot", "M.e()", 100, 100);

    final Map<String, Double> v = f.row("a");
    assertEquals(30.0, v.get(Retrospective.LINES));
    assertEquals(3.0, v.get(Retrospective.MCCC));
    assertEquals(2.0, v.get(Retrospective.NESTING));
    assertEquals(2.0, v.get(Retrospective.FILES));
  }

  @Test
  @DisplayName("Java lines are split evenly among the issues a commit cites")
  void linesSplitAmongCitedIssues() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.commit("shared", "dev", T0 + D, 30, new int[] {1}, "a", "b", "c");
    f.commit("own", "dev", T0 + 2 * D, 5, new int[] {2}, "a");
    assertEquals(15.0, f.row("a").get(Retrospective.LINES), 1e-12);
  }

  @Test
  @DisplayName("commits without Java give 0 lines and no complexity or nesting, the files and "
      + "familiarity still read")
  void commitsWithoutJava() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.commit("docs", "dev", T0 + D, 0, new int[] {7}, "a");
    final Map<String, Double> v = f.row("a");
    assertEquals(0.0, v.get(Retrospective.LINES));
    assertNull(v.get(Retrospective.MCCC));
    assertNull(v.get(Retrospective.NESTING));
    assertEquals(1.0, v.get(Retrospective.FILES));
    assertEquals(0.0, v.get(Retrospective.FAMILIARITY));
  }

  @Test
  @DisplayName("without an implementation commit the five content attributes are missing, never "
      + "0, and the three of the discussion are still counted")
  void noCommitIsMissingNotZero() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    final Map<String, Double> v = f.row("a");
    for (final String column : Retrospective.CONTENT) {
      assertNull(v.get(column), column);
    }
    assertEquals(0.0, v.get(Retrospective.COMMENTS));
    assertEquals(1.0, v.get(Retrospective.PARTICIPANTS), "dev, who moved it to In Progress");
    assertEquals(0.0, v.get(Retrospective.ASSIGNEE_CHANGES));
    assertEquals(Retrospective.COLUMNS.size(), v.size());
  }

  @Test
  @DisplayName("complexity and nesting are means over the methods of the implementation commits "
      + "only, each method once at its latest state, whoever wrote the commits")
  void complexityDeduplicatedByLatestState() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.commit("first", "ann", T0 - D, 10, new int[] {1}, "a");
    f.method("first", "M.m()", 2, 1);
    f.method("first", "M.k()", 9, 9);
    f.commit("second", "bob", T0 + D, 10, new int[] {1}, "a");
    f.method("second", "M.m()", 6, 3);
    f.method("second", "M.n()", 4, 1);
    f.commit("third", "ann", T0 + 2 * D, 10, new int[] {1}, "a");
    f.method("third", "M.k()", 1, 2);
    // a commit of another issue, and one of this issue visible after t1, change nothing
    f.commit("other", "ann", T0 + D, 10, new int[] {1}, "b");
    f.method("other", "M.m()", 50, 50);
    f.commit("late", "ann", T1 + H, 10, new int[] {1}, "a");
    f.method("late", "M.n()", 50, 50);

    final Map<String, Double> v = f.row("a");
    // m at 6 (second), k at 1 (third), n at 4 (second)
    assertEquals((6 + 1 + 4) / 3.0, v.get(Retrospective.MCCC), 1e-12);
    assertEquals((3 + 2 + 1) / 3.0, v.get(Retrospective.NESTING), 1e-12);
  }

  @Test
  @DisplayName("of two commits with the same authoring instant, the later revision hash decides "
      + "the state that stays")
  void tiesBrokenByRevisionHash() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.commit("y", "ann", T0 + D, 10, new int[] {1}, "a");
    f.method("y", "M.m()", 8, 8);
    f.commit("x", "bob", T0 + D, 11, new int[] {1}, "a");
    f.method("x", "M.m()", 2, 2);
    assertEquals(8.0, f.row("a").get(Retrospective.MCCC), "hy sorts after hx");
  }

  @Test
  @DisplayName("familiarity: the share of the changed files any of the issue's authors touched in "
      + "the project strictly before t0, the issue's own commits never counted")
  void familiarity() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.started("b", "P-2", "r");
    f.commit("i1", "dev1", T0 - D, 10, new int[] {1, 3}, "a");
    f.commit("i2", "dev2", T0 + D, 10, new int[] {2, 4, 5, 6}, "a");
    f.commit("h1", "dev1", T0 - 5 * D, 10, new int[] {1});       // known by dev1
    f.commit("h2", "dev2", T0 - 5 * D, 10, new int[] {2}, "b");  // known by dev2, another issue
    f.commit("h3", "dev1", T0, 10, new int[] {4});               // at t0: not before it
    f.commit("h4", "stranger", T0 - 5 * D, 10, new int[] {5});   // not an author of a
    // file 3 only in a's own commit before t0; file 6 in a's own commit visible after t1
    f.commit("late", "dev1", T1 + D, 10, new int[] {6}, "a");
    f.commits.add(new Corpus.Commit("elsewhere", "q", "helsewhere", "dev2", T0 - 5 * D,
        T0 - 5 * D, 1, List.of()));
    f.files.put("elsewhere", new int[] {6});                     // another project

    final Map<String, Double> v = f.row("a");
    assertEquals(6.0, v.get(Retrospective.FILES));
    assertEquals(2 / 6.0, v.get(Retrospective.FAMILIARITY), 1e-12);
  }

  @Test
  @DisplayName("an author's history is read as their canonical person, any of their accounts")
  void familiarityByCanonicalPerson() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.people.put("dev-old", new Corpus.Person("person-1", false));
    f.people.put("dev-new", new Corpus.Person("person-1", false));
    f.commit("i1", "dev-new", T0 + D, 10, new int[] {1, 2}, "a");
    f.commit("h1", "dev-old", T0 - D, 10, new int[] {2});
    assertEquals(0.5, f.row("a").get(Retrospective.FAMILIARITY), 1e-12);
  }

  @Test
  @DisplayName("comments count only in [t0, t1), bots' left out")
  void comments() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.comment("a", "u1", T0 - H);
    f.comment("a", "u1", T0);
    f.comment("a", "u2", T0 + D);
    f.comment("a", "bot", T0 + D);
    f.comment("a", "u2", T1);
    f.comment("a", "u2", T1 + D);
    assertEquals(2.0, f.row("a", Set.of("bot")).get(Retrospective.COMMENTS));
  }

  @Test
  @DisplayName("participants: distinct people who commented or changed a field in [t0, t1), bots "
      + "out, the reporter only if active, the resolution's action, ProjectImport events and commit "
      + "authors not counted")
  void participants() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.people.put("u1", new Corpus.Person("person-1", false));
    f.people.put("u1-alias", new Corpus.Person("person-1", false));
    f.comment("a", "u1", T0 + D);
    f.comment("a", "u1-alias", T0 + 2 * D);
    f.event("a", T0 + D, "priority", "u3", "Major", "Minor");
    f.comment("a", "bot", T0 + D);
    f.event("a", T0 + D, "labels", "bot", "", "x");
    f.comment("a", "early", T0 - H);
    f.event("a", T0 - H, "priority", "early", "Minor", "Major");
    f.event("a", T0 + D, "ProjectImport", "importer", null, "x");
    f.commit("c", "coder", T0 + D, 10, new int[] {1}, "a");
    // dev (the move to In Progress at t0), person-1 and u3; not r, closer, bot, early, importer
    // (a ProjectImport event is a migration, not a person taking part) or coder
    assertEquals(3.0, f.row("a", Set.of("bot")).get(Retrospective.PARTICIPANTS));

    final Fixture g = new Fixture();
    g.started("a", "P-1", "r");
    g.comment("a", "r", T0 + D);
    assertEquals(2.0, g.row("a").get(Retrospective.PARTICIPANTS), "dev and the active reporter");
  }

  @Test
  @DisplayName("assignee changes: every effective change of the assignee in [t0, t1) counts alike; "
      + "events that leave it unchanged after normalisation, bots' and those outside do not")
  void assigneeChanges() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", "r");
    f.event("a", T0 - H, "assignee_id", "u", null, "x");
    f.event("a", T0, "assignee_id", "u", "x", null);           // person -> nobody
    f.event("a", T0 + H, "assignee_id", "u", null, "u1");      // nobody -> person
    f.event("a", T0 + 2 * H, "assignee_id", "u", "u1", "u2");  // person -> another
    f.event("a", T0 + 3 * H, "assignee_id", "u", "u2", " u2 "); // unchanged after normalisation
    f.event("a", T0 + 4 * H, "assignee_id", "u", "u2", " ");   // person -> nobody (blank)
    f.event("a", T0 + 5 * H, "assignee_id", "u", " ", null);   // nobody -> nobody
    f.event("a", T0 + 6 * H, "assignee_id", "bot", null, "u3");
    f.event("a", T1, "assignee_id", "u", null, "u4");
    assertEquals(4.0, f.row("a", Set.of("bot")).get(Retrospective.ASSIGNEE_CHANGES));
  }
}
