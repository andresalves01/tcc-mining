package app.paper6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.labels.IssueLabel;
import app.paper6.port.IssueUnitSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one exclusion, and nothing else. */
class PopulationTest {

  private static final long HOUR = 3_600_000L;

  private static IssueUnitSource.Label label(final int i, final long createdH,
      final long firstCommitH, final long resolvedH) {
    return new IssueUnitSource.Label(new IssueLabel("i" + i, "p", Instant.ofEpochMilli(createdH
        * HOUR), Instant.ofEpochMilli(resolvedH * HOUR), "dev", Instant.ofEpochMilli(firstCommitH
        * HOUR), 1, 1, 1, List.of()), "rep" + (i % 3), "P-" + i, "TRAIN", false);
  }

  @Test
  @DisplayName("only an issue resolved before its first linked commit is excluded")
  void onlyTheLifecycleViolationIsExcluded() {
    final List<IssueUnitSource.Label> labels = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      // a very short one, a very long one, and ordinary ones: all kept
      final long created = i * 10L;
      final long resolved = i == 0 ? created + 0 : i == 1 ? created + 20_000 : created + 5;
      labels.add(label(i, created, created, Math.max(resolved, created)));
    }
    labels.add(label(100, 5, 8, 7)); // resolved an hour before the first commit

    final Population.Report report = Population.of(labels, 0.70);

    assertEquals(1, report.resolvedBeforeCommit());
    final int placed = report.byPart().values().stream().mapToInt(Population.Group::rows).sum();
    assertEquals(100, placed, "every other label is placed somewhere by the split");
    assertTrue(report.train().stream().noneMatch(u -> u.issueId().equals("i100")));
    assertTrue(report.test().stream().noneMatch(u -> u.issueId().equals("i100")));
  }

  @Test
  @DisplayName("resolving at the same instant as the first commit is inside the lifecycle")
  void sameInstantIsKept() {
    final List<IssueUnitSource.Label> labels = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      labels.add(label(i, i, i + 1, i + 1));
    }
    assertEquals(0, Population.of(labels, 0.70).resolvedBeforeCommit());
  }
}
