package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The population filter's boundaries, pinned.
 *
 * <p>The thresholds are pre-registered, so a change to them has to break a test rather than pass
 * quietly: the whole point of fixing them ahead of the run is that they cannot move afterwards.
 */
class PopulationTest {

  private static Observation at(final double postCommitDays) {
    return new Observation("i", "p", "dev", 1.0, new double[] {1.0}, postCommitDays);
  }

  @Test
  @DisplayName("the unrestricted population keeps everything, including a missing interval")
  void allKeepsEverything() {
    final Population all = Population.all();
    assertFalse(all.restricts());
    assertTrue(all.admits(at(-10)));
    assertTrue(all.admits(at(0)));
    assertTrue(all.admits(at(500)));
    assertTrue(all.admits(at(Double.NaN)));
    assertFalse(all.refusesForMissing(at(Double.NaN)));
  }

  @Test
  @DisplayName("postdated means strictly before: a resolution at the commit instant stays")
  void postdatedBoundaryIsInclusiveAtZero() {
    final Population p = Population.withoutPostdated();
    assertTrue(p.restricts());
    assertFalse(p.admits(at(-1e-9)));
    assertTrue(p.admits(at(0)));
    assertTrue(p.admits(at(1e-9)));
  }

  @Test
  @DisplayName("the primary arm cuts at exactly one minute, and keeps the minute itself")
  void minuteBoundary() {
    final Population p = Population.withoutImmediateByMinute();
    assertEquals(1.0 / 1440.0, p.minPostCommitDays());
    assertFalse(p.admits(at(0)));
    assertFalse(p.admits(at(59.0 / 86400.0)));
    assertTrue(p.admits(at(1.0 / 1440.0)));
    assertTrue(p.admits(at(1.0 / 24.0)));
  }

  @Test
  @DisplayName("the sensitivity arm cuts at exactly one hour")
  void hourBoundary() {
    final Population p = Population.withoutImmediateByHour();
    assertEquals(1.0 / 24.0, p.minPostCommitDays());
    assertFalse(p.admits(at(59.0 / 1440.0)));
    assertTrue(p.admits(at(1.0 / 24.0)));
  }

  @Test
  @DisplayName("a missing interval is refused where it matters, and the refusal is countable")
  void missingIntervalIsRefusedNotScoredAsZero() {
    for (final Population p : new Population[] {Population.withoutPostdated(),
        Population.withoutImmediateByMinute(), Population.withoutImmediateByHour()}) {
      assertFalse(p.admits(at(Double.NaN)), p.name() + " must not admit an unknown interval");
      assertTrue(p.refusesForMissing(at(Double.NaN)), p.name() + " must count the refusal");
      assertFalse(p.refusesForMissing(at(-5)), "a postdated row is refused on its value");
    }
  }

  @Test
  @DisplayName("the filter never touches the predictors it selects on")
  void filteringDoesNotAlterTheDesignMatrix() {
    // Population selects the sample. If the interval ever reached values[] it would be leakage:
    // it is measured after the work, and could not be known when the task was created.
    final double[] values = {1.0, 2.0, 3.0};
    final Observation row = new Observation("i", "p", "dev", 1.0, values, -4.0);
    Population.withoutImmediateByHour().admits(row);
    assertEquals(3, row.values().length);
    assertEquals(1.0, row.values()[0]);
    assertEquals(2.0, row.values()[1]);
    assertEquals(3.0, row.values()[2]);
  }
}
