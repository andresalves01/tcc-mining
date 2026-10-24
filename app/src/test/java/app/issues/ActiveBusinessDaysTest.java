package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The active business days of an issue, without the time it was back in Open or Reopened. */
class ActiveBusinessDaysTest {

  /** 2024-01-01 was a Monday. */
  private static long at(final String instant) {
    return Instant.parse(instant).toEpochMilli();
  }

  private static Corpus.Event status(final String instant, final String status) {
    return new Corpus.Event("i", at(instant), "status", "u", null, status, null);
  }

  private static final long MONDAY = at("2024-01-01T10:00:00Z");

  @Test
  @DisplayName("the active business days leave out the time back in Open or Reopened")
  void stretches() {
    // Monday 10:00 In Progress, Tuesday 10:00 back to Open, the next Monday 10:00 In Progress,
    // Wednesday 10:00 resolved: stretches of 1 and 2 business days, active 3
    final List<Corpus.Event> events = List.of(status("2024-01-01T10:00:00Z", "In Progress"),
        status("2024-01-02T10:00:00Z", "Open"), status("2024-01-08T10:00:00Z", "In Progress"),
        status("2024-01-10T10:00:00Z", "Resolved"));
    assertEquals(3, BusinessDays.active(events, MONDAY, at("2024-01-10T10:00:00Z"), 0));
  }
}
