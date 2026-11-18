package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommit;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.SupportingNumbers;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.port.SilentProgress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The supporting numbers on a corpus built by hand: ten issues a week apart, the latest of which
 * waits for a long time between its two commits and lands on the outer test.
 */
public class IssuesFirstCommitSupportingNumbersTaskTest {

  private static final long DAY = World.DAY;
  private static final long MONDAY = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli();

  private static String id(final int n) {
    return String.format("%024x", n);
  }

  /**
   * Issue n starts n - 1 weeks after the first, its second commit two days after the first
   * commit, except issue 10's, 16 days after it; the first six went through In Progress, a day
   * after their first commit.
   */
  public static StudySource source() {
    return source(n -> n == 10 ? 17 : 3, false);
  }

  /**
   * As {@link #source()}, issue n's second commit {@code secondDay.applyAsInt(n)} days after its
   * Monday; with {@code quick}, each issue resolved an hour after its second commit instead of 30
   * days after its Monday, so under the revised rule only the issue at each cut crosses it.
   */
  private static StudySource source(final java.util.function.IntUnaryOperator secondDay,
      final boolean quick) {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Commit> commits = new ArrayList<>();
    final Map<String, FirstCommitFacts.Native> facts = new HashMap<>();
    for (int n = 1; n <= 10; n++) {
      final String id = id(n);
      final long monday = MONDAY + 7L * (n - 1) * DAY;
      final long second = monday + secondDay.applyAsInt(n) * DAY;
      final long resolved = quick ? second + DAY / 24 : monday + 30 * DAY;
      issues.add(new Corpus.Issue(id, "P-" + n, "p", monday, "rep", "rep", null, "Bug", "Major",
          null, List.of(), List.of()));
      if (n <= 6) {
        events.add(new Corpus.Event(id, monday + 2 * DAY, "status", "x", "Open", "In Progress",
            n + "%%2"));
      }
      events.add(new Corpus.Event(id, resolved, "status", "x", "Open", "Resolved",
          n + "%%0"));
      events.add(new Corpus.Event(id, resolved, "resolution", "x", null, "Fixed",
          n + "%%1"));
      commits.add(new Corpus.Commit("a" + n, "p", "ha" + n, "dev", monday + DAY, monday + DAY, 1,
          List.of(id)));
      commits.add(new Corpus.Commit("b" + n, "p", "hb" + n, "dev", second, second, 1,
          List.of(id)));
      facts.put("a" + n, new FirstCommitFacts.Native(10L, "start " + n));
      facts.put("b" + n, new FirstCommitFacts.Native(30L, "finish " + n));
    }
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), commits,
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        List.of());
    return new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        throw new AssertionError("no text is read");
      }

      @Override
      public Map<String, DurationEvidence> durationEvidence(final List<RowCache.Entry> rows,
          final Set<String> bots) {
        final Map<String, DurationEvidence> out = new HashMap<>();
        rows.forEach(r -> out.put(r.unit().issueId(), new DurationEvidence(List.of(), List.of(),
            List.of(), "native", List.of(), List.of())));
        return out;
      }

      @Override
      public FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachments) {
        return new FirstCommitFacts(facts, Map.of(), Map.of());
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  private static IssuesFirstCommitSupportingNumbersTask.Report run(final SplitRule rule,
      final double waitK) {
    return new IssuesFirstCommitSupportingNumbersTask(source(), SilentProgress.INSTANCE, rule,
        waitK).run();
  }

  @Test
  @DisplayName("counts the filter's three cases over the Fixed issues with a commit, the real "
      + "In Progress as the start")
  void counts() {
    final SupportingNumbers.Counts c = run(SplitRule.TEMPORAL, 1.5).counts();
    assertEquals(10, c.base());
    assertEquals(10, c.withCode());
    assertEquals(0, c.createdAfterFirstCommit());
    assertEquals(0, c.createdAtFirstCommit());
    assertEquals(6, c.withInProgress(), "only the first six went through In Progress");
    assertEquals(6, c.firstBeforeInProgress(), "the first commit a day before each In Progress");
    assertEquals(0, c.single());
  }

  @Test
  @DisplayName("the worked examples come from the outer training alone: the outer test's issue "
      + "with the longest wait is never chosen, though it would top the gap")
  void neverTheTest() {
    final IssuesFirstCommitSupportingNumbersTask.Report r = run(SplitRule.TEMPORAL, 1.5);
    assertEquals(7, r.training(), "the outer training of the temporal draw");
    final List<String> chosen = r.examples().stream().map(e -> e.pick().entry().id()).toList();
    assertEquals(3, chosen.size());
    assertEquals(chosen.size(), Set.copyOf(chosen).size(), "three distinct issues");
    assertFalse(chosen.contains(id(10)), "issue 10 is on the outer test: " + chosen);
    for (final String id : chosen) {
      assertTrue(Integer.parseInt(id, 16) <= 7, id + " is on the outer training");
    }
  }

  @Test
  @DisplayName("each example carries the issue's dates, marks, waits with their cut, and tail")
  void examplesCarryTheirExplanation() {
    final IssuesFirstCommitSupportingNumbersTask.Report r = run(SplitRule.TEMPORAL, 1.5);
    for (final IssuesFirstCommitSupportingNumbersTask.Example e : r.examples()) {
      final FirstCommit.Explanation x = e.pick().entry().explanation();
      assertEquals(FirstCommit.count(e.issue(), e.verdict(), r.threshold().cap()), x.count());
      assertEquals(2, x.marks().size());
      assertEquals(1, x.waits().size());
    }
  }

  @Test
  @DisplayName("the wait fence's k is the run's: with no cap no wait is cut in any example")
  void usesTheRunsFence() {
    final IssuesFirstCommitSupportingNumbersTask.Report none =
        run(SplitRule.TEMPORAL, FirstCommit.NO_WAIT_CAP);
    assertEquals(FirstCommit.NO_WAIT_CAP, none.waitFenceK());
    for (final IssuesFirstCommitSupportingNumbersTask.Example e : none.examples()) {
      assertEquals(0, e.pick().entry().explanation().count().capped());
    }
  }

  @Test
  @DisplayName("under the revised rule the examples are chosen among the outer training by "
      + "verdict, its crossing issue included, at the cap the draw learns without the wait of "
      + "the issue crossing the inner cut")
  void revisedReadsTheOuterTrainingByVerdict() {
    // waits of 1, 1, 2, 2 and 3 days on the inner fitting, issues 1 to 5; issue 5, at the inner
    // cut, crosses it, and issue 7, at the outer cut, crosses that one
    final int[] waits = {1, 1, 2, 2, 3};
    final java.util.function.IntUnaryOperator second =
        n -> 1 + (n <= 5 ? waits[n - 1] : n == 10 ? 16 : 1);
    final IssuesFirstCommitSupportingNumbersTask.Report revised =
        new IssuesFirstCommitSupportingNumbersTask(source(second, true), SilentProgress.INSTANCE,
            SplitRule.REVISED, 1.5).run();
    final IssuesFirstCommitSupportingNumbersTask.Report temporal =
        new IssuesFirstCommitSupportingNumbersTask(source(second, true), SilentProgress.INSTANCE,
            SplitRule.TEMPORAL, 1.5).run();
    assertEquals(7, revised.training(), "the outer training keeps its crossing issue 7");
    assertEquals(4, revised.threshold().gaps(), "issue 5's wait crosses the inner cut");
    assertEquals(5, temporal.threshold().gaps());
    assertTrue(revised.threshold().cap() < temporal.threshold().cap(),
        revised.threshold() + " against " + temporal.threshold());
    for (final IssuesFirstCommitSupportingNumbersTask.Example e : revised.examples()) {
      assertTrue(Integer.parseInt(e.pick().entry().id(), 16) <= 7, "on the outer training");
      assertEquals(FirstCommit.count(e.issue(), e.verdict(), revised.threshold().cap()),
          e.pick().entry().explanation().count(), "explained at the revised cap");
    }
  }
}
