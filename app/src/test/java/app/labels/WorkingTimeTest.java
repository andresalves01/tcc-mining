package app.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkingTimeTest {

  // 2020-01-01 was a Wednesday. Saturday 04/01, Sunday 05/01.
  private static final Instant WEDNESDAY = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  @DisplayName("within the week it equals the wall clock")
  void weekdaysAreUnchanged() {
    assertEquals(2.0,
        WorkingTime.daysBetween(WEDNESDAY, WEDNESDAY.plus(Duration.ofDays(2))), 1e-9);
  }

  @Test
  @DisplayName("crossing the weekend discounts exactly two days")
  void weekendIsRemoved() {
    // Wednesday 01/01 to Wednesday 08/01: seven calendar days, two of them weekend
    assertEquals(5.0,
        WorkingTime.daysBetween(WEDNESDAY, WEDNESDAY.plus(Duration.ofDays(7))), 1e-9);
  }

  @Test
  @DisplayName("an interval entirely within the weekend is worth zero")
  void aWeekendIsNoTimeAtAll() {
    final Instant saturday = Instant.parse("2020-01-04T02:00:00Z");
    final Instant sunday = Instant.parse("2020-01-05T20:00:00Z");

    assertEquals(0.0, WorkingTime.daysBetween(saturday, sunday), 1e-9);
  }

  @Test
  @DisplayName("Friday night to Monday morning is almost no working time")
  void theCaseThisExistsFor() {
    final Instant fridayNight = Instant.parse("2020-01-03T22:00:00Z");
    final Instant mondayMorning = Instant.parse("2020-01-06T08:00:00Z");

    final double wallClock = Duration.between(fridayNight, mondayMorning).toMillis() / 86_400_000.0;
    final double working = WorkingTime.daysBetween(fridayNight, mondayMorning);

    assertEquals(2.4167, wallClock, 1e-3);
    assertEquals(0.4167, working, 1e-3, "two hours of Friday plus eight of Monday");
    assertTrue(working < wallClock);
  }

  @Test
  @DisplayName("a non-positive interval is worth zero instead of negative")
  void nonPositiveIsZero() {
    assertEquals(0.0, WorkingTime.daysBetween(WEDNESDAY, WEDNESDAY), 1e-9);
    assertEquals(0.0, WorkingTime.daysBetween(WEDNESDAY.plus(Duration.ofDays(1)), WEDNESDAY), 1e-9);
  }
}
