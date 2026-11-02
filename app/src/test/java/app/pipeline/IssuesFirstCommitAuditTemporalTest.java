package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.World;
import app.issues.port.StudySource;
import app.pipeline.IssuesFirstCommitAuditTask.Censoring;
import app.pipeline.IssuesFirstCommitAuditTask.Report;
import app.pipeline.IssuesFirstCommitAuditTask.Side;
import app.pipeline.IssuesFirstCommitAuditTask.Temporal;
import app.pipeline.port.SilentProgress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit under the temporal rule, on ten issues whose first commits fall on the Mondays of ten
 * weeks: the outer cut is week 6 (position 7 of 10), the inner one week 4 (position 5 of 7).
 */
class IssuesFirstCommitAuditTemporalTest {

  private static final long H = World.HOUR;
  private static final long D = World.DAY;

  /** Monday noon UTC of week {@code w} of 2024, from 0. */
  private static long monday(final int w) {
    return Instant.parse("2024-01-01T12:00:00Z").toEpochMilli() + 7 * w * D;
  }

  private static String id(final int n) {
    return String.format("%024x", n);
  }

  private final List<Corpus.Issue> issues = new ArrayList<>();
  private final List<Corpus.Event> events = new ArrayList<>();
  private final List<Corpus.Comment> comments = new ArrayList<>();
  private final List<Corpus.Commit> commits = new ArrayList<>();
  private final Map<String, Corpus.Person> people = new HashMap<>();
  private final Map<String, FirstCommitFacts.Native> natives = new HashMap<>();
  private int ext = 1;

  private void issue(final int n, final String project, final long created, final Long resolved,
      final String reason) {
    issues.add(new Corpus.Issue(id(n), project.toUpperCase() + "-" + n, project, created, "rep",
        "rep", null, "Bug", "Major", null, List.of(), List.of()));
    if (resolved != null) {
      events.add(new Corpus.Event(id(n), resolved, "status", "x", "Open", "Resolved",
          (ext++) + "%%0"));
      events.add(new Corpus.Event(id(n), resolved, "resolution", "x", null, reason,
          (ext - 1) + "%%1"));
    }
  }

  private void commit(final String sha, final String project, final String author, final long at,
      final Integer linked) {
    commits.add(new Corpus.Commit("c" + sha, project, sha, author, at, at, 1,
        linked == null ? List.of() : List.of(id(linked))));
    natives.put("c" + sha, new FirstCommitFacts.Native(sha.startsWith("a") ? 10L : 30L, sha));
  }

  /** An issue of the population: commits on Monday of {@code week} and {@code gap} days later. */
  private void eligible(final int n, final String project, final int week, final int gap,
      final Long resolved) {
    final long second = monday(week) + gap * D;
    issue(n, project, monday(week) - H, resolved == null ? second + H : resolved, "Fixed");
    commit("a" + n, project, "dev", monday(week), n);
    commit("b" + n, project, "dev", second, n);
  }

  @BeforeEach
  void corpus() {
    for (int week = 0; week < 10; week++) {
      eligible(week + 1, week < 8 ? "p" : "n", week, week < 5 ? 1 : 3,
          week == 4 ? monday(7) : null); // week 4 crosses both cuts
    }
    // no first resolution as Fixed, a commit that counts after the outer cut
    issue(101, "p", monday(0), null, null); // never resolved: censored
    commit("s101", "p", "dev", monday(8), 101);
    comments.add(new Corpus.Comment(id(101), "rep", monday(11), 3, false)); // the data's end
    issue(102, "p", monday(0), monday(9), "Won't Fix"); // resolved, another reason
    commit("s102", "p", "dev", monday(8), 102);
    // not counted: the commit before the cut; a bot's commit
    issue(103, "p", monday(0), null, null);
    commit("s103", "p", "dev", monday(2), 103);
    issue(104, "p", monday(0), null, null);
    people.put("robot", new Corpus.Person("robot", true));
    commit("s104", "p", "robot", monday(8), 104);
    // a commit after every Jira record moves only the latest commit
    commit("late", "p", "dev", monday(12), null);
  }

  private Report run(final SplitRule rule) {
    final Corpus corpus = new Corpus(issues, events, comments, List.of(), commits,
        Map.of("p", Set.of("P"), "n", Set.of("N")), Map.of(), people, Map.of(), Map.of(),
        Map.of(), Map.of(), List.of());
    final StudySource source = new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        throw new AssertionError("the audit reads no text");
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
        final Map<String, FirstCommitFacts.Native> n = new HashMap<>();
        commitIds.forEach(c -> n.put(c, natives.get(c)));
        return new FirstCommitFacts(n, Map.of(), Map.of());
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
    return new IssuesFirstCommitAuditTask(source, SilentProgress.INSTANCE, rule).run();
  }

  @Test
  @DisplayName("the cuts, the sides and the projects on each side")
  void sides() {
    final Report r = run(SplitRule.TEMPORAL);
    assertEquals(SplitRule.TEMPORAL, r.rule());
    assertEquals(new IssuesFirstCommitAuditTask.Sizes(10, 2, 7, 3, 5, 2), r.sizes());
    final Temporal t = r.temporal();
    assertEquals(Instant.ofEpochMilli(monday(6)), t.cuts().outer());
    assertEquals(Instant.ofEpochMilli(monday(4)), t.cuts().inner());
    assertEquals(new IssuesFirstCommitAuditTask.Projects(1, 2, 1, 1), t.projects());
    assertEquals(Map.of("n", 2), t.newProjects(), "a project with issues on the test side alone");
    assertEquals(Map.of(), t.newInnerProjects());
    assertEquals(1, r.threshold().cap());
  }

  @Test
  @DisplayName("the target of the inner fitting, the inner validation and the outer training; "
      + "of the test only its size")
  void targets() {
    final Report r = run(SplitRule.TEMPORAL);
    final Temporal t = r.temporal();
    assertEquals(5, t.fit().n());
    assertEquals(1.0, t.fit().median());
    assertEquals(2, t.validation().n());
    assertEquals(1.0, t.validation().p90(), "three days of wait capped at one");
    assertEquals(7, r.training().target().n());
    assertEquals(7, r.rows().stream().filter(x -> x.side() != null).count());
    r.rows().forEach(x -> assertNotEquals(Side.TEST, x.side()));
  }

  @Test
  @DisplayName("training issues resolved after the cut cross it: the one at the cut and an earlier "
      + "one")
  void crossing() {
    final Temporal t = run(SplitRule.TEMPORAL).temporal();
    assertEquals(2, t.crossing(), "week 4, resolved in week 7, and week 6, at the cut");
    assertEquals(1, t.innerCrossing(), "week 4, at the inner cut");
  }

  @Test
  @DisplayName("the cap the random rule learns on the same issues stands beside the new one")
  void randomCap() {
    final Temporal t = run(SplitRule.TEMPORAL).temporal();
    assertEquals(run(SplitRule.RANDOM).threshold(), t.random());
  }

  @Test
  @DisplayName("the censoring: the data's end and the issues started after the cut without a "
      + "first resolution as Fixed")
  void censoring() {
    final Censoring c = run(SplitRule.TEMPORAL).temporal().censoring();
    assertEquals(Instant.ofEpochMilli(monday(11)), c.end(), "the latest Jira record");
    assertEquals(Instant.ofEpochMilli(monday(12)), c.lastCommit());
    assertEquals(Instant.ofEpochMilli(monday(11)), c.projectEnds().get("p"));
    assertEquals(Instant.ofEpochMilli(monday(9) + 3 * D + H), c.projectEnds().get("n"));
    assertEquals(1, c.unresolved(), "the bot's commit and the one before the cut do not count");
    assertEquals(1, c.otherReason());
    assertEquals(0, c.unitless());
    assertEquals(1, c.unresolvedDays().n());
    assertEquals(21.0, c.unresolvedDays().median());
    assertEquals(21.0, c.unresolvedProjectDays().median());
    assertEquals(3, c.testDays().n());
    assertEquals(14.0, c.testDays().min());
    assertEquals(28.0, c.testDays().max());
    assertEquals(3.0, c.testProjectDays().min(), "week 9 to the last record of project n");
    assertEquals(28.0, c.testProjectDays().max());
  }

  @Test
  @DisplayName("the random rule adds nothing of the temporal report")
  void randomAddsNothing() {
    final Report r = run(SplitRule.RANDOM);
    assertEquals(SplitRule.RANDOM, r.rule());
    assertNull(r.temporal());
    assertNotNull(r.threshold());
  }
}
