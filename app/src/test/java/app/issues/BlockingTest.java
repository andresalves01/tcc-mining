package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retrospective blocking-link wait (ticket 19, items 16, 26 and 37): active business days from
 * the start to the last first resolution among the issues this one waits on, on corpora built by
 * hand.
 */
class BlockingTest {

  private static final long D = World.DAY;
  /** A Monday, 00:00 UTC: 1970-01-05 plus whole weeks. */
  private static final long MONDAY = (4 + 7 * 2_000) * D;
  private static final long CREATED = MONDAY - 7 * D;
  private static final long T0 = MONDAY + 1_000;
  private static final long T1 = MONDAY + 14 * D + 1_000;
  private static final int HOUR = 0;

  private static Corpus.Link blockedBy(final String target) {
    return new Corpus.Link(target, "Blocker", "is blocked by");
  }

  /** A small corpus under construction; projects p (key P) and q (key Q). */
  private static final class Fixture {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    int ext = 1;

    void started(final String id, final String key, final List<Corpus.Link> links) {
      issues.add(new Corpus.Issue(id, key, "p", CREATED, "r", "r", null, "Bug", "Major", null,
          List.of(), links.stream().map(Corpus.Link::target).toList(), links, List.of()));
      event(id, T0, "status", "Open", "In Progress");
      event(id, T1, "status", "In Progress", "Resolved");
      event(id, T1, "resolution", null, "Fixed");
    }

    /** Another issue of {@code project}, resolved (any reason) at {@code res}, or never. */
    void other(final String id, final String key, final String project, final Long res) {
      issues.add(new Corpus.Issue(id, key, project, CREATED, "r", "r", null, "Bug", "Major", null,
          List.of(), List.of()));
      if (res != null) {
        event(id, res, "status", "Open", "Closed");
        event(id, res, "resolution", null, "Won't Fix");
      }
    }

    void event(final String issue, final long at, final String field, final String from,
        final String to) {
      events.add(new Corpus.Event(issue, at, field, "x", from, to, (ext++) + "%%0"));
    }

    void link(final String issue, final long at, final Corpus.Link removed,
        final Corpus.Link added) {
      events.add(new Corpus.Event(issue, at, "issue_links", "x",
          removed == null ? null : removed.target(), added == null ? null : added.target(),
          (ext++) + "%%0", removed, added));
    }

    Double wait(final String id) {
      final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
          Map.of("p", Set.of("P"), "q", Set.of("Q")), Map.of(), Map.of(), Map.of(), Map.of(),
          Map.of(), Map.of());
      final Universe universe = Universe.of(corpus);
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      assertNotNull(u, id + " is in the In-Progress population");
      return Blocking.of(universe).wait(u, HOUR);
    }
  }

  @Test
  @DisplayName("only the waiting effects count: is blocked by, depends upon, requires; blocks, "
      + "relates to, a type without direction and a link without an effect do not")
  void waitingEffects() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of(new Corpus.Link("b", "Blocker", "blocks"),
        new Corpus.Link("c", "Reference", "relates to"),
        new Corpus.Link("d", "Blocked", "Blocked"), new Corpus.Link("e", "Blocker", null)));
    f.other("b", "P-2", "p", T0 + 5 * D);
    f.other("c", "P-3", "p", T0 + 5 * D);
    f.other("d", "P-4", "p", T0 + 5 * D);
    f.other("e", "P-5", "p", T0 + 5 * D);
    assertNull(f.wait("a"));

    for (final String effect : List.of("is blocked by", "depends upon", "requires")) {
      final Fixture g = new Fixture();
      g.started("a", "P-1", List.of(new Corpus.Link("b", "any", effect)));
      g.other("b", "P-2", "p", T0 + 3 * D);
      assertEquals(3.0, g.wait("a"), effect);
    }
  }

  @Test
  @DisplayName("the latest first resolution among the blockers, from the start, in business days "
      + "at the issue's boundary; a weekend not counted")
  void latestResolution() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of(blockedBy("b"), blockedBy("c")));
    // first resolution of b at Tuesday; reopened and resolved again later: the first counts
    f.other("b", "P-2", "p", MONDAY + D + 5);
    f.event("b", MONDAY + 2 * D, "status", "Closed", "Reopened");
    f.event("b", MONDAY + 13 * D, "status", "Reopened", "Closed");
    // c resolved on the next Monday: five business days
    f.other("c", "P-3", "p", MONDAY + 7 * D + 5);
    assertEquals(5.0, f.wait("a"));
  }

  @Test
  @DisplayName("the time the issue spent back in Open or Reopened is left out, as from the target, "
      + "so the wait never exceeds the active business days")
  void leavesOutTheTimeBackInOpen() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of(blockedBy("b")));
    // back in Open from Monday to Wednesday of the first week: two business days out
    f.event("a", MONDAY + 2_000, "status", "In Progress", "Open");
    f.event("a", MONDAY + 2 * D + 2_000, "status", "Open", "In Progress");
    f.other("b", "P-2", "p", MONDAY + 7 * D + 5);
    assertEquals(3.0, f.wait("a"));

    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of(blockedBy("b")));
    g.event("a", MONDAY + 2_000, "status", "In Progress", "Open");
    g.event("a", MONDAY + 2 * D + 2_000, "status", "Open", "In Progress");
    g.other("b", "P-2", "p", null);
    assertEquals(8.0, g.wait("a"), "clipped at the resolution: the target's 8, never its 10");
  }

  @Test
  @DisplayName("0 when every blocker was resolved before the start; a blocker never resolved or "
      + "resolved after the issue is clipped at the issue's first resolution")
  void zeroAndClipping() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of(blockedBy("b")));
    f.other("b", "P-2", "p", T0 - D);
    assertEquals(0.0, f.wait("a"));

    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of(blockedBy("b"), blockedBy("c")));
    g.other("b", "P-2", "p", T0 - D);
    g.other("c", "P-3", "p", null);
    assertEquals(10.0, g.wait("a"));

    final Fixture h = new Fixture();
    h.started("a", "P-1", List.of(blockedBy("c")));
    h.other("c", "P-3", "p", T1 + 30 * D);
    assertEquals(10.0, h.wait("a"));
  }

  @Test
  @DisplayName("links present at any time before the first resolution: one removed after creation "
      + "counts, one added only at or after the resolution does not")
  void linksBeforeTheResolution() {
    final Fixture f = new Fixture();
    // final record: only the link added after the resolution; b was linked at creation, removed
    f.started("a", "P-1", List.of(blockedBy("late")));
    f.link("a", T0 + D, blockedBy("b"), null);
    f.link("a", T1, null, blockedBy("late"));
    f.other("b", "P-2", "p", MONDAY + 2 * D + 5);
    f.other("late", "P-5", "p", null);
    assertEquals(2.0, f.wait("a"));

    // a link added during the work
    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of(blockedBy("b")));
    g.link("a", T0 + D, null, blockedBy("b"));
    g.other("b", "P-2", "p", MONDAY + 3 * D + 5);
    assertEquals(3.0, g.wait("a"));
  }

  @Test
  @DisplayName("a blocker in another project counts; a copy is read through the home issue of its "
      + "key; a copy the corpus holds no home issue for is left out, its resolution unknown")
  void otherProjectsAndCopies() {
    final Fixture f = new Fixture();
    f.started("a", "P-1", List.of(blockedBy("copy")));
    // the copy in p's tracker has no changelog; Q-7's home issue in q does
    f.other("copy", "Q-7", "p", null);
    f.other("home", "Q-7", "q", MONDAY + 4 * D + 5);
    assertEquals(4.0, f.wait("a"));

    final Fixture g = new Fixture();
    g.started("a", "P-1", List.of(blockedBy("orphan")));
    g.other("orphan", "Z-1", "p", null);
    assertNull(g.wait("a"));
  }
}
