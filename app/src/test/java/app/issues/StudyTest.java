package app.issues;

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
    final Map<String, Double> complexity = new HashMap<>();
    int ext = 1;

    void issue(final String id, final String key, final long created, final String reporter) {
      assigned(id, key, created, reporter, null);
    }

    /** An issue whose stored assignee is the last one its changelog sets. */
    void assigned(final String id, final String key, final long created, final String reporter,
        final String assignee) {
      issues.add(new Corpus.Issue(id, key, "p", created, reporter, reporter, assignee, "Bug",
          "Major", null, List.of(), List.of()));
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

    /** A Java commit linked to nothing, with its lines and its McCC (null when not measured). */
    void measured(final String id, final String author, final long at, final long loc,
        final Double mccc) {
      commits.add(new Corpus.Commit(id, "p", "h" + id, author, at, at, 1, List.of()));
      contributions.put(id, new Corpus.Contribution(false, 1, loc, 0));
      if (mccc != null) {
        complexity.put(id, mccc);
      }
    }

    Corpus build() {
      return new Corpus(issues, events, comments, List.of(), commits,
          Map.of("p", Set.of("P")), prMerge, people, contributions, complexity, labels,
          Map.of());
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
    assertEquals("Fixed", u.unit("a2").reason());
    assertEquals("Duplicate", u.unit("c").reason());
    assertEquals(Set.of("a2", "c"), history(u), "both are units of the history");
  }

  /** The issues with a unit: those every column's history reads. */
  private static Set<String> history(final Universe u) {
    final Set<String> out = new java.util.TreeSet<>();
    for (final String id : u.universe()) {
      if (u.unit(id) != null) {
        out.add(id);
      }
    }
    return out;
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
    final Extractor e = new Extractor(w, history(u));

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
    final Extractor e = new Extractor(new World(u, corpus), history(u));
    final Map<String, Double> a = e.row(u.unit("a"), Initial.of(u.issue("a"), u.events("a"),
        null), null);
    assertEquals(5.0, a.get("A15_change_loc"));
    assertEquals(10.0, a.get("A15w_change_loc_whole"));
    assertEquals(1.0, a.get("EXE_known"));
    assertFalse(a.get("CODE_linked_after_res_only") > 0);
  }

  @Test
  @DisplayName("a change copied to another branch counts once: the copy visible first stays, with "
      + "the issues every copy links")
  void copiedChange() {
    final Fixture f = new Fixture();
    f.issue("a", "P-1", T0, "r");
    f.resolve("a", T0 + 10 * D, "Fixed");
    f.issue("b", "P-2", T0, "r");
    f.resolve("b", T0 + 10 * D, "Fixed");
    f.issue("z", "P-3", T0 + 5 * D, "r");
    f.resolve("z", T0 + 6 * D, "Fixed");
    // one change authored once, applied to two branches: c1 reached the repository first
    f.commit("c1", "dev", T0 + D, T0 + D, "b");
    f.commit("c2", "dev", T0 + D, T0 + 2 * D, "a");
    // same person, same lines, another instant: a different change
    f.commit("c3", "dev", T0 + 3 * D, T0 + 3 * D);
    final Corpus corpus = f.build();
    final Universe u = Universe.of(corpus);
    final World w = new World(u, corpus);
    final Extractor e = new Extractor(w, history(u));

    assertEquals(List.of("c1", "c3"), w.commits().stream().map(World.Commit::id).toList());
    assertEquals(List.of("b", "a"), w.commits().get(0).linked());
    final Map<String, Double> a = e.row(u.unit("a"), Initial.of(u.issue("a"), u.events("a"),
        null), null);
    assertEquals(5.0, a.get("A15_change_loc"), "the kept copy links both issues");
    assertEquals(10.0, a.get("A15w_change_loc_whole"), "the lines enter once");
    assertEquals(1.0, a.get("K08_commit_refs"));
    final Map<String, Double> z = e.row(u.unit("z"), Initial.of(u.issue("z"), u.events("z"),
        null), null);
    assertEquals(2.0, z.get("K16_project_commits_90d"), "c1 and c3; c2 is the same change as c1");
  }

  @Test
  @DisplayName("in progress: the first In Progress before the resolution starts the duration; "
      + "one after the resolution does not count")
  void inProgressPopulation() {
    final Fixture f = new Fixture();
    for (int i = 0; i < 10; i++) {
      f.issue("i" + i, "P-" + i, T0, "r");
      f.events.add(new Corpus.Event("i" + i, T0 + i * D, "status", "dev", "Open", "In Progress",
          (f.ext++) + "%%0"));
      f.resolve("i" + i, T0 + i * D + (i == 5 ? 10 * D : D / 2), "Fixed");
    }
    f.issue("late", "P-90", T0, "r");
    f.resolve("late", T0 + D, "Fixed");
    f.events.add(new Corpus.Event("late", T0 + 2 * D, "status", "dev", "Resolved", "In Progress",
        (f.ext++) + "%%0"));
    final Universe u = Universe.of(f.build());

    assertEquals(10, u.population(Universe.Population.IN_PROGRESS).size(), "late is left out");
    final Universe.Unit i3 = u.unit(Universe.Population.IN_PROGRESS, "i3");
    assertEquals(T0 + 3 * D, i3.start());
    assertEquals("dev", i3.startedBy());
    assertEquals(12.0, i3.durationHours(), 1e-9, "from the In Progress, not from the creation");
    assertEquals(T0, u.unit("i3").start(), "the history's unit starts at creation");
  }

  @Test
  @DisplayName("strict t0 at the In Progress: changes before it stand, the one at it is undone")
  void initialAtStart() {
    final Corpus.Issue issue = new Corpus.Issue("a", "P-1", "p", T0, "r", "r", "dev", "Bug",
        "Critical", null, List.of(), List.of());
    final long t0 = T0 + 2 * D;
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("a", T0 + D, "assignee_id", "r", null, "dev", "1%%0"),
        new Corpus.Event("a", T0 + D, "priority", "r", "Minor", "Major", "1%%1"),
        new Corpus.Event("a", t0, "priority", "dev", "Major", "Critical", "2%%0"));
    final Initial.Values v = Initial.at(issue, events, null, t0);
    assertEquals("dev", v.assignee(), "assigned the day before the In Progress");
    assertEquals("Major", v.priority(), "raised the day before; the change at t0 is undone");
    assertEquals("Minor", Initial.of(issue, events, null).priority(), "creation is untouched");
  }

  @Test
  @DisplayName("at the In Progress: the executor's history and load are the known person's, an "
      + "assignment after t0 is not load, and the participants' commits count until t0")
  void executorAtStart() {
    final Fixture f = new Fixture();
    final long t0 = T0 + 10 * D;
    f.assigned("a", "P-1", T0, "r", "dev");
    f.events.add(new Corpus.Event("a", T0 + D, "assignee_id", "r", null, "dev", "1%%0"));
    f.events.add(new Corpus.Event("a", t0, "status", "dev", "Open", "In Progress", "2%%0"));
    f.resolve("a", t0 + 5 * D, "Fixed");
    f.assigned("b", "P-2", T0, "r", "dev");          // open, assigned to dev before t0: load
    f.events.add(new Corpus.Event("b", T0 + 2 * D, "assignee_id", "r", null, "dev", "3%%0"));
    f.assigned("c", "P-3", T0, "r", "dev");          // assigned to dev only after t0: not load
    f.events.add(new Corpus.Event("c", t0 + D, "assignee_id", "r", null, "dev", "4%%0"));
    f.comments.add(new Corpus.Comment("a", "s", T0 + 3 * D, 5, false)); // before t0: participant
    f.comments.add(new Corpus.Comment("a", "z", t0 + D, 5, false));     // after t0: not
    f.commit("c1", "dev", T0 - 20 * D, T0 - 20 * D);
    f.commit("c2", "s", t0 - 3 * D, t0 - 3 * D);
    f.commit("c3", "z", t0 - 2 * D, t0 - 2 * D);
    final Corpus corpus = f.build();
    final Universe u = Universe.of(corpus);
    final World w = new World(u, corpus);
    final Extractor e = new Extractor(w, Set.of("a"));
    final Universe.Unit unit = u.unit(Universe.Population.IN_PROGRESS, "a");
    final Initial.Values init = Initial.at(u.issue("a"), u.events("a"), null, unit.start());
    final Map<String, Double> row = e.row(unit, init, null,
        new Extractor.At(unit.start(), "dev", Set.of("r", "s")));

    assertEquals(1.0, row.get("A11_exec_commits"), "dev's one commit before t0");
    assertEquals(1.0, row.get("A13_exec_open_load"), "b, not a itself, not c");
    // a changelog whose assignment does not chain to the stored assignee: the stored value says
    // dev, the change set someone else; the issue was not counted, so it is not left out either
    final Fixture g = new Fixture();
    g.assigned("x", "P-1", T0, "r", "dev");
    g.events.add(new Corpus.Event("x", T0 + D, "assignee_id", "r", null, "dev2", "1%%0"));
    g.events.add(new Corpus.Event("x", t0, "status", "dev", "Open", "In Progress", "2%%0"));
    g.resolve("x", t0 + 5 * D, "Fixed");
    final Corpus broken = g.build();
    final Universe ub = Universe.of(broken);
    final Universe.Unit ux = ub.unit(Universe.Population.IN_PROGRESS, "x");
    final Initial.Values ix = Initial.at(ub.issue("x"), ub.events("x"), null, ux.start());
    assertEquals("dev", ix.assignee());
    assertEquals(0.0, new Extractor(new World(ub, broken), Set.of("x")).row(ux, ix, null,
        new Extractor.At(ux.start(), "dev", Set.of("r"))).get("A13_exec_open_load"),
        "never negative");
    assertEquals(1.0, row.get("K20c1_participant_commits_14d"), "s commented before t0");
    assertEquals(1.0, row.get("K21c1_participant_committers_14d"));
    assertEquals(1.0, row.get("ASG_present_c1"));

    final Map<String, Double> unknown = e.row(unit, init, null,
        new Extractor.At(unit.start(), "nobody", Set.of("r")));
    assertNull(unknown.get("A11_exec_commits"), "no Git identity at t0: unknown, not zero");
    assertEquals(0.0, unknown.get("A13_exec_open_load"), "the load is read from the changelog");
    assertNull(unknown.get("K20c1_participant_commits_14d"), "no participant known in Git");
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
    assertEquals("ab", Initial.norm("  ab "), "strip as Python: the no-break space goes");
    assertNull(Initial.norm(" "), "a description of spaces only is absent");
  }

  /** The row of issue "a", In Progress at {@code t0}, created ten days before it. */
  private static Map<String, Double> teamRow(final Fixture f, final long t0) {
    f.issue("a", "P-1", t0 - 10 * D, "r");
    f.events.add(new Corpus.Event("a", t0, "status", "dev", "Open", "In Progress",
        (f.ext++) + "%%0"));
    f.resolve("a", t0 + 5 * D, "Fixed");
    final Corpus corpus = f.build();
    final Universe u = Universe.of(corpus);
    final Universe.Unit unit = u.unit(Universe.Population.IN_PROGRESS, "a");
    return new Extractor(new World(u, corpus), Set.of("a")).row(unit,
        Initial.at(u.issue("a"), u.events("a"), null, unit.start()), null,
        new Extractor.At(unit.start(), null, Set.of("r")));
  }

  @Test
  @DisplayName("the revised team columns: median and spread over the members who committed in the "
      + "90 days, a member without McCC left out of the complexity")
  void revisedTeam() {
    final long t0 = T0 + 200 * D;
    final Fixture f = new Fixture();
    f.measured("c1", "m1", t0 - 150 * D, 10, 4.0);    // m1's history counts from before the window
    f.measured("c2", "m1", t0 - 20 * D, 30, 2.0);
    f.measured("c3", "m2", t0 - 10 * D, 100, 12.0);
    f.measured("c4", "m3", t0 - 5 * D, 10, null);      // Java, but its McCC was never measured
    f.measured("c5", "old", t0 - 120 * D, 500, 50.0);  // not in the team: nothing in the window
    final Map<String, Double> row = teamRow(f, t0);

    assertEquals(3.0, row.get("A03_team_size"));
    // lines 40, 100, 10: median 40; mean 50, squared deviations 100 + 2500 + 1600 over n = 3
    assertEquals(40.0, row.get(Extractor.TEAM_LOC_MEDIAN), 1e-9);
    assertEquals(Math.sqrt(1400), row.get(Extractor.TEAM_LOC_SD), 1e-9);
    // McCC 6 and 12 over the two measured members
    assertEquals(9.0, row.get(Extractor.TEAM_MCCC_MEDIAN), 1e-9);
    assertEquals(3.0, row.get(Extractor.TEAM_MCCC_SD), 1e-9);
    assertEquals(0.75, row.get("A06c_team_mccc_coverage"), 1e-9,
        "three of the four Java commits measured: metadata, as before");
  }

  @Test
  @DisplayName("a one-member team has zero spread where its measure is observed, and missing "
      + "complexity where it is not")
  void oneMemberTeam() {
    final long t0 = T0 + 200 * D;
    final Fixture measured = new Fixture();
    measured.measured("c1", "m1", t0 - 10 * D, 70, 5.0);
    final Map<String, Double> one = teamRow(measured, t0);
    assertEquals(70.0, one.get(Extractor.TEAM_LOC_MEDIAN), 1e-9);
    assertEquals(0.0, one.get(Extractor.TEAM_LOC_SD), 1e-9);
    assertEquals(5.0, one.get(Extractor.TEAM_MCCC_MEDIAN), 1e-9);
    assertEquals(0.0, one.get(Extractor.TEAM_MCCC_SD), 1e-9);

    final Fixture unmeasured = new Fixture();
    unmeasured.measured("c1", "m1", t0 - 10 * D, 70, null);
    final Map<String, Double> none = teamRow(unmeasured, t0);
    assertEquals(0.0, none.get(Extractor.TEAM_LOC_SD), 1e-9, "the lines are observed");
    assertNull(none.get(Extractor.TEAM_MCCC_MEDIAN), "missing, not zero experience");
    assertNull(none.get(Extractor.TEAM_MCCC_SD), "missing, not a false zero spread");
  }

  @Test
  @DisplayName("a member with no Java at all has zero complexity, as zero Java lines, not a "
      + "missing one")
  void memberWithoutJava() {
    final long t0 = T0 + 200 * D;
    final Fixture f = new Fixture();
    f.measured("c1", "m1", t0 - 10 * D, 70, 5.0);
    f.commits.add(new Corpus.Commit("c2", "p", "hc2", "m2", t0 - 5 * D, t0 - 5 * D, 1,
        List.of()));
    f.contributions.put("c2", new Corpus.Contribution(false, 0, 0, 0));
    final Map<String, Double> row = teamRow(f, t0);

    assertEquals(35.0, row.get(Extractor.TEAM_LOC_MEDIAN), 1e-9, "70 and 0 Java lines");
    assertEquals(2.5, row.get(Extractor.TEAM_MCCC_MEDIAN), 1e-9, "5 and 0: nothing to measure");
    assertEquals(2.5, row.get(Extractor.TEAM_MCCC_SD), 1e-9);
  }

  @Test
  @DisplayName("a team with no member in the 90 days has missing summaries, not zeros")
  void noTeam() {
    final long t0 = T0 + 200 * D;
    final Fixture f = new Fixture();
    f.measured("c1", "m1", t0 - 120 * D, 70, 5.0);
    final Map<String, Double> row = teamRow(f, t0);

    assertEquals(0.0, row.get("A03_team_size"));
    for (final String column : Extractor.TEAM_REVISED) {
      assertTrue(row.containsKey(column), column + " is written, as missing");
      assertNull(row.get(column), column);
    }
  }
}
