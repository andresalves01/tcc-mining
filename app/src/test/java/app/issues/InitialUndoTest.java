package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reversal of a field holding several elements ({@link Initial#undo}), which the links at
 * creation, the blockers and the fix versions read (ticket 19).
 */
class InitialUndoTest {

  private static Corpus.Event link(final long at, final String removed, final String added) {
    return new Corpus.Event("a", at, "issue_links", "x", removed, added, at + "%%0");
  }

  private static Initial.Undone<String> undo(final List<String> current,
      final List<Corpus.Event> changes) {
    return Initial.undo(current, changes, Corpus.Event::newValue, Corpus.Event::oldValue);
  }

  @Test
  @DisplayName("every addition undone was held: consistent, the elements at creation recovered as "
      + "a multiset, two links to one target kept apart")
  void consistent() {
    // at creation b twice and c; then c removed, d added
    final Initial.Undone<String> u =
        undo(List.of("b", "b", "d"), List.of(link(1, "c", null), link(2, null, "d")));
    assertTrue(u.consistent());
    assertEquals(Map.of("b", 2, "c", 1, "d", 0), u.initial());
    assertEquals(Map.of("b", 2, "d", 1), u.stored());
    assertEquals(Set.of("b", "c"), u.held());
  }

  @Test
  @DisplayName("an addition undone that the record does not hold is inconsistent, and is not "
      + "taken out below zero")
  void inconsistentUndo() {
    // d was added, but the record holds e instead: a change the changelog does not show
    final Initial.Undone<String> u = undo(List.of("e"), List.of(link(1, null, "d")));
    assertFalse(u.consistent());
    assertEquals(Map.of("e", 1), u.initial());
    assertEquals(Set.of("e"), u.held());
  }

  @Test
  @DisplayName("the links' fidelity reads the same flag: an unexplained link change counts as "
      + "changed but not consistent")
  void linkFidelity() {
    final List<Corpus.Issue> issues = List.of(
        new Corpus.Issue("a", "P-1", "p", 0L, "r", "r", null, "Bug", "Major", null, List.of(),
            List.of("e")),
        new Corpus.Issue("b", "P-2", "p", 0L, "r", "r", null, "Bug", "Major", null, List.of(),
            List.of("d")));
    final List<Corpus.Event> events = List.of(link(1, null, "d"),
        new Corpus.Event("b", 1L, "issue_links", "x", null, "d", "1%%0"));
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), List.of(),
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    final Initial.Fidelity f = Initial.fidelity(Universe.of(corpus), List.of("a", "b"),
        Map.of()).stream().filter(x -> x.field().equals("issue_links")).findFirst()
        .orElseThrow();
    assertEquals(new Initial.Fidelity("issue_links", 2, 2, 1, 1), f);
  }
}
