package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six constructs that read the final state of the change.
 *
 * <p>They have no reference implementation to be checked against -- the validation measured their
 * coverage and classified them, but never extracted them -- so these are fixtures, and they are the
 * only guarantee these six have. Written to pin the parts that are easy to get wrong: distinct
 * commits rather than file actions, a median rather than a mean, and absence rather than a division
 * by one.
 */
class TechnicalConstructsTest {

  private static final long DAY = 86_400_000L;
  private static final Instant T0 = Instant.parse("2018-06-15T12:00:00Z");
  private static final long T0_MS = T0.toEpochMilli();

  private static final Unit UNIT = new Unit("alvo", "p", "ana", T0, T0.plusMillis(3 * DAY));

  private static Tables.Builder base() {
    return new Tables.Builder()
        .pullRequest("alvo", "ana", T0_MS, "p")
        .origin("p", T0_MS - 900 * DAY, "commit");
  }

  @Test
  @DisplayName("the five snapshot constructs are the diff as the corpus recorded it")
  void theSnapshotConstructsComeFromTheDiff() {
    final Tables tables = base()
        .diff("alvo", new Tables.Diff(3, 120, 45, true, 7),
            List.of("src/A.java", "src/B.java", "src/test/CTest.java"))
        .build();

    final Row row = Extractor.of(UNIT, tables);

    assertEquals(120.0, row.values().get("additions_at_open"));
    assertEquals(45.0, row.values().get("deletions_at_open"));
    assertEquals(3.0, row.values().get("num_files_at_open"));
    assertEquals(1.0, row.values().get("test_inclusion_at_open"));
    assertEquals(7.0, row.values().get("commits_at_open"),
        "the final count of the request's commits, which is what the literature uses");
  }

  @Test
  @DisplayName("hotness is the median over touched files, relative to the project's own activity")
  void hotnessIsAMedianOverTouchedFiles() {
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(3, 1, 1, false, 1),
            List.of("a.java", "b.java", "c.java"));
    // a.java: 1 commit in the window, b.java: 3, c.java: 5 -> median 3
    builder.touched("p", "a.java", T0_MS - 10 * DAY, 1);
    builder.touched("p", "b.java", T0_MS - 10 * DAY, 1);
    builder.touched("p", "b.java", T0_MS - 20 * DAY, 2);
    builder.touched("p", "b.java", T0_MS - 30 * DAY, 3);
    for (int i = 0; i < 5; i++) {
      builder.touched("p", "c.java", T0_MS - (10 + i) * DAY, 10 + i);
    }
    // ten commits in the project in the same window
    for (int i = 0; i < 10; i++) {
      builder.commit("p", T0_MS - (5 + i) * DAY);
    }

    final Row row = Extractor.of(UNIT, builder.build());

    assertEquals(3.0 / 10.0, row.values().get("area_hotness_90d"), 1e-12,
        "median of {1, 3, 5} is 3, over the project's ten commits in the window");
    assertEquals(0.0, row.values().get("area_hotness_90d_ausente"));
  }

  @Test
  @DisplayName("one commit touching one file twice is one commit, not two")
  void distinctCommitsNotFileActions() {
    // (commit_id, file_id) is not unique in file_action -- measured on the corpus -- so counting
    // rows would inflate the numerator. The packing deduplicates on the commit's index.
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(1, 1, 1, false, 1), List.of("a.java"));
    builder.touched("p", "a.java", T0_MS - 10 * DAY, 7);
    builder.touched("p", "a.java", T0_MS - 10 * DAY, 7);
    builder.commit("p", T0_MS - 5 * DAY);

    final Row row = Extractor.of(UNIT, builder.build());

    assertEquals(1.0, row.values().get("area_hotness_90d"), 1e-12,
        "one distinct commit over one project commit");
  }

  @Test
  @DisplayName("activity at or after t0, and outside the ninety days, does not count")
  void theHotnessWindowIsHalfOpenAndNinetyDays() {
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(1, 1, 1, false, 1), List.of("a.java"));
    builder.touched("p", "a.java", T0_MS - 10 * DAY, 1);     // inside
    builder.touched("p", "a.java", T0_MS - 100 * DAY, 2);    // older than ninety days
    builder.touched("p", "a.java", T0_MS, 3);                // exactly at t0
    builder.touched("p", "a.java", T0_MS + DAY, 4);          // after
    builder.commit("p", T0_MS - 5 * DAY);
    builder.commit("p", T0_MS - 200 * DAY);
    builder.commit("p", T0_MS);

    final Row row = Extractor.of(UNIT, builder.build());

    assertEquals(1.0, row.values().get("area_hotness_90d"), 1e-12,
        "one touch inside the window over one project commit inside it");
  }

  @Test
  @DisplayName("a project with no commit in the window has no hotness, and never a zero")
  void hotnessIsAbsentWithoutADenominator() {
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(1, 1, 1, false, 1), List.of("a.java"));
    builder.touched("p", "a.java", T0_MS - 10 * DAY, 1);
    builder.commit("p", T0_MS - 300 * DAY);

    final Row row = Extractor.of(UNIT, builder.build());

    assertTrue(row.isAbsent("area_hotness_90d"),
        "no denominator means no value -- not zero, and not a division by one");
    assertEquals(1.0, row.values().get("area_hotness_90d_ausente"));
  }

  @Test
  @DisplayName("a change touching no recorded file has no hotness either")
  void hotnessIsAbsentWithoutPaths() {
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(0, 0, 0, false, 0), List.of());
    builder.commit("p", T0_MS - 5 * DAY);

    final Row row = Extractor.of(UNIT, builder.build());

    assertTrue(row.isAbsent("area_hotness_90d"), "there is nothing to take a median over");
    assertEquals(0.0, row.values().get("num_files_at_open"));
  }

  @Test
  @DisplayName("a file the mined history never recorded contributes a real zero to the median")
  void anUnknownPathCountsAsNoActivity() {
    // The common case: a file this very change adds. The repository was mined, so no record of the
    // path means it had no prior history -- which is zero commits, not an unknown.
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(2, 1, 1, false, 1), List.of("novo.java", "antigo.java"));
    builder.touched("p", "antigo.java", T0_MS - 10 * DAY, 1);
    builder.touched("p", "antigo.java", T0_MS - 11 * DAY, 2);
    builder.commit("p", T0_MS - 5 * DAY);
    builder.commit("p", T0_MS - 6 * DAY);

    final Row row = Extractor.of(UNIT, builder.build());

    assertEquals(1.0 / 2.0, row.values().get("area_hotness_90d"), 1e-12,
        "median of {0, 2} is 1, over the project's two commits");
  }

  @Test
  @DisplayName("truncating the future changes no snapshot construct, and that is the finding")
  void theSnapshotConstructsSurviveTruncation() {
    // They pass the time-travel comparison for the wrong reason: pull_request_file carries no
    // timestamp, so there is nothing dated to remove. The test states it rather than hiding it.
    final Tables.Builder builder = base()
        .diff("alvo", new Tables.Diff(2, 10, 5, true, 4), List.of("a.java", "test/B.java"));
    builder.touched("p", "a.java", T0_MS + 10 * DAY, 1);
    builder.commit("p", T0_MS - 5 * DAY);
    final Tables complete = builder.build();

    final Row fromComplete = Extractor.of(UNIT, complete);
    final Row fromTruncated = Extractor.of(UNIT, complete.truncatedBefore(T0_MS));

    for (final String column : List.of("additions_at_open", "deletions_at_open",
        "num_files_at_open", "test_inclusion_at_open", "commits_at_open")) {

      assertEquals(fromComplete.values().get(column), fromTruncated.values().get(column),
          column + " is identical because it was never dated, not because it was filtered");
    }
    assertEquals(0.0, fromTruncated.values().get("area_hotness_90d"), 1e-12,
        "the hotness history IS dated, so truncation removes the activity after t0");
  }
}
