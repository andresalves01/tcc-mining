package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.FirstCommit.Code;
import app.issues.FirstCommit.Rule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The first-commit population and its target, each rule with the case that would break it. */
class FirstCommitTest {

  private static final long H = World.HOUR;

  /** Noon UTC of a day of January 2024: the 1st is a Monday, the 6th a Saturday. */
  private static long day(final int d) {
    return Instant.parse(String.format("2024-01-%02dT12:00:00Z", d)).toEpochMilli();
  }

  private static Code code(final String sha, final String author, final long at,
      final Long lines) {
    return new Code(sha, author, at, lines, "change " + sha, Set.of(), false);
  }

  private static Code titled(final String sha, final String author, final long at,
      final Long lines, final String title) {
    return new Code(sha, author, at, lines, title, Set.of(), false);
  }

  private static FirstCommit.Issue issue(final long created, final long resolved,
      final List<Code> code) {
    return new FirstCommit.Issue("i", "P-1", "p", created, resolved, code, List.of(), List.of());
  }

  private static final FirstCommit.Boundary MIDNIGHT = (author, project, at) -> 0;

  private static FirstCommit.Verdict select(final FirstCommit.Issue issue) {
    return FirstCommit.select(issue, FirstCommit.SHARE, MIDNIGHT);
  }

  // ------------------------------------------------------------------ selection

  @Test
  @DisplayName("an issue with no commit that counts leaves first")
  void noCode() {
    assertEquals(Rule.NO_CODE, select(issue(day(1), day(9), List.of())).failed());
  }

  @Test
  @DisplayName("the issue must be created before its first commit, strictly")
  void createdBeforeFirstCommit() {
    final List<Code> two = List.of(code("a", "x", day(2), 10L), code("b", "x", day(3), 30L));
    assertEquals(Rule.CREATED_AFTER_FIRST_COMMIT, select(issue(day(2), day(9), two)).failed());
    assertEquals(Rule.CREATED_AFTER_FIRST_COMMIT,
        select(issue(day(2) + H, day(9), two)).failed());
    assertNull(select(issue(day(2) - 1, day(9), two)).failed());
  }

  @Test
  @DisplayName("two commits of the same author at the same instant count once; another author "
      + "at the same instant does not")
  void sameAuthorSameDate() {
    final FirstCommit.Verdict copies = select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 10L), code("b", "x", day(2), 10L))));
    assertEquals(Rule.FEWER_THAN_TWO, copies.failed());
    assertEquals(1, copies.duplicates().sameAuthorDate());
    assertEquals(1, copies.kept().size());

    final FirstCommit.Verdict pair = select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 10L), code("b", "y", day(2), 10L))));
    assertEquals(0, pair.duplicates().sameAuthorDate());
    assertEquals(2, pair.kept().size());
  }

  @Test
  @DisplayName("the same first message line with the same changed lines on another date counts "
      + "once, the oldest kept; a different line count or title is another commit")
  void sameTitleSameLines() {
    final FirstCommit.Verdict replicated = select(issue(day(1), day(20), List.of(
        titled("late", "y", day(9), 40L, "Fix the parser"),
        titled("early", "x", day(2), 40L, "Fix the parser"),
        titled("other", "x", day(4), 100L, "Add tests"))));
    assertEquals(1, replicated.duplicates().sameTitleLines());
    assertEquals(List.of("early", "other"), replicated.kept().stream().map(Code::sha).toList());

    final FirstCommit.Verdict distinct = select(issue(day(1), day(20), List.of(
        titled("a", "x", day(2), 40L, "Fix the parser"),
        titled("b", "x", day(9), 41L, "Fix the parser"),
        titled("c", "x", day(10), 40L, "Fix the lexer"))));
    assertEquals(0, distinct.duplicates().sameTitleLines());
    assertEquals(3, distinct.kept().size());

    final FirstCommit.Verdict unknown = select(issue(day(1), day(20), List.of(
        titled("a", "x", day(2), null, "Fix the parser"),
        titled("b", "x", day(9), null, "Fix the parser"))));
    assertEquals(0, unknown.duplicates().sameTitleLines(), "an unknown size matches nothing");
  }

  @Test
  @DisplayName("a dropped copy hands the issues it also links to the commit that stays")
  void duplicateCarriesItsLinks() {
    final Code kept = titled("early", "x", day(2), 40L, "Fix the parser");
    final Code copy = new Code("late", "y", day(9), 40L, "Fix the parser", Set.of("Q-7"), false);
    final FirstCommit.Verdict v = select(issue(day(1), day(20), List.of(kept, copy,
        code("c", "x", day(4), 400L))));
    assertEquals(Rule.SHARED, v.failed());
    assertEquals(Set.of("Q-7"), v.kept().getFirst().others());
  }

  @Test
  @DisplayName("at least two commits after the duplicates are gone")
  void atLeastTwo() {
    assertEquals(Rule.FEWER_THAN_TWO,
        select(issue(day(1), day(9), List.of(code("a", "x", day(2), 10L)))).failed());
  }

  @Test
  @DisplayName("the first commit must carry less than the limit of the issue's changed lines")
  void firstCommitShare() {
    final List<Code> half = List.of(code("a", "x", day(2), 50L), code("b", "x", day(3), 50L));
    final FirstCommit.Verdict v = select(issue(day(1), day(9), half));
    assertEquals(Rule.FIRST_TOO_LARGE, v.failed(), "exactly half is not below half");
    assertEquals(0.5, v.firstShare(), 1e-12);
    final List<Code> small = List.of(code("a", "x", day(2), 49L), code("b", "x", day(3), 51L));
    assertNull(select(issue(day(1), day(9), small)).failed());
    assertEquals(Rule.FIRST_TOO_LARGE,
        FirstCommit.select(issue(day(1), day(9), small), 0.3, MIDNIGHT).failed());
    assertNull(FirstCommit.select(issue(day(1), day(9), half), 0.7, MIDNIGHT).failed());
  }

  @Test
  @DisplayName("the first commit is the earliest authored, whatever the input order")
  void firstIsEarliest() {
    final FirstCommit.Verdict v = select(issue(day(1), day(9), List.of(
        code("b", "x", day(3), 90L), code("a", "x", day(2), 10L))));
    assertNull(v.failed());
    assertEquals("a", v.kept().getFirst().sha());
    assertEquals(0.1, v.firstShare(), 1e-12);
  }

  @Test
  @DisplayName("an unknown size, or no changed line at all, leaves the issue")
  void unknownSize() {
    assertEquals(Rule.UNKNOWN_SIZE, select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 10L), code("b", "x", day(3), null)))).failed());
    assertEquals(Rule.UNKNOWN_SIZE, select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 0L), code("b", "x", day(3), 0L)))).failed());
  }

  @Test
  @DisplayName("code on two dates at least, the dates cut at the first author's day boundary")
  void twoDays() {
    // 12:00 and 22:00 of the same day: one date at midnight, two at a boundary of 20:00
    final List<Code> sameDay = List.of(code("a", "x", day(2), 10L),
        code("b", "x", day(2) + 10 * H, 30L));
    final FirstCommit.Verdict midnight = select(issue(day(1), day(9), sameDay));
    assertEquals(Rule.ONE_DAY, midnight.failed());
    assertEquals(1, midnight.codeDays());
    final FirstCommit.Boundary evening = (author, project, at) -> {
      assertEquals("x", author);
      assertEquals("p", project);
      assertEquals(day(2), at);
      return 20;
    };
    final FirstCommit.Verdict late = FirstCommit.select(issue(day(1), day(9), sameDay),
        FirstCommit.SHARE, evening);
    assertEquals(2, late.codeDays());
    assertEquals(20, late.hour());
  }

  @Test
  @DisplayName("a commit that also links another issue leaves the issue")
  void shared() {
    final Code linked = new Code("b", "x", day(3), 30L, "t", Set.of("Q-9"), false);
    assertEquals(Rule.SHARED, select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 10L), linked))).failed());
  }

  @Test
  @DisplayName("an issue whose code days give no business day leaves as target 0")
  void zero() {
    // Saturday then Sunday, resolved on Sunday: no business date after the first commit's
    final FirstCommit.Verdict v = select(issue(day(1), day(7) + H, List.of(
        code("a", "x", day(6), 10L), code("b", "x", day(7), 30L))));
    assertEquals(Rule.ZERO, v.failed());
  }

  @Test
  @DisplayName("the rules apply in order: the first one an issue fails is its verdict")
  void order() {
    // fewer than two and created after: creation is checked first
    assertEquals(Rule.CREATED_AFTER_FIRST_COMMIT,
        select(issue(day(5), day(9), List.of(code("a", "x", day(2), 10L)))).failed());
    // too large and on one day: the size is checked first
    assertEquals(Rule.FIRST_TOO_LARGE, select(issue(day(1), day(9), List.of(
        code("a", "x", day(2), 90L), code("b", "x", day(2) + H, 10L)))).failed());
  }

  // ------------------------------------------------------------------ target

  private static FirstCommit.Issue worked(final List<Code> code, final List<Long> patches,
      final List<Long> activity, final long resolved) {
    return new FirstCommit.Issue("i", "P-1", "p", day(1) - H, resolved, code, patches, activity);
  }

  @Test
  @DisplayName("the start is the first commit; marks are the commits and the patches after it; "
      + "a patch before it does not enter")
  void marks() {
    final FirstCommit.Issue i = worked(List.of(code("a", "x", day(2), 10L),
        code("b", "x", day(4), 30L)), List.of(day(1), day(3), day(5)), List.of(), day(9));
    final FirstCommit.Verdict v = select(i);
    assertEquals(List.of(day(2), day(3), day(4), day(5)), FirstCommit.marks(i, v));
    assertEquals(List.of(1, 1, 1), FirstCommit.gaps(i, v));
  }

  @Test
  @DisplayName("each wait between marks is capped, but never below its dates of explicit activity")
  void cappedWaits() {
    // Tuesday 2 to Tuesday 16: ten business days; a comment on the 10th
    final FirstCommit.Issue i = worked(List.of(code("a", "x", day(2), 10L),
        code("b", "x", day(16), 30L)), List.of(), List.of(day(10)), day(16) + H);
    final FirstCommit.Verdict v = select(i);
    assertEquals(List.of(10), FirstCommit.gaps(i, v));
    final FirstCommit.Count capped = FirstCommit.count(i, v, 3);
    assertEquals(3, capped.implementation());
    assertEquals(7, capped.capped());
    assertEquals(3, capped.total());
    assertEquals(10, FirstCommit.count(i, v, 20).total());
    // explicit activity on five dates of the wait: the floor beats a cap of 3
    final FirstCommit.Issue busy = worked(i.code(), List.of(),
        List.of(day(3), day(4), day(5), day(8), day(9)), day(16) + H);
    assertEquals(6, FirstCommit.count(busy, select(busy), 3).implementation(),
        "five comment dates plus the second commit's own date");
  }

  @Test
  @DisplayName("after the last code only the dates with human activity count, up to the resolution")
  void tail() {
    // last code on Tuesday 2; comments on Thursday 4 and Saturday 6; resolved on Friday 12
    final FirstCommit.Issue i = worked(List.of(code("a", "x", day(1), 10L),
        code("b", "x", day(2), 30L)), List.of(), List.of(day(4), day(6), day(13)), day(12));
    final FirstCommit.Count c = FirstCommit.count(i, select(i), 5);
    assertEquals(1, c.implementation());
    assertEquals(1, c.tail(), "the weekend and the comment after the resolution do not count");
    assertEquals(2, c.total());
  }

  @Test
  @DisplayName("a patch can be the last code: the tail starts after it")
  void patchIsLastCode() {
    final FirstCommit.Issue i = worked(List.of(code("a", "x", day(1), 10L),
        code("b", "x", day(2), 30L)), List.of(day(4)), List.of(day(3), day(4), day(5)), day(12));
    final FirstCommit.Count c = FirstCommit.count(i, select(i), 5);
    assertEquals(3, c.implementation(), "one day commit to commit, two commit to patch");
    assertEquals(1, c.tail(), "only the comment on the 5th is after the patch");
    assertEquals(1, c.patches());
  }

  // ------------------------------------------------------------------ cap

  @Test
  @DisplayName("the cap is the largest gap not above Q3 + 1.5 IQR of the gaps it is given")
  void threshold() {
    final List<Integer> gaps = new ArrayList<>(List.of(0, 1, 1, 2, 2, 3, 4, 50));
    final FirstCommit.Threshold t = FirstCommit.threshold(gaps);
    assertEquals(1.0, t.q1(), 1e-12);
    assertEquals(2.0, t.median(), 1e-12);
    assertEquals(3.25, t.q3(), 1e-12);
    assertEquals(3.25 + 1.5 * 2.25, t.fence(), 1e-12);
    assertEquals(4, t.cap());
    assertEquals(8, t.gaps());
    assertThrows(IllegalArgumentException.class, () -> FirstCommit.threshold(List.of()));
  }

  @Test
  @DisplayName("a cap below one day is refused: the population's zero rule assumes at least one")
  void capOfZeroRefused() {
    assertThrows(IllegalStateException.class,
        () -> FirstCommit.threshold(List.of(0, 0, 0, 5)).requirePositive());
    assertTrue(FirstCommit.threshold(List.of(0, 1, 1)).requirePositive().cap() >= 1);
  }
}
