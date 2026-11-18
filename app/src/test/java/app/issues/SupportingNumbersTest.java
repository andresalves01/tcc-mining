package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.FirstCommit.Code;
import app.issues.SupportingNumbers.Entry;
import app.issues.SupportingNumbers.Pick;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The numbers that support the text: the filter's counts and the choice of the worked examples. */
class SupportingNumbersTest {

  private static long day(final int d) {
    return java.time.Instant.parse(String.format("2024-01-%02dT12:00:00Z", d)).toEpochMilli();
  }

  private static Code code(final int n, final long at) {
    return new Code("s" + n + "-" + at, "x", at, 10L, "change " + at, Set.of(), false);
  }

  private static FirstCommit.Issue issue(final String id, final long created,
      final long... commits) {
    final List<Code> code = new ArrayList<>();
    for (final long at : commits) {
      code.add(code(code.size(), at));
    }
    return new FirstCommit.Issue(id, "P-" + id, "p", created, day(30), code, List.of(), List.of());
  }

  // ------------------------------------------------------------------ counts

  @Test
  @DisplayName("counts the issues with a commit before their creation, before their In Progress, "
      + "and with a single commit, each over its own denominator")
  void counts() {
    final List<FirstCommit.Issue> issues = List.of(
        issue("a", day(1), day(5), day(6)), // ordinary, In Progress on the 4th
        issue("b", day(1)), // no commit that counts
        issue("c", day(5), day(5), day(7)), // created at the instant of the first commit
        issue("d", day(8), day(5), day(9)), // created after the first commit
        issue("e", day(1), day(5)), // one commit
        issue("f", day(1), day(3), day(9)), // first commit before the In Progress of the 4th
        issue("g", day(1), day(3), day(9))); // never In Progress
    final Map<String, Long> inProgress = new HashMap<>();
    inProgress.put("a", day(4));
    inProgress.put("e", day(4));
    inProgress.put("f", day(4));
    inProgress.put("c", day(5)); // the first commit at that very instant is not before it
    final SupportingNumbers.Counts c = SupportingNumbers.count(issues, inProgress::get);
    assertEquals(7, c.base());
    assertEquals(6, c.withCode(), "b has no commit that counts");
    assertEquals(2, c.createdAtOrAfterFirstCommit(), "c at the instant, d after");
    assertEquals(4, c.withInProgress(), "a, c, e and f, which have code and an In Progress");
    assertEquals(1, c.firstBeforeInProgress(), "f alone: the first commit is before the 4th");
    assertEquals(1, c.single());
  }

  @Test
  @DisplayName("an issue without an In Progress is not in the denominator of the In Progress "
      + "count, and its creation is never used as the start")
  void noInProgressNoFallback() {
    final List<FirstCommit.Issue> issues = List.of(issue("a", day(1), day(5), day(6)));
    final SupportingNumbers.Counts c = SupportingNumbers.count(issues, id -> null);
    assertEquals(0, c.withInProgress());
    assertEquals(0, c.firstBeforeInProgress());
  }

  @Test
  @DisplayName("a single commit is counted before the duplicates are removed: two copies of one "
      + "commit are two commits here")
  void singleBeforeDuplicates() {
    final FirstCommit.Issue copies = issue("a", day(1), day(5), day(5));
    assertEquals(1, copies.code().stream().map(Code::at).distinct().count());
    assertEquals(0, SupportingNumbers.count(List.of(copies), id -> null).single());
  }

  // ------------------------------------------------------------------ the choice

  /** An entry whose target has {@code calendar - total} days of gap, and the given parts. */
  private static Entry entry(final String id, final int calendar, final int total,
      final int capped, final int tail) {
    final FirstCommit.Count count = new FirstCommit.Count(total, total - tail, tail, capped, 3, 0);
    return new Entry(id, "P-" + id, new FirstCommit.Explanation(count, List.of(), List.of(),
        Collections.nCopies(tail, LocalDate.of(2024, 1, 1)), calendar, 0));
  }

  @Test
  @DisplayName("picks the largest calendar-minus-study gap, the largest capped and the largest "
      + "tail in days, one issue for each")
  void picksByThreeCriteria() {
    final List<Entry> entries = List.of(
        entry("1", 100, 20, 5, 1), // gap 80
        entry("2", 60, 40, 30, 2), // gap 20, the most capped
        entry("3", 30, 25, 0, 9)); // gap 5, the longest tail
    final List<Pick> picks = SupportingNumbers.choose(entries);
    assertEquals(List.of("1", "2", "3"), picks.stream().map(p -> p.entry().id()).toList());
    assertEquals(List.of("calendar_minus_study", "capped", "tail"),
        picks.stream().map(Pick::criterion).toList());
    assertEquals(List.of(80L, 30L, 9L), picks.stream().map(Pick::value).toList());
    assertTrue(picks.stream().allMatch(p -> p.note().isEmpty()));
  }

  @Test
  @DisplayName("a tie is broken by the smaller id, whatever the order the issues come in")
  void tiesById() {
    final List<Entry> entries = new ArrayList<>(List.of(
        entry("b", 50, 10, 0, 0), entry("a", 50, 10, 0, 0), entry("c", 50, 10, 0, 0)));
    for (int shuffle = 0; shuffle < 4; shuffle++) {
      Collections.rotate(entries, 1);
      final List<Pick> picks = SupportingNumbers.choose(entries);
      assertEquals(List.of("a", "b", "c"), picks.stream().map(p -> p.entry().id()).toList(),
          "rotation " + shuffle);
    }
  }

  @Test
  @DisplayName("when a criterion's best issue was already chosen, the next distinct one is "
      + "taken and the note says so")
  void nextDistinct() {
    final List<Entry> entries = List.of(
        entry("1", 100, 20, 50, 9), // the best of all three
        entry("2", 80, 30, 40, 8),
        entry("3", 70, 40, 30, 7));
    final List<Pick> picks = SupportingNumbers.choose(entries);
    assertEquals(List.of("1", "2", "3"), picks.stream().map(p -> p.entry().id()).toList());
    assertEquals("", picks.get(0).note());
    assertTrue(picks.get(1).note().contains("1"), picks.get(1).note());
    assertTrue(picks.get(1).note().contains("calendar_minus_study"), picks.get(1).note());
    assertTrue(picks.get(2).note().contains("1") && picks.get(2).note().contains("2"),
        picks.get(2).note());
  }

  @Test
  @DisplayName("with fewer issues than criteria, it picks as many as there are, never twice")
  void fewIssues() {
    assertEquals(1, SupportingNumbers.choose(List.of(entry("1", 10, 5, 1, 1))).size());
    assertEquals(0, SupportingNumbers.choose(List.of()).size());
  }
}
