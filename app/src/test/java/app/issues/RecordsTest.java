package app.issues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record instants the target with recorded activity reads (ticket 27, item 62): bot
 * accounts' events and comments are not records, as they are not activity at the day boundaries.
 */
class RecordsTest {

  private static final long D = World.DAY;
  /** A Monday, 00:00 UTC: 1970-01-05 plus whole weeks. */
  private static final long MONDAY = (4 + 7 * 2_000) * D;
  private static final long T0 = MONDAY + 1_000;
  private static final long T1 = MONDAY + 9 * D + 1_000;

  private static long[] records(final Set<String> bots) {
    final Corpus.Issue issue = new Corpus.Issue("a", "P-1", "p", MONDAY - 7 * D, "r", "r", null,
        "Bug", "Major", null, List.of(), List.of());
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("a", T0, "status", "dev", "Open", "In Progress", "1%%0"),
        new Corpus.Event("a", MONDAY + 3 * D, "labels", "ci-bot", null, "x", "2%%0"),
        new Corpus.Event("a", T1, "status", "dev", "In Progress", "Resolved", "3%%0"),
        new Corpus.Event("a", T1, "resolution", "dev", null, "Fixed", "4%%0"));
    final List<Corpus.Comment> comments = List.of(
        new Corpus.Comment("a", "ci-bot", MONDAY + 4 * D, 10, true),
        new Corpus.Comment("a", "dev", MONDAY + 7 * D, 10, false));
    final Corpus corpus = new Corpus(List.of(issue), events, comments, List.of(), List.of(),
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    final Universe universe = Universe.of(corpus);
    final long[] out = BusinessDays.Records.of(corpus, universe, new World(universe, corpus), bots)
        .of("a");
    Arrays.sort(out);
    return out;
  }

  @Test
  @DisplayName("a bot account's events and comments are left out; a person's are kept")
  void botsAreNotRecords() {
    final long[] withBots = records(Set.of());
    final long[] withoutBots = records(Set.of("ci-bot"));
    assertArrayEquals(new long[] {T0, MONDAY + 3 * D, MONDAY + 4 * D, MONDAY + 7 * D, T1, T1},
        withBots);
    assertArrayEquals(new long[] {T0, MONDAY + 7 * D, T1, T1}, withoutBots);
  }
}
