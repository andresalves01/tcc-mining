package app.paper6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The study's rules on a corpus built by hand, each with the case that would break it. */
class StudyTest {

  private static final long D = World.DAY;
  private static final long T0 = 1_000 * D;

  /** A small corpus under construction. */
  private static final class Fixture {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Comment> comments = new ArrayList<>();
    final List<Corpus.Commit> commits = new ArrayList<>();
    final Map<String, Long> prMerge = new HashMap<>();
    final Map<String, Corpus.Person> people = new HashMap<>();
    final Map<String, Corpus.Contribution> contributions = new HashMap<>();
    final Map<String, Corpus.Label> labels = new HashMap<>();
    int ext = 1;

    void issue(final String id, final String key, final long created, final String reporter) {
      issues.add(new Corpus.Issue(id, key, "p", created, reporter, reporter, null, "Bug", "Major",
          null, List.of(), List.of()));
    }

    void resolve(final String id, final long at, final String reason) {
      events.add(new Corpus.Event(id, at, "status", "x", "Open", "Resolved", (ext++) + "%%0"));
      if (reason != null) {
        events.add(new Corpus.Event(id, at, "resolution", "x", null, reason, (ext - 1) + "%%1"));
      }
    }

    void commit(final String id, final String author, final long authored, final long committed,
        final String... linked) {
      commits.add(new Corpus.Commit(id, "p", "h" + id, author, authored, committed, 1,
          List.of(linked)));
      contributions.put(id, new Corpus.Contribution(false, 1, 10, 0));
    }

    Corpus build() {
      return new Corpus(issues, events, comments, List.of(), commits,
          Map.of("p", Set.of("P")), prMerge, people, contributions, Map.of(), labels, Map.of());
    }
  }

  @Test
  @DisplayName("home keys only; of two copies of a key the longer changelog stays; Fixed is read at "
      + "the first resolution, not at the end")
  void universe() {
    final Fixture f = new Fixture();
    f.issue("a1", "P-1", T0, "r");
    f.issue("a2", "P-1", T0, "r");          // a copy of the same key, collected later
    f.issue("b", "OTHER-9", T0, "r");        // a foreign copy
    f.issue("c", "P-2", T0, "r");
    f.resolve("a2", T0 + D, "Fixed");
    f.resolve("c", T0 + 2 * D, "Duplicate");
    // c is later reopened and closed as Fixed: the first resolution's reason stands
    f.events.add(new Corpus.Event("c", T0 + 3 * D, "status", "x", "Resolved", "Reopened", "90%%0"));
    f.events.add(new Corpus.Event("c", T0 + 4 * D, "resolution", "x", "Duplicate", "Fixed",
        "91%%0"));
    final Universe u = Universe.of(f.build());

    assertEquals(Set.of("a2", "c"), Set.copyOf(u.universe()));
    assertEquals(List.of("a2"), u.population(Universe.Population.FIXED).stream()
        .map(Universe.Unit::id).toList());
    assertEquals("Duplicate", u.unit("c").reason());
    assertEquals(2, u.population(Universe.Population.BROAD).size());
  }

  @Test
  @DisplayName("the split: created and resolved before the cut trains, resolved after crosses")
  void split() {
    final Fixture f = new Fixture();
    for (int i = 0; i < 10; i++) {
      f.issue("i" + i, "P-" + i, T0 + i * D, "r");
      f.resolve("i" + i, T0 + i * D + (i == 5 ? 10 * D : D / 2), "Fixed");
    }
    final Universe.Split s = Universe.of(f.build()).split(Universe.Population.FIXED, 0.70);
    assertEquals(T0 + 6 * D, s.cut(), "position int(0.7 * 9) = 6");
    assertEquals(Universe.Part.CROSSES, s.outer().get("i5"));
    assertEquals(Universe.Part.TRAIN, s.outer().get("i4"));
    assertEquals(Universe.Part.TEST, s.outer().get("i6"), "created at the cut is test");
  }

  @Test
  @DisplayName("strict t0: an edit stamped at the creation instant is undone too")
  void strictCreation() {
    final Corpus.Issue issue = new Corpus.Issue("a", "P-1", "p", T0, "r", "r", "dev", "Task",
        "Minor", null, List.of("x"), List.of("t1", "t1"));
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("a", T0, "issue_type", "r", "Bug", "Task", "1%%0"),
        new Corpus.Event("a", T0 + D, "assignee_id", "r", null, "dev", "2%%0"),
        new Corpus.Event("a", T0 + D, "labels", "r", "", "x", "2%%1"),
        new Corpus.Event("a", T0 + 2 * D, "issue_links", "r", null, "t1", "3%%0"));
    final Initial.Values v = Initial.of(issue, events, null);
    assertEquals("Bug", v.type());
    assertNull(v.assignee());
    assertTrue(v.labels().isEmpty());
    assertEquals(List.of("t1"), v.links(), "one link to t1 existed at creation, one came later");
  }

  @Test
  @DisplayName("a commit written before t0 but visible only after it is not in the history; a "
      + "reporter with no visible commit anywhere has unknown commits, not zero")
  void visibilityAndIdentity() {
    final Fixture f = new Fixture();
    f.issue("x", "P-1", T0, "r");
    f.resolve("x", T0 + 10 * D, "Fixed");
    f.issue("y", "P-2", T0 + 20 * D, "s");
    f.resolve("y", T0 + 30 * D, "Fixed");
    // written 5 days before x opened, reached the repository through a PR merged after it
    f.commit("c1", "r", T0 - 5 * D, T0 - 5 * D);
    f.prMerge.put("hc1", T0 + D);
    // s committed visibly before y opened
    f.commit("c2", "s", T0 + D, T0 + D);
    final Corpus corpus = f.build();
    final Universe u = Universe.of(corpus);
    final World w = new World(u, corpus);
    final Extractor e = new Extractor(w, u.studied());

    final Map<String, Double> x = e.row(u.unit("x"), Initial.of(u.issue("x"), u.events("x"),
        null), null);
    assertEquals(0.0, x.get("K16_project_commits_90d"), "c1 was not visible at t0");
    assertEquals(0.0, x.get("REP_git_known_t0"));
    assertNull(x.get("K13_creator_commits_90d"), "unknown, not zero");
    assertEquals(1.0, x.get("K13x_reporter_no_commit_ever"));

    final Map<String, Double> y = e.row(u.unit("y"), Initial.of(u.issue("y"), u.events("y"),
        null), null);
    assertEquals(1.0, y.get("REP_git_known_t0"));
    assertEquals(1.0, y.get("K13_creator_commits_90d"));
    assertEquals(2.0, y.get("K16_project_commits_90d"), "c1 is visible by then");
  }

  @Test
  @DisplayName("a commit linked to two issues gives each half its lines; the whole commit is kept "
      + "for the sensitivity; nothing after the resolution counts")
  void sharedCommit() {
    final Fixture f = new Fixture();
    f.issue("a", "P-1", T0, "r");
    f.resolve("a", T0 + 10 * D, "Fixed");
    f.issue("b", "P-2", T0, "r");
    f.resolve("b", T0 + 10 * D, "Fixed");
    f.commit("c1", "dev", T0 + D, T0 + D, "a", "b");
    f.commit("c2", "dev", T0 + 20 * D, T0 + 20 * D, "a"); // after the resolution
    final Corpus corpus = f.build();
    final Universe u = Universe.of(corpus);
    final Extractor e = new Extractor(new World(u, corpus), u.studied());
    final Map<String, Double> a = e.row(u.unit("a"), Initial.of(u.issue("a"), u.events("a"),
        null), null);
    assertEquals(5.0, a.get("A15_change_loc"));
    assertEquals(10.0, a.get("A15w_change_loc_whole"));
    assertEquals(1.0, a.get("EXE_known"));
    assertFalse(a.get("CODE_linked_after_res_only") > 0);
  }

  @Test
  @DisplayName("text measures agree with Python: Unicode spaces split words, code blocks and URLs "
      + "are removed before cleaning counts, fog needs a word")
  void text() {
    assertEquals(3, TextMeasures.words("a b  c"));
    assertEquals(2, TextMeasures.words(TextMeasures.cleaned(
        "see {code:java}x = 1;{code} http://a.b/c done")));
    assertNull(TextMeasures.fog("1234 !!"));
    assertEquals(0.4 * (2.0 / 1 + 0), TextMeasures.fog("Fix bug."), 1e-12);
    assertEquals(3, TextMeasures.syllables("beautiful"));
    assertEquals(2, TextMeasures.length("😀a"), "code points, not UTF-16 units");
  }
}
