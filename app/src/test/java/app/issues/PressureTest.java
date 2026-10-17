package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
    int ext = 1;

    /** An issue created at CREATED, In Progress at T0, resolved Fixed at T1. */
    void started(final String id, final String key, final List<String> fixVersions) {
      issues.add(new Corpus.Issue(id, key, "p", CREATED, "r", "r", null, "Bug", "Major", null,
          List.of(), List.of(), List.of(), fixVersions));
      event(id, T0, "status", "Open", "In Progress");
      event(id, T1, "status", "In Progress", "Resolved");
      event(id, T1, "resolution", null, "Fixed");
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

    Map<String, Double> row(final String id) {
      final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
          Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
          tags);
      final Universe universe = Universe.of(corpus);
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      assertNotNull(u, id + " is in the In-Progress population");
      return Pressure.of(universe, Releases.of(corpus)).row(u);
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
    // the same rename when the version was there at creation and removed after the start
    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of());
    g.event("a", T0 + D, "fix_versions", "1.0-renamed", null);
    assertEquals(1.0, g.row("a").get(Pressure.FIX_VERSION));
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

  @Test
  @DisplayName("Blocker pace: the median creation-to-resolution days of the project's other issues "
      + "Blocker at their first resolution, of any reason, resolved strictly before the start; "
      + "missing with fewer than three")
  void blockerPace() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of());
    // the issue itself is a Blocker, but resolved after its own start: it can never be among the
    // references, so this does not test the exclusion by id, which is there only as a guard
    f.issues.set(0, new Corpus.Issue("a", "P-1", "p", CREATED, "r", "r", null, "Bug", "Blocker",
        null, List.of(), List.of()));
    f.other("b1", "P-10", 0, 2 * D, "Blocker");
    f.other("b2", "P-11", 0, 4 * D, "Blocker");
    assertNull(f.row("a").get(Pressure.BLOCKER_PACE));
    // Blocker only after its resolution: not a Blocker at it
    f.other("late", "P-12", 0, 100 * D, "Blocker");
    f.event("late", 200 * D, "priority", "Major", "Blocker");
    // Blocker at its resolution, Major now
    f.other("was", "P-13", 0, 9 * D, "Major");
    f.event("was", 1 * D, "priority", "Major", "Blocker");
    f.event("was", 50 * D, "priority", "Blocker", "Major");
    // resolved at the start, after it, never, and a Critical one: not counted
    f.other("at", "P-14", 0, T0, "Blocker");
    f.other("after", "P-15", 0, T0 + D, "Blocker");
    f.other("never", "P-16", 0, null, "Blocker");
    f.other("crit", "P-17", 0, 3 * D, "Critical");
    // b1 2, b2 4, was 9 -> median 4
    assertEquals(4.0, f.row("a").get(Pressure.BLOCKER_PACE), 1e-9);
  }

  @Test
  @DisplayName("the three columns, every one keyed; missing ones as null")
  void columns() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of());
    final Map<String, Double> v = f.row("a");
    assertEquals(Set.copyOf(Pressure.COLUMNS), v.keySet());
    assertFalse(v.get(Pressure.FIX_VERSION) == null);
  }
}
