package app.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cost.Contribution;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three-argument constructor: accumulation from one observation, presence from another.
 *
 * <p>The case it exists for is a corpus whose two observations stop at different times, which is
 * what {@code AsOfLedger}'s javadoc describes. These fixtures reproduce that shape in miniature:
 * the ledger stops early, the presence source keeps going.
 */
class AsOfLedgerPresenceTest {

  private static final Instant EARLY = Instant.parse("2018-01-01T00:00:00Z");
  private static final Instant LATE = Instant.parse("2020-01-01T00:00:00Z");
  private static final Duration WINDOW = Duration.ofDays(90);

  private static Contribution row(final String person, final String project, final Instant at,
      final int javaLines) {
    return new Contribution(person + "@" + at, null, null, project, null, person, person, false,
        at, javaLines, 0, javaLines, 0, 1, 1);
  }

  private static Contribution bot(final String person, final String project, final Instant at) {
    return new Contribution(person + "@" + at, null, null, project, null, person, person, true,
        at, 0, 0, 0, 0, 0, 0);
  }

  @Test
  @DisplayName("presence comes from the second source, accumulation from the first")
  void presenceAndAccumulationComeFromDifferentSources() {
    final List<Contribution> ledger = List.of(row("alice", "demo", EARLY, 500));
    final List<Contribution> presence = List.of(row("bob", "demo", LATE, 0));

    final AsOfLedger asOf = new AsOfLedger(ledger, java.util.Map.of(), presence);

    // At the late instant the commit ledger has nobody inside the window; the presence source does.
    assertEquals(Set.of("bob"), asOf.activeTeamAt("demo", LATE.plusSeconds(1), WINDOW),
        "the active team is whoever the presence source saw, not whoever the ledger saw");
    // And accumulation still answers from the ledger, for someone the presence source never lists.
    assertEquals(500L, asOf.historyAt("alice", "demo", LATE).orElseThrow().javaLines(),
        "accumulated volume still comes from the ledger");
    assertTrue(asOf.historyAt("bob", "demo", LATE).isEmpty(),
        "a person known only to the presence source has no accumulated history, which is a real "
            + "absence and not a zero");
  }

  @Test
  @DisplayName("the two-argument constructor is unchanged: one source answers both")
  void twoArgumentConstructorUsesOneSourceForBoth() {
    final List<Contribution> ledger =
        List.of(row("alice", "demo", EARLY, 500), row("bob", "demo", EARLY, 100));

    final AsOfLedger both = new AsOfLedger(ledger, java.util.Map.of());
    final AsOfLedger explicit = new AsOfLedger(ledger, java.util.Map.of(), ledger);

    final Instant justAfter = EARLY.plusSeconds(1);
    assertEquals(both.activeTeamAt("demo", justAfter, WINDOW),
        explicit.activeTeamAt("demo", justAfter, WINDOW));
    assertEquals(both.historyAt("alice", "demo", justAfter).orElseThrow().javaLines(),
        explicit.historyAt("alice", "demo", justAfter).orElseThrow().javaLines());
  }

  @Test
  @DisplayName("bots are excluded from presence whichever source it came from")
  void botsAreExcludedFromPresence() {
    final List<Contribution> ledger = List.of(row("alice", "demo", EARLY, 10));
    final List<Contribution> presence =
        List.of(bot("robot", "demo", LATE), row("bob", "demo", LATE, 0));

    final AsOfLedger asOf = new AsOfLedger(ledger, java.util.Map.of(), presence);

    assertEquals(Set.of("bob"), asOf.activeTeamAt("demo", LATE.plusSeconds(1), WINDOW),
        "a bot is not a member of the active team, and separating the sources must not change that");
  }

  @Test
  @DisplayName("a presence row outside the window does not put its author on the team")
  void presenceRespectsTheWindow() {
    final List<Contribution> presence = List.of(row("bob", "demo", EARLY, 0));

    final AsOfLedger asOf = new AsOfLedger(List.of(), java.util.Map.of(), presence);

    assertTrue(asOf.activeTeamAt("demo", LATE, WINDOW).isEmpty(),
        "presence is still bounded by the window -- the separate source changes where it is read "
            + "from, never how far back it reaches");
  }
}
