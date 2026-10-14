package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The decomposition of the outcome, in the part that touches no database. */
class TtcDecompositionTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private static IssueLabel label(final Optional<Instant> examinedAt) {
    return new IssueLabel("6100000000000000000000aa", "demo", T0, T0.plus(Duration.ofHours(48)),
        "6100000000000000000000bb", T0, 1, 10, 2, List.of(),
        examinedAt, examinedAt.map(at -> ExamOrigin.REVIEW));
  }

  @Test
  @DisplayName("the total always exists; the two parts only when there was an examination")
  void onlyTheTotalIsAlwaysDefined() {
    final IssueLabel withoutExam = label(Optional.empty());

    assertEquals(2.0, withoutExam.durationOf(TtcTarget.TOTAL).orElseThrow(), 1e-9);
    assertTrue(withoutExam.durationOf(TtcTarget.WAIT).isEmpty());
    assertTrue(withoutExam.durationOf(TtcTarget.AUTHOR).isEmpty());
  }

  @Test
  @DisplayName("the parts add up to the total, which is what makes adding the predictions legitimate")
  void theComponentsPartitionTheWhole() {
    final IssueLabel withExam = label(Optional.of(T0.plus(Duration.ofHours(12))));

    final double wait = withExam.durationOf(TtcTarget.WAIT).orElseThrow();
    final double author = withExam.durationOf(TtcTarget.AUTHOR).orElseThrow();

    assertEquals(0.5, wait, 1e-9);
    assertEquals(1.5, author, 1e-9);
    assertEquals(withExam.durationOf(TtcTarget.TOTAL).orElseThrow(), wait + author, 1e-9);
  }

  @Test
  @DisplayName("the working version discounts the weekend and nothing else")
  void workingVersionsRemoveWeekends() {
    // Friday 03/01/2020 12:00 -> Wednesday 08/01 12:00: five calendar days, two of them weekend
    final Instant friday = Instant.parse("2020-01-03T12:00:00Z");
    final IssueLabel crossing = new IssueLabel("6100000000000000000000aa", "demo", friday,
        friday.plus(Duration.ofDays(5)), "6100000000000000000000bb", friday, 1, 10, 2, List.of(),
        Optional.of(friday.plus(Duration.ofDays(1))), Optional.of(ExamOrigin.REVIEW));

    assertEquals(5.0, crossing.durationOf(TtcTarget.TOTAL).orElseThrow(), 1e-9);
    assertEquals(3.0, crossing.durationOf(TtcTarget.TOTAL_WORKING).orElseThrow(), 1e-9);
    // the examination fell on Saturday: the working wait is only the half Friday before it
    assertEquals(1.0, crossing.durationOf(TtcTarget.WAIT).orElseThrow(), 1e-9);
    assertEquals(0.5, crossing.durationOf(TtcTarget.WAIT_WORKING).orElseThrow(), 1e-9);
    assertEquals(crossing.durationOf(TtcTarget.TOTAL_WORKING).orElseThrow(),
        crossing.durationOf(TtcTarget.WAIT_WORKING).orElseThrow()
            + crossing.durationOf(TtcTarget.AUTHOR_WORKING).orElseThrow(), 1e-9,
        "the working parts must also add up to the working total");
  }

  @Test
  @DisplayName("without an examination, the working parts are missing for the same reason as the clock ones")
  void workingComponentsAreAbsentTogether() {
    final IssueLabel withoutExam = label(Optional.empty());

    assertTrue(withoutExam.durationOf(TtcTarget.TOTAL_WORKING).isPresent());
    assertTrue(withoutExam.durationOf(TtcTarget.WAIT_WORKING).isEmpty());
    assertTrue(withoutExam.durationOf(TtcTarget.AUTHOR_WORKING).isEmpty());
  }

  @Test
  @DisplayName("an issue label has no external examination, and the old constructor says so by itself")
  void issueLabelsCarryNoExamination() {
    final IssueLabel issue = new IssueLabel("6100000000000000000000aa", "demo", T0,
        T0.plus(Duration.ofHours(48)), "6100000000000000000000bb", T0, 1, 10, 2, List.of());

    assertTrue(issue.examinedAt().isEmpty());
    assertTrue(issue.examOrigin().isEmpty());
    assertEquals(2.0, issue.durationOf(TtcTarget.TOTAL).orElseThrow(), 1e-9);
  }
}
