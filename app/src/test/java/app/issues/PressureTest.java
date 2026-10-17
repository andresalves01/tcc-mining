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

  @Test
  @DisplayName("Blocker pace (ticket 21): the median creation-to-resolution days of the project's "
      + "issues of the training side Blocker at their first resolution, whatever their dates, the "
      + "issue itself left out; missing with fewer than three")
  void blockerPace() {
    final Fixture f = new Fixture();
    // the issue itself, inner fitting and a Blocker of 30 days: never its own reference
    f.drawn("a", "P-1", CREATED, T1, "Blocker", "TRAIN", "TRAIN");
    f.drawn("f1", "P-10", 0, 2 * D, "Blocker", "TRAIN", "TRAIN");
    // resolved long after a's start: the history is the whole training side, whatever the dates
    f.drawn("f2", "P-11", 0, T0 + 100 * D, "Blocker", "TRAIN", "TRAIN");
    assertNull(f.row("a").get(INNER), "two references, a itself left out");
    // Blocker at its resolution, Major now
    f.drawn("was", "P-13", 0, 4 * D, "Major", "TRAIN", "TRAIN");
    f.event("was", 2 * D, "priority", "Major", "Blocker");
    f.event("was", 50 * D, "priority", "Blocker", "Major");
    // Blocker only after its resolution, a Critical one, and a Blocker outside the matrix
    f.drawn("late", "P-12", 0, 6 * D, "Major", "TRAIN", "TRAIN");
    f.event("late", 200 * D, "priority", "Major", "Blocker");
    f.drawn("crit", "P-17", 0, 3 * D, "Critical", "TRAIN", "TRAIN");
    f.other("outside", "P-18", 0, 5 * D, "Blocker");
    // the other sides' labels
    f.drawn("v", "P-20", 0, 1_000 * D, "Blocker", "TRAIN", "TEST");
    f.drawn("t", "P-21", 0, 2_000 * D, "Blocker", "TEST", "");

    // inner: f1 2, was 4, f2 1,120 -> 4; never v, the validation, nor t, the test
    assertEquals(4.0, f.row("a").get(INNER), 1e-9);
    // outer: f1 2, was 4, v 1,000, f2 1,120 -> 502; never t
    assertEquals(502.0, f.row("a").get(OUTER), 1e-9);
    // the inner validation reads the inner fitting side, a among it: 2, 4, 30, 1,120 -> 17
    assertEquals(17.0, f.row("v").get(INNER), 1e-9);
    assertEquals(17.0, f.row("v").get(OUTER), 1e-9, "itself left out of the outer training");
    // the outer test reads the whole outer training: 2, 4, 30, 1,000, 1,120 -> 30; it is no row
    // of the inner variant
    assertEquals(30.0, f.row("t").get(OUTER), 1e-9);
    assertTrue(f.row("t").containsKey(INNER));
    assertNull(f.row("t").get(INNER));
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
