package app.issues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The target with recorded activity (ticket 27, item 62): the active business days without the
 * whole working days inside the silent gaps between records.
 */
class RecordedActivityTest {

  /** 2024-01-01 was a Monday. */
  private static long at(final String instant) {
    return Instant.parse(instant).toEpochMilli();
  }

  private static long[] records(final String... instants) {
    final long[] out = new long[instants.length];
    for (int k = 0; k < out.length; k++) {
      out[k] = at(instants[k]);
    }
    return out;
  }

  private static Corpus.Event status(final String instant, final String status) {
    return new Corpus.Event("i", at(instant), "status", "u", null, status, null);
  }

  private static final long MONDAY = at("2024-01-01T10:00:00Z");

  @Test
  @DisplayName("records on the same day give gaps of 0; an overnight gap of 1 day is kept")
  void sameDayAndOvernight() {
    final long end = at("2024-01-03T10:00:00Z");
    final long[] records = records("2024-01-01T11:00:00Z", "2024-01-01T12:00:00Z",
        "2024-01-02T10:00:00Z");
    final int[] gaps = BusinessDays.gaps(List.of(), records, MONDAY, end, 0);
    assertArrayEquals(new int[] {0, 0, 1, 1}, gaps);
    assertEquals(2, BusinessDays.recorded(gaps, BusinessDays.LEAST_SILENT));
    assertEquals(BusinessDays.active(List.of(), MONDAY, end, 0),
        java.util.Arrays.stream(gaps).sum(), "the gaps sum to the active count");
  }

  @Test
  @DisplayName("a silent gap of 3 business days leaves out 2; one of 2 leaves out 1")
  void silentGap() {
    final long end = at("2024-01-04T10:00:00Z");
    final long[] records = records("2024-01-01T12:00:00Z");
    assertArrayEquals(new int[] {0, 3}, BusinessDays.gaps(List.of(), records, MONDAY, end, 0));
    assertEquals(1, BusinessDays.recorded(List.of(), records, MONDAY, end, 0, 2));
    assertEquals(3, BusinessDays.recorded(List.of(), records, MONDAY, end, 0, 4),
        "below a threshold of 4 the gap is kept whole");
    assertEquals(2, BusinessDays.recorded(List.of(), records("2024-01-03T10:00:00Z"), MONDAY, end, 0,
        2), "2 then 1: the gap of 2 keeps 1, the overnight one 1");
  }

  @Test
  @DisplayName("records outside (start, end) or in the time back in Open or Reopened are not "
      + "bounds; a gap crossing a stretch boundary counts only its active days")
  void stretches() {
    // Monday 10:00 In Progress, Tuesday 10:00 back to Open, the next Monday 10:00 In Progress,
    // Wednesday 10:00 resolved: stretches of 1 and 2 business days, active 3
    final List<Corpus.Event> events = List.of(status("2024-01-01T10:00:00Z", "In Progress"),
        status("2024-01-02T10:00:00Z", "Open"), status("2024-01-08T10:00:00Z", "In Progress"),
        status("2024-01-10T10:00:00Z", "Resolved"));
    final long end = at("2024-01-10T10:00:00Z");
    assertEquals(3, BusinessDays.active(events, MONDAY, end, 0));
    // no record: one gap across both stretches, 3 days, silent
    assertArrayEquals(new int[] {3}, BusinessDays.gaps(events, new long[0], MONDAY, end, 0));
    assertEquals(1, BusinessDays.recorded(events, new long[0], MONDAY, end, 0, 2));
    // a comment while back in Open, one before the start and one after the end: no bound
    final long[] outside = records("2024-01-04T10:00:00Z", "2023-12-29T10:00:00Z",
        "2024-01-11T10:00:00Z", "2024-01-02T10:00:00Z");
    assertArrayEquals(new int[] {3}, BusinessDays.gaps(events, outside, MONDAY, end, 0));
    // the move back to In Progress is a record of the second stretch: gaps of 1 and 2
    final long[] restart = records("2024-01-08T10:00:00Z");
    assertArrayEquals(new int[] {1, 2}, BusinessDays.gaps(events, restart, MONDAY, end, 0));
    assertEquals(2, BusinessDays.recorded(events, restart, MONDAY, end, 0, 2));
  }

  @Test
  @DisplayName("never below 1 when the active count is at least 1")
  void neverBelowOne() {
    final long end = at("2024-03-01T10:00:00Z");
    assertEquals(1, BusinessDays.recorded(List.of(), new long[0], MONDAY, end, 0, 2));
    assertEquals(1, BusinessDays.recorded(List.of(), new long[0], MONDAY,
        at("2024-01-02T10:00:00Z"), 0, 2));
    assertEquals(0, BusinessDays.recorded(List.of(), new long[0], MONDAY,
        at("2024-01-01T18:00:00Z"), 0, 2), "0 active days stay 0");
  }

  @Test
  @DisplayName("T = max(2, Q3 + 3 IQR of every gap pooled), type 7; 2 without a gap")
  void threshold() {
    assertEquals(2.0, BusinessDays.threshold(List.of()));
    assertEquals(2.0, BusinessDays.threshold(List.of(new int[] {0, 0, 0, 0, 0, 1}, new int[] {5})),
        "Q1 = Q3 = 0: the fence 0 is below 2");
    // 1..8: Q1 2.75, Q3 6.25, fence 6.25 + 3 * 3.5 = 16.75
    assertEquals(16.75, BusinessDays.threshold(List.of(new int[] {1, 2, 3, 4},
        new int[] {5, 6, 7, 8})), 1e-12);
  }

  @Test
  @DisplayName("each variant's T reads its fitting side alone: a validation or test gap never "
      + "moves it")
  void thresholds() {
    final Map<String, String[]> parts = Map.of("f", new String[] {"TRAIN", "TRAIN"},
        "v", new String[] {"TRAIN", "TEST"}, "t", new String[] {"TEST", ""});
    final Map<String, int[]> gaps = new HashMap<>();
    gaps.put("f", new int[] {1, 2, 3, 4});
    gaps.put("v", new int[] {5, 6, 7, 8});
    gaps.put("t", new int[] {100, 200});
    final Map<Variant, Double> base = BusinessDays.thresholds(gaps, parts);
    assertEquals(BusinessDays.threshold(List.of(gaps.get("f"))), base.get(Variant.INNER));
    assertEquals(BusinessDays.threshold(List.of(gaps.get("f"), gaps.get("v"))),
        base.get(Variant.OUTER));
    gaps.put("v", new int[] {500, 900});
    gaps.put("t", new int[] {0});
    final Map<Variant, Double> moved = BusinessDays.thresholds(gaps, parts);
    assertEquals(base.get(Variant.INNER), moved.get(Variant.INNER),
        "the inner validation never moves the inner T");
    gaps.put("t", new int[] {1000, 2000, 3000});
    assertEquals(moved.get(Variant.OUTER), BusinessDays.thresholds(gaps, parts)
        .get(Variant.OUTER), "the outer test never moves the outer T");
    assertTrue(moved.get(Variant.OUTER) > base.get(Variant.OUTER),
        "the inner validation is on the outer training side");
  }
}
