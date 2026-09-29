package app.paper5.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.Row;
import app.paper5.Unit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The past median of a person in a project, and the case the correction handout of 28/09/2026 names:
 * a pull request opened before {@code t0} but merged after it has not taught anybody anything at
 * {@code t0}, because its duration did not exist yet.
 */
class HistoryTest {

  private static final Instant T0 = Instant.parse("2019-01-10T00:00:00Z");

  private static Unit unit(final String id, final Instant opened, final Instant merged) {
    return new Unit(id, "p", "ana", opened, merged);
  }

  private static double pairOf(final History history, final Unit target) {
    final Row row = history.augment(new Row(target.pullRequestId(), Map.of(), Set.of()), target);
    return row.values().getOrDefault(History.PAIR, Double.NaN);
  }

  @Test
  @DisplayName("a pull request opened before t0 and merged after it does not enter the history")
  void mergedAfterT0IsNotHistory() {
    final Unit known = unit("a", T0.minusSeconds(10 * 86_400), T0.minusSeconds(8 * 86_400));
    final Unit pending = unit("b", T0.minusSeconds(5 * 86_400), T0.plusSeconds(20 * 86_400));
    final Unit target = unit("t", T0, T0.plusSeconds(86_400));
    final History history = History.learnedOn(List.of(known, pending, target));

    assertEquals(48.0, pairOf(history, target), 1e-9,
        "only 'a', merged two days after it opened, was known at t0; 'b' was still open");
  }

  @Test
  @DisplayName("merged exactly at t0 is not yet known: the window is strictly before")
  void mergedAtT0IsNotHistory() {
    final Unit atT0 = unit("a", T0.minusSeconds(86_400), T0);
    final Unit target = unit("t", T0, T0.plusSeconds(86_400));
    final History history = History.learnedOn(List.of(atT0, target));

    assertTrue(Double.isNaN(pairOf(history, target)), "no history yet, so the value is absent");
  }

  @Test
  @DisplayName("a row the history was not learned on contributes nothing, even if it is earlier")
  void onlyTheFitSideTeaches() {
    final Unit fitRow = unit("a", T0.minusSeconds(10 * 86_400), T0.minusSeconds(9 * 86_400));
    final Unit scoredEarlier = unit("s", T0.minusSeconds(6 * 86_400), T0.minusSeconds(3 * 86_400));
    final Unit target = unit("t", T0, T0.plusSeconds(86_400));
    final History history = History.learnedOn(List.of(fitRow));

    assertEquals(24.0, pairOf(history, target), 1e-9,
        "the scored row 's' merged before t0 but is not on the fit side, so it is not counted");
    assertTrue(scoredEarlier.mergedAt().isBefore(T0));
  }
}
