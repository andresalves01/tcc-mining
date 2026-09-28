package app.paper6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Undoing the changelog: what the value was at creation, for each shape of field. */
class ReplayTest {

  private static final long T0 = 1_000_000L;

  private static Tables.Snapshot snapshot(final String title, final String desc,
      final String priority, final String assignee, final List<String> labels,
      final int components, final int links) {
    return new Tables.Snapshot(title, desc, priority, "Bug", assignee, labels, components, 0, 0,
        links);
  }

  @Test
  @DisplayName("a single-valued field is the old value of the earliest change after creation")
  void scalarTakesTheEarliestOldValue() {
    final List<Tables.Change> changes = List.of(
        new Tables.Change(T0 + 10, "priority", "Major", "Critical"),
        new Tables.Change(T0 + 20, "priority", "Critical", "Blocker"));
    final Replay.AtOpen open = Replay.of(
        snapshot("t", "d", "Blocker", null, List.of(), 0, 0), changes, T0);

    assertEquals("Major", open.priority(), "two changes after creation, so the first old value");
  }

  @Test
  @DisplayName("with nothing recorded, the stored value is the value at creation")
  void noChangeMeansTheStoredValue() {
    final Replay.AtOpen open = Replay.of(
        snapshot("titulo", "corpo", "Minor", "abc", List.of("x"), 2, 1), List.of(), T0);

    assertEquals("titulo", open.title());
    assertEquals("corpo", open.description());
    assertTrue(open.hasAssignee());
    assertEquals(1, open.labels());
    assertEquals(2, open.components());
    assertEquals(1, open.linkCount());
  }

  @Test
  @DisplayName("an assignee set after creation means nobody was assigned at creation")
  void assigneeAddedLater() {
    final Replay.AtOpen open = Replay.of(snapshot("t", "d", "Major", "abc", List.of(), 0, 0),
        List.of(new Tables.Change(T0 + 5, "assignee_id", null, "abc")), T0);

    assertFalse(open.hasAssignee());
  }

  @Test
  @DisplayName("a description written after creation was absent at creation, not empty")
  void descriptionAddedLater() {
    final Replay.AtOpen open = Replay.of(snapshot("t", "escrita depois", "Major", null,
        List.of(), 0, 0), List.of(new Tables.Change(T0 + 5, "desc", null, "escrita depois")), T0);

    assertNull(open.description());
  }

  @Test
  @DisplayName("a list takes back what was added after creation and restores what was removed")
  void listCountsAreUndone() {
    final List<Tables.Change> changes = List.of(
        new Tables.Change(T0 + 1, "components", null, "api"),
        new Tables.Change(T0 + 2, "components", null, "core"),
        new Tables.Change(T0 + 3, "components", "build", null),
        new Tables.Change(T0 + 4, "issue_links", null, "5bf4"));
    final Replay.AtOpen open = Replay.of(
        snapshot("t", "d", "Major", null, List.of(), 2, 1), changes, T0);

    assertEquals(1, open.components(), "two added and one removed: 2 - 2 + 1");
    assertEquals(0, open.linkCount());
  }

  @Test
  @DisplayName("a count the log would take below zero is held at zero and counted by the audit")
  void aLostAdditionIsCountedNotHidden() {
    final Tables.Snapshot snap = snapshot("t", "d", "Major", null, List.of(), 0, 0);
    final List<Tables.Change> changes =
        List.of(new Tables.Change(T0 + 1, "components", null, "api"));
    assertEquals(0, Replay.of(snap, changes, T0).components());

    final Replay.Audit audit = new Replay.Audit();
    audit.add(snap, changes, T0);
    final Replay.Fidelity components = audit.result().stream()
        .filter(f -> f.field().equals("components")).findFirst().orElseThrow();
    assertEquals(1, components.negative());
  }

  @Test
  @DisplayName("an absent link field stays absent: no count is invented for it")
  void absentLinksStayAbsent() {
    assertEquals(-1, Replay.of(snapshot("t", "d", "Major", null, List.of(), 0, -1), List.of(),
        T0).linkCount());
  }

  @Test
  @DisplayName("labels are a set: the audit does not read a reordering as a broken chain")
  void labelOrderIsNotADifference() {
    final Tables.Snapshot snap = snapshot("t", "d", "Major", null, List.of("b", "a"), 0, 0);
    final Replay.Audit audit = new Replay.Audit();
    audit.add(snap, List.of(new Tables.Change(T0 + 1, "labels", "", "a b")), T0);
    final Replay.Fidelity labels = audit.result().stream()
        .filter(f -> f.field().equals("labels")).findFirst().orElseThrow();

    assertEquals(1, labels.withChanges());
    assertEquals(1, labels.chained());
    assertEquals(1, labels.changedAfterOpen(), "at creation there were no labels");
  }

  @Test
  @DisplayName("a change at the very instant of creation is not undone: the window is after t0")
  void changeAtCreationStays() {
    final Replay.AtOpen open = Replay.of(snapshot("t", "d", "Minor", null, List.of(), 0, 0),
        List.of(new Tables.Change(T0, "priority", "Major", "Minor")), T0);

    assertEquals("Minor", open.priority());
  }
}
