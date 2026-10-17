package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The start model's three attributes of ticket 19 -- the fix version set before the start, the
 * days left in the release cycle and the pace of the project's other Blocker issues -- on corpora
 * built by hand.
 */
class PressureTest {

  private static final long D = World.DAY;
  private static final long CREATED = 1_000 * D;
  private static final long T0 = CREATED + 20 * D;
  private static final long T1 = T0 + 10 * D;

  /** A small corpus under construction, every issue in project p. */
  private static final class Fixture {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Tag> tags = new ArrayList<>();
    /** Per issue of the matrix, its outer and inner verdicts. */
    final Map<String, String[]> parts = new HashMap<>();
    int ext = 1;

    /** An issue created at CREATED, In Progress at T0, resolved Fixed at T1, inner fitting. */
    void started(final String id, final String key, final List<String> fixVersions) {
      issues.add(new Corpus.Issue(id, key, "p", CREATED, "r", "r", null, "Bug", "Major", null,
          List.of(), List.of(), List.of(), fixVersions));
      event(id, T0, "status", "Open", "In Progress");
      event(id, T1, "status", "In Progress", "Resolved");
      event(id, T1, "resolution", null, "Fixed");
      parts.put(id, new String[] {"TRAIN", "TRAIN"});
    }

    /**
     * An issue of the matrix created at {@code created} with {@code priority}, In Progress a day
     * later, resolved Fixed at {@code res}, in the given split.
     */
    void drawn(final String id, final String key, final long created, final long res,
        final String priority, final String outer, final String inner) {
      issues.add(new Corpus.Issue(id, key, "p", created, "r", "r", null, "Bug", priority, null,
          List.of(), List.of()));
      event(id, created + D, "status", "Open", "In Progress");
      event(id, res, "status", "In Progress", "Resolved");
      event(id, res, "resolution", null, "Fixed");
      parts.put(id, new String[] {outer, inner});
    }

    /** An issue created at {@code created} with {@code priority}, resolved at {@code res}. */
    void other(final String id, final String key, final long created, final Long res,
        final String priority) {
      issues.add(new Corpus.Issue(id, key, "p", created, "r", "r", null, "Bug", priority, null,
          List.of(), List.of()));
      if (res != null) {
        event(id, res, "status", "Open", "Resolved");
        event(id, res, "resolution", null, "Won't Fix");
      }
    }

    void event(final String issue, final long at, final String field, final String from,
        final String to) {
      events.add(new Corpus.Event(issue, at, field, "x", from, to, (ext++) + "%%0"));
    }

    /** The fix versions at creation by name, as the rule before the count read them. */
    Set<String> creation(final String id) {
      final Corpus.Issue issue = issues.stream().filter(i -> i.id().equals(id)).findFirst()
          .orElseThrow();
      return FixVersions.atCreation(issue, Initial.ordered(events.stream()
          .filter(e -> e.issue().equals(id)).toList()));
    }

    Map<String, Double> row(final String id) {
      final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
          Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
          tags);
      final Universe universe = Universe.of(corpus);
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      assertNotNull(u, id + " is in the In-Progress population");
      return Pressure.of(universe, Releases.of(corpus), parts).row(u);
    }
  }

  @Test
  @DisplayName("fix version flag: 1 with a fix version at creation, or one added strictly before "
      + "the start even if removed later; 0 when added only at or after the start")
  void fixVersionBeforeTheStart() {
    final Fixture f = new Fixture();
    // at creation: the final record is empty, but the changelog removed 1.0 after the start
    f.started("a", "P-1", List.of());
    f.event("a", T0 + D, "fix_versions", "1.0", null);
    // added before the start, removed before the start
    f.started("b", "P-2", List.of());
    f.event("b", T0 - 2 * D, "fix_versions", null, "1.0");
    f.event("b", T0 - D, "fix_versions", "1.0", null);
    // added exactly at the start, and after it
    f.started("c", "P-3", List.of("1.0", "2.0"));
    f.event("c", T0, "fix_versions", null, "1.0");
    f.event("c", T0 + D, "fix_versions", null, "2.0");
    // never
    f.started("d", "P-4", List.of());
    assertEquals(1.0, f.row("a").get(Pressure.FIX_VERSION));
    assertEquals(1.0, f.row("b").get(Pressure.FIX_VERSION));
    assertEquals(0.0, f.row("c").get(Pressure.FIX_VERSION));
    assertEquals(0.0, f.row("d").get(Pressure.FIX_VERSION));
  }

  @Test
  @DisplayName("fix version flag, name-independent: a version added after the start and renamed "
      + "later in Jira, without a changelog entry, is not taken as present at creation")
  void renamedVersionIsNotAtCreation() {
    final Fixture f = new Fixture();
    // added after the start as 2.0, renamed to 2.0.0 in Jira: the record holds the new name only
    f.started("a", "P-1", List.of("2.0.0"));
    f.event("a", T0 + D, "fix_versions", null, "2.0");
    assertEquals(0.0, f.row("a").get(Pressure.FIX_VERSION));
    assertEquals(Set.of("2.0.0"), f.creation("a"), "by name the renamed version looks there");

    // added after the start as 2.0, renamed 2.0.0, then removed under its new name: the record
    // is empty, by name 2.0.0 looks there at creation, by count nothing was
    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of());
    g.event("a", T0 + D, "fix_versions", null, "2.0");
    g.event("a", T0 + 2 * D, "fix_versions", "2.0.0", null);
    assertEquals(0.0, g.row("a").get(Pressure.FIX_VERSION));
    assertEquals(Set.of("2.0.0"), g.creation("a"), "by name the old rule would have said 1");

    // a version there at creation and removed after the start still counts
    final Fixture h = new Fixture();
    h.started("a", "P-1", List.of());
    h.event("a", T0 + D, "fix_versions", "1.0", null);
    assertEquals(1.0, h.row("a").get(Pressure.FIX_VERSION));
  }

  @Test
  @DisplayName("the fix versions' fidelity: an issue whose names the changelog does not explain is "
      + "counted as inconsistent, as a rename leaves it")
  void fixVersionFidelity() {
    final Fixture f = new Fixture();
    f.started("plain", "P-1", List.of("1.0"));
    f.event("plain", T0 + D, "fix_versions", null, "1.0");
    f.started("renamed", "P-2", List.of("2.0.0"));
    f.event("renamed", T0 + D, "fix_versions", null, "2.0");
    f.started("none", "P-3", List.of("3.0"));
    final Corpus corpus = new Corpus(f.issues, f.events, List.of(), List.of(), List.of(),
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    final Initial.Fidelity fidelity =
        FixVersions.fidelity(Universe.of(corpus), List.of("plain", "renamed", "none"));
    assertEquals(new Initial.Fidelity("fix_versions", 3, 2, 1, 1), fidelity);
  }

  @Test
  @DisplayName("the release cycle reads only the tags dated strictly before the start")
  void cycleBeforeTheStart() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of());
    f.tags.add(new Corpus.Tag("p", "1.0", T0 - 30 * D, null));
    f.tags.add(new Corpus.Tag("p", "1.1", T0 - 20 * D, null));
    f.tags.add(new Corpus.Tag("p", "1.2", T0 - 10 * D, null));
    // at the start, and after: never read
    f.tags.add(new Corpus.Tag("p", "1.3", T0, null));
    f.tags.add(new Corpus.Tag("p", "1.4", T0 + D, null));
    assertEquals(0.0, f.row("a").get(Pressure.RELEASE_CYCLE), 1e-9);

    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of());
    g.tags.add(new Corpus.Tag("p", "1.0", T0 - 30 * D, null));
    g.tags.add(new Corpus.Tag("p", "1.1", T0 - 20 * D, null));
    g.tags.add(new Corpus.Tag("p", "1.2", T0, null));
    final Map<String, Double> v = g.row("a");
    assertTrue(v.containsKey(Pressure.RELEASE_CYCLE));
    assertNull(v.get(Pressure.RELEASE_CYCLE));
  }

  private static final String INNER = Pressure.blockerPace(Augmentation.Variant.INNER);
  private static final String OUTER = Pressure.blockerPace(Augmentation.Variant.OUTER);

  /**
   * The Blocker pace's corpus: a, the issue itself, inner fitting and a Blocker of 30 days; f1, f2
   * and was, Blocker references of the inner fitting side, f2 resolved long after a's start; late
   * and crit, never Blocker at their resolution; outside, a Blocker never In Progress; zero, a
   * Blocker In Progress issue of 0 active business days, kept out of the matrix; v, an inner
   * validation Blocker of {@code validation} days; t, an outer test Blocker of {@code test} days.
   */
  private static Fixture blockers(final long validation, final long test) {
    final Fixture f = new Fixture();
    f.drawn("a", "P-1", CREATED, T1, "Blocker", "TRAIN", "TRAIN");
    f.drawn("f1", "P-10", 0, 2 * D, "Blocker", "TRAIN", "TRAIN");
    f.drawn("f2", "P-11", 0, T0 + 100 * D, "Blocker", "TRAIN", "TRAIN");
    // Blocker at its resolution, Major now
    f.drawn("was", "P-13", 0, 4 * D, "Major", "TRAIN", "TRAIN");
    f.event("was", 2 * D, "priority", "Major", "Blocker");
    f.event("was", 50 * D, "priority", "Blocker", "Major");
    // Blocker only after its resolution, and a Critical one: never references
    f.drawn("late", "P-12", 0, 6 * D, "Major", "TRAIN", "TRAIN");
    f.event("late", 200 * D, "priority", "Major", "Blocker");
    f.drawn("crit", "P-17", 0, 3 * D, "Critical", "TRAIN", "TRAIN");
    // outside the matrix: never In Progress, Won't Fix, 5 days; and In Progress but left out of
    // the matrix, as an issue of 0 active business days is, 7 days
    f.other("outside", "P-18", 0, 5 * D, "Blocker");
    f.drawn("zero", "P-19", 0, 7 * D, "Blocker", "TRAIN", "TRAIN");
    f.parts.remove("zero");
    // the other sides' labels
    f.drawn("v", "P-20", 0, validation * D, "Blocker", "TRAIN", "TEST");
    f.drawn("t", "P-21", 0, test * D, "Blocker", "TEST", "");
    return f;
  }

  @Test
  @DisplayName("Blocker pace (ticket 21, item 51): the median creation-to-resolution days of "
      + "the project's issues Blocker at their first resolution, whatever their dates, read from "
      + "the variant's training side and from outside the matrix, never a matrix row of the "
      + "other side, never the issue itself; missing with fewer than three")
  void blockerPace() {
    final Fixture few = new Fixture();
    few.drawn("a", "P-1", CREATED, T1, "Blocker", "TRAIN", "TRAIN");
    few.drawn("f1", "P-10", 0, 2 * D, "Blocker", "TRAIN", "TRAIN");
    few.other("outside", "P-18", 0, 5 * D, "Blocker");
    assertNull(few.row("a").get(INNER), "two references, a itself left out");

    final Fixture f = blockers(1_000, 2_000);
    // inner: f1 2, was 4, outside 5, zero 7, f2 1,120 -> 5; never v, the validation, nor t
    assertEquals(5.0, f.row("a").get(INNER), 1e-9);
    // outer: the same and v 1,000 -> 6; never t
    assertEquals(6.0, f.row("a").get(OUTER), 1e-9);
    // the inner validation reads the inner fitting side and outside, a among it: 2, 4, 5, 7, 30,
    // 1,120 -> 6
    assertEquals(6.0, f.row("v").get(INNER), 1e-9);
    assertEquals(6.0, f.row("v").get(OUTER), 1e-9, "itself left out of the outer training");
    // the outer test reads the outer training and outside: 2, 4, 5, 7, 30, 1,000, 1,120 -> 7; it
    // is no row of the inner variant
    assertEquals(7.0, f.row("t").get(OUTER), 1e-9);
    assertTrue(f.row("t").containsKey(INNER));
    assertNull(f.row("t").get(INNER));
  }

  @Test
  @DisplayName("Blocker pace: a validation duration moves no inner column, a test duration no "
      + "column at all; an outside Blocker counts in both variants")
  void blockerPaceSides() {
    final Fixture base = blockers(1_000, 2_000);
    final Fixture validation = blockers(3, 2_000);
    final Fixture test = blockers(1_000, 3);
    boolean outerMoved = false;
    for (final String id : List.of("a", "f1", "f2", "was", "late", "crit", "v", "t")) {
      assertEquals(base.row(id).get(INNER), validation.row(id).get(INNER), id);
      outerMoved |= !java.util.Objects.equals(base.row(id).get(OUTER),
          validation.row(id).get(OUTER));
      assertEquals(base.row(id), test.row(id), id);
    }
    assertTrue(outerMoved, "the validation rows are outer training: the outer pace may move");
    // without the two outside the matrix, a's inner references are f1, was and f2 only
    final Fixture inside = blockers(1_000, 2_000);
    inside.issues.removeIf(i -> i.id().equals("outside") || i.id().equals("zero"));
    assertEquals(4.0, inside.row("a").get(INNER), 1e-9, "2, 4 and 1,120");
    assertEquals(5.0, base.row("a").get(INNER), 1e-9, "with 5 and 7 from outside the matrix");
  }

  @Test
  @DisplayName("the columns, the Blocker pace once per variant, every one keyed; missing ones as "
      + "null")
  void columns() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of());
    final Map<String, Double> v = f.row("a");
    assertEquals(Set.copyOf(Pressure.COLUMNS), v.keySet());
    assertFalse(v.get(Pressure.FIX_VERSION) == null);
  }
}
