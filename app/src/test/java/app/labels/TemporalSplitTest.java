package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The chronological split: train on the past, test on the future, once. */
class TemporalSplitTest {

  private static final Instant EPOCH = Instant.parse("2010-01-01T00:00:00Z");

  private static IssueLabel labelIn(final String developer, final String project,
      final int createdDay, final int resolvedDay) {
    return new IssueLabel(
        String.format("%024x",
            (createdDay * 1_000L + resolvedDay + developer.hashCode() + project.hashCode())
                & 0xffffffL),
        project, EPOCH.plus(createdDay, ChronoUnit.DAYS), EPOCH.plus(resolvedDay, ChronoUnit.DAYS),
        developer, EPOCH.plus(createdDay, ChronoUnit.DAYS), 1, 10, 5,
        List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  private static IssueLabel label(final String developer, final int createdDay,
      final int resolvedDay) {
    return new IssueLabel(
        String.format("%024x", createdDay * 1_000 + resolvedDay + developer.hashCode() & 0xffffff),
        "p", EPOCH.plus(createdDay, ChronoUnit.DAYS), EPOCH.plus(resolvedDay, ChronoUnit.DAYS),
        developer, EPOCH.plus(createdDay, ChronoUnit.DAYS), 1, 10, 5,
        List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  /** Ten issues per developer, created on consecutive days and resolved the day after. */
  private static List<IssueLabel> corpus(final String... developers) {
    final List<IssueLabel> labels = new ArrayList<>();
    int day = 1;
    for (final String developer : developers) {
      for (int i = 0; i < 10; i++, day++) {
        labels.add(label(developer, day, day + 1));
      }
    }
    return labels;
  }

  @Test
  @DisplayName("an issue resolved after the cut is in neither side, and is counted")
  void crossingIssuesAreExcludedAndCounted() {
    // The rule the design turns on: at the cut this issue had no known outcome, so training on it
    // uses the future; and it was not created in the test period, so it is not a future task.
    final List<IssueLabel> labels = new ArrayList<>(corpus("dev"));
    labels.add(label("dev", 2, 900));

    final IssueLabel crossing = labels.get(labels.size() - 1);
    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 1);

    assertEquals(SplitPart.CROSSES_CUT, split.parts().get(crossing.issueId()));
    assertTrue(split.outer().past().stream().noneMatch(l -> l.ttcDays() > 100));
    assertTrue(split.outer().future().stream().noneMatch(l -> l.ttcDays() > 100));
  }

  @Test
  @DisplayName("crossing the cut is decided before the author's history is consulted")
  void crossingBeatsIneligibility() {
    // A property of the issue, not of the person: unknown at the cut is unusable whoever wrote it.
    final List<IssueLabel> labels = new ArrayList<>(corpus("veteran"));
    labels.add(label("newcomer", 2, 900));

    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 5);

    assertEquals(SplitPart.CROSSES_CUT, split.parts().get(labels.get(labels.size() - 1).issueId()));
  }

  @Test
  @DisplayName("a developer without enough resolutions before the cut is excluded from both sides")
  void insufficientHistoryIsItsOwnExclusion() {
    final List<IssueLabel> labels = new ArrayList<>(corpus("veteran"));
    labels.add(label("newcomer", 8, 9));
    labels.add(label("newcomer", 40, 41));

    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 5);

    assertEquals(2, (split.outer().insufficientPast().size() + split.outer().insufficientFuture().size()));
    assertEquals(1, split.outer().eligiblePairs());
    assertTrue(split.outer().past().stream().allMatch(l -> "veteran".equals(l.canonicalAuthorId())));
    assertTrue(split.outer().future().stream().allMatch(l -> "veteran".equals(l.canonicalAuthorId())));
  }

  @Test
  @DisplayName("the cut is taken from creation instants, not from resolutions")
  void cutIsAnchoredOnCreation() {
    // Creation is the instant the prediction is anchored to. Cutting on resolution would let an
    // issue created after the cut land in training because it happened to close quickly.
    final TemporalSplit.Split split = TemporalSplit.of(corpus("dev"), 0.70, 1);

    // Ten creations on consecutive days: the 70th percentile is the seventh of them.
    assertEquals(EPOCH.plus(7, ChronoUnit.DAYS), split.outer().cut());
    assertTrue(split.outer().past().stream().allMatch(l -> l.createdAt().isBefore(split.outer().cut())));
    assertTrue(split.outer().future().stream().noneMatch(l -> l.createdAt().isBefore(split.outer().cut())));
  }

  @Test
  @DisplayName("the inner split stays inside the training side and never touches the test set")
  void innerSplitIsContainedInTraining() {
    final TemporalSplit.Split split = TemporalSplit.of(corpus("a", "b", "c"), 0.70, 1);

    assertTrue(split.inner().cut().isBefore(split.outer().cut()), "the inner cut is earlier than the outer");
    assertEquals(split.outer().past().size(), split.inner().past().size() + split.inner().future().size()
        + split.inner().crossing().size() + (split.inner().insufficientPast().size() + split.inner().insufficientFuture().size()));
    assertTrue(split.inner().past().stream().noneMatch(split.outer().future()::contains));
  }

  @Test
  @DisplayName("history is counted per developer and project, not pooled across the corpus")
  void eligibilityIsPerProject() {
    // The regression this test exists for. Four resolutions in one project and one in another are
    // not five in either: what qualifies the developer is familiarity with the codebase changing,
    // and pooling would credit project A with work done in B.
    // veteran earns ten resolutions in A; split-dev earns four in A and one in B, which is five
    // labels but not five in either project. The cut lands at day 15.
    final List<IssueLabel> labels = new ArrayList<>();
    for (int day = 1; day <= 10; day++) {
      labels.add(labelIn("veteran", "A", day, day + 1));
    }
    for (int day = 11; day <= 14; day++) {
      labels.add(labelIn("split-dev", "A", day, day + 1));
    }
    labels.add(labelIn("split-dev", "B", 15, 16));
    for (int day = 100; day <= 105; day++) {
      labels.add(labelIn("veteran", "A", day, day + 1));
    }
    labels.add(labelIn("split-dev", "A", 106, 107));

    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 5);

    assertEquals(1, split.outer().eligiblePairs(), "only (veteran, A) reaches five in one project");
    assertTrue(split.outer().past().stream().noneMatch(l -> "split-dev".equals(l.canonicalAuthorId())),
        "four in A plus one in B must not qualify as five in A");
    assertTrue(split.outer().future().stream().noneMatch(l -> "split-dev".equals(l.canonicalAuthorId())));
  }

  @Test
  @DisplayName("eligibility is recounted at the inner cut, and its exclusions are counted apart")
  void innerCutRecountsHistory() {
    // latecomer reaches five resolutions in time for the outer cut but not for the inner one, so
    // it belongs to the training side and still cannot take part in choosing regularisation.
    final List<IssueLabel> labels = new ArrayList<>();
    for (int day = 1; day <= 40; day++) {
      labels.add(labelIn("veteran", "A", day, day + 1));
    }
    for (int day = 25; day <= 30; day++) {
      labels.add(labelIn("latecomer", "A", day, day + 1));
    }
    for (int day = 200; day <= 210; day++) {
      labels.add(labelIn("veteran", "A", day, day + 1));
    }

    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 5);

    assertTrue(split.outer().past().stream().anyMatch(l -> "latecomer".equals(l.canonicalAuthorId())),
        "latecomer qualifies at the outer cut");
    assertTrue((split.inner().insufficientPast().size() + split.inner().insufficientFuture().size()) > 0,
        "a pair that only reached five after the inner cut must be excluded there");
    assertEquals(split.outer().past().size(), split.inner().past().size() + split.inner().future().size()
        + split.inner().crossing().size() + (split.inner().insufficientPast().size() + split.inner().insufficientFuture().size()));
  }

  @Test
  @DisplayName("resolution exactly at the cut counts as after it, not before")
  void theCutIsAClosedFuture() {
    // "Resolved before the cut" is strict. An issue closing at the very instant of the cut had no
    // known outcome an instant earlier, which is what the training side requires.
    final List<IssueLabel> labels = new ArrayList<>(corpus("dev"));
    final TemporalSplit.Split split = TemporalSplit.of(labels, 0.70, 1);
    final IssueLabel onTheBoundary = labels.stream()
        .filter(l -> l.resolvedAt().equals(split.outer().cut())).findFirst().orElseThrow();

    assertEquals(SplitPart.CROSSES_CUT, split.parts().get(onTheBoundary.issueId()));
  }

  @Test
  @DisplayName("every label lands in exactly one part")
  void partitionIsTotal() {
    final TemporalSplit.Split split = TemporalSplit.of(corpus("a", "b"), 0.70, 3);

    assertEquals(20, split.parts().size());
    assertEquals(20, split.outer().total());
  }

  @Test
  @DisplayName("splitting nothing is a programming error, not an empty split")
  void emptyInputIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> TemporalSplit.of(List.of(), 0.70, 5));
  }
}
