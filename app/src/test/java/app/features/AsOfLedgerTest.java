package app.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cost.Contribution;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The as-of index over the ledger, checked against the definition rather than against itself.
 *
 * <p>Every number the individual component of the model carries comes through here, and the one way
 * it can be wrong without anything failing is by reaching one commit too far. A row built from a
 * commit that belongs to the task being predicted is leakage: it would score well and mean nothing.
 */
class AsOfLedgerTest {

  private static final Instant EPOCH = Instant.parse("2010-01-01T00:00:00Z");
  private static final Duration NINETY_DAYS = Duration.ofDays(90);

  private static Instant day(final int day) {
    return EPOCH.plus(day, ChronoUnit.DAYS);
  }

  private static Contribution commit(final String developer, final String project, final int day,
      final int javaLines, final int otherLines, final boolean bot) {

    return new Contribution(
        developer + project + day, "hash" + day, project + "-id", project, "vcs",
        developer + "-raw", developer, bot, day(day),
        javaLines + otherLines, 0, javaLines, 0, 1, 1);
  }

  private static Contribution commit(final String developer, final String project, final int day) {
    return commit(developer, project, day, 10, 0, false);
  }

  /** The definition, written out longhand, with no index and no binary search. */
  private static Optional<DeveloperHistory> bruteForce(final List<Contribution> ledger,
      final String developer, final String project, final Instant asOf) {

    long java = 0;
    long all = 0;
    int commits = 0;
    Instant first = null;
    for (final Contribution c : ledger) {
      if (!c.canonicalId().equals(developer) || !c.projectName().equals(project)) {
        continue;
      }
      if (first == null || c.authoredAt().isBefore(first)) {
        first = c.authoredAt();
      }
      if (!c.authoredAt().isBefore(asOf)) {
        continue;
      }
      java += c.javaLinesAdded() + c.javaLinesDeleted();
      all += c.linesAdded() + c.linesDeleted();
      commits++;
    }
    if (commits == 0) {
      return Optional.empty();
    }
    return Optional.of(new DeveloperHistory(java, all, commits,
        (asOf.toEpochMilli() - first.toEpochMilli()) / 86_400_000.0));
  }

  @Test
  @DisplayName("the indexed answer matches the longhand definition over a randomised ledger")
  void indexAgreesWithTheDefinition() {
    final Random random = new Random(20260920L);
    final List<Contribution> ledger = new ArrayList<>();
    final String[] developers = {"alice", "bob", "carol", "dan"};
    final String[] projects = {"lucene", "derby", "camel"};
    for (int i = 0; i < 400; i++) {
      ledger.add(commit(developers[random.nextInt(developers.length)],
          projects[random.nextInt(projects.length)], random.nextInt(900),
          random.nextInt(500), random.nextInt(200), false));
    }
    final AsOfLedger index = new AsOfLedger(ledger);

    for (int probe = 0; probe < 300; probe++) {
      final String developer = developers[random.nextInt(developers.length)];
      final String project = projects[random.nextInt(projects.length)];
      final Instant asOf = day(random.nextInt(1000));
      assertEquals(bruteForce(ledger, developer, project, asOf),
          index.historyAt(developer, project, asOf),
          "the index disagrees with the definition at " + asOf + " for " + developer
              + " in " + project);
    }
  }

  @Test
  @DisplayName("a commit authored exactly at the instant asked about is not yet observable")
  void strictAnteriority() {
    final AsOfLedger index = new AsOfLedger(List.of(commit("alice", "lucene", 100)));

    assertTrue(index.historyAt("alice", "lucene", day(100)).isEmpty(),
        "a change made at t_i cannot compose the history that predicts what happens from t_i");
    assertEquals(1, index.historyAt("alice", "lucene", day(100).plusMillis(1)).orElseThrow()
        .commits(), "one millisecond later the same commit is history");
  }

  @Test
  @DisplayName("a first task and an unheard-of pair both read as zero, and are told apart")
  void firstTaskIsNotAGap() {
    final AsOfLedger index = new AsOfLedger(List.of(commit("alice", "lucene", 500)));

    assertTrue(index.historyAt("alice", "lucene", day(100)).isEmpty(),
        "alice had committed nothing in lucene yet, so her accumulated volume is zero");
    assertTrue(index.knows("alice", "lucene"),
        "the ledger does know the pair, so that zero is a first day and not a gap");
    assertFalse(index.knows("bob", "lucene"),
        "bob never appears, so his zero is missing evidence and has to be counted apart");
    assertTrue(index.historyAt("bob", "lucene", day(600)).isEmpty(), "and it still reads empty");
  }

  @Test
  @DisplayName("volume counts added plus deleted, and tenure runs from the pair's first commit")
  void accumulationAndTenure() {
    final AsOfLedger index = new AsOfLedger(List.of(
        commit("alice", "lucene", 10, 100, 5, false),
        commit("alice", "lucene", 40, 30, 0, false),
        commit("alice", "derby", 20, 999, 0, false)));

    final DeveloperHistory history = index.historyAt("alice", "lucene", day(50)).orElseThrow();
    assertEquals(130, history.javaLines(), "both lucene commits, java lines only");
    assertEquals(135, history.allLines(), "the same two commits over every file");
    assertEquals(2, history.commits());
    assertEquals(40.0, history.tenureDays(), 1e-9,
        "day 50 minus the first lucene commit, which was on day 10");
    assertEquals(999, index.historyAt("alice", "derby", day(50)).orElseThrow().javaLines(),
        "the scope is the project of the task, so derby volume stays out of lucene");
  }

  @Test
  @DisplayName("the active team is the humans who committed inside the window, and only them")
  void activeTeamWindow() {
    final AsOfLedger index = new AsOfLedger(List.of(
        commit("alice", "lucene", 100),
        commit("bob", "lucene", 320),
        commit("carol", "lucene", 355),
        commit("jenkins", "lucene", 356, 10, 0, true),
        commit("dan", "derby", 355)));

    final Set<String> team = index.activeTeamAt("lucene", day(400), NINETY_DAYS);

    assertEquals(Set.of("bob", "carol"), team,
        "alice last committed on day 100, jenkins is a bot, dan is in another project");
    assertEquals(Set.of(), index.activeTeamAt("hbase", day(400), NINETY_DAYS),
        "a project nobody committed to has an empty team rather than no answer");
  }

  @Test
  @DisplayName("the window includes its first day and excludes the instant it is anchored on")
  void windowBoundaries() {
    final AsOfLedger index = new AsOfLedger(List.of(
        commit("early", "lucene", 310),
        commit("onTheEdge", "lucene", 400)));

    assertEquals(Set.of("early"), index.activeTeamAt("lucene", day(400), NINETY_DAYS),
        "day 310 is exactly ninety days back and counts; a commit at t_i itself does not");
    assertEquals(Set.of("onTheEdge"), index.activeTeamAt("lucene", day(401), NINETY_DAYS),
        "a day later the window has slid past day 310 and the edge commit has become history");
  }
}
