package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whole business days between two instants, dates cut at a boundary hour. */
class BusinessDaysTest {

  /** 2024-01-01 was a Monday. */
  private static long at(final String instant) {
    return Instant.parse(instant).toEpochMilli();
  }

  @Test
  @DisplayName("same date 0; Friday to Monday 1; Friday to Saturday 0; Saturday to Monday 1")
  void fixedCases() {
    assertEquals(0, BusinessDays.count(at("2024-01-03T09:00:00Z"), at("2024-01-03T18:00:00Z"), 0));
    assertEquals(1, BusinessDays.count(at("2024-01-05T15:00:00Z"), at("2024-01-08T10:00:00Z"), 0));
    assertEquals(0, BusinessDays.count(at("2024-01-05T15:00:00Z"), at("2024-01-06T10:00:00Z"), 0));
    assertEquals(1, BusinessDays.count(at("2024-01-06T15:00:00Z"), at("2024-01-08T10:00:00Z"), 0));
    assertEquals(0, BusinessDays.count(at("2024-01-06T15:00:00Z"), at("2024-01-07T10:00:00Z"), 0),
        "Saturday to Sunday reaches no weekday");
    assertEquals(5, BusinessDays.count(at("2024-01-01T10:00:00Z"), at("2024-01-08T10:00:00Z"), 0),
        "Monday to the next Monday");
    assertEquals(10, BusinessDays.count(at("2024-01-03T10:00:00Z"), at("2024-01-17T11:00:00Z"), 0),
        "two whole weeks");
  }

  @Test
  @DisplayName("a boundary at H other than midnight moves where dates are cut")
  void boundary() {
    // Monday 22:00 to Tuesday 02:00 UTC: two dates at midnight, one date when they turn at 04:00
    final long start = at("2024-01-01T22:00:00Z");
    final long end = at("2024-01-02T02:00:00Z");
    assertEquals(1, BusinessDays.count(start, end, 0));
    assertEquals(0, BusinessDays.count(start, end, 4));
    // Friday 02:00 UTC belongs to Thursday's date when dates turn at 04:00, so Friday is reached
    assertEquals(1, BusinessDays.count(at("2024-01-05T02:00:00Z"), at("2024-01-05T10:00:00Z"), 4));
    // Monday 03:00 UTC still belongs to Sunday's date at 04:00: a weekend start, Monday not yet
    assertEquals(0, BusinessDays.count(at("2024-01-06T12:00:00Z"), at("2024-01-08T03:00:00Z"), 4));
  }

  @Test
  @DisplayName("an end exactly at the boundary is on the next date; a millisecond before is not")
  void endAtBoundary() {
    final long start = at("2024-01-01T10:00:00Z");
    assertEquals(1, BusinessDays.count(start, at("2024-01-02T04:00:00Z"), 4));
    assertEquals(0, BusinessDays.count(start, at("2024-01-02T04:00:00Z") - 1, 4));
  }

  private final List<RowCache.Entry> entries = new ArrayList<>();

  /** An issue clicked at {@code start}, resolved at {@code end}, its boundary at {@code hour}. */
  private void entry(final String id, final String outer, final String inner, final String start,
      final String end, final Double hour) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant s = Instant.parse(start);
    entries.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p", "r", "e", s,
        Instant.parse(end), "Fixed", false, s),
        new Row(id, hour == null ? Map.of() : Map.of(BusinessDays.BOUNDARY, hour), Set.of()),
        parts));
  }

  @Test
  @DisplayName("the population: each issue's business days at its own boundary; issues of 0 "
      + "business days dropped from inner fitting, inner validation, outer training and outer test")
  void population() {
    // Monday 22:00 to Tuesday 02:00: one business day at midnight, none at 04:00
    entry("fit-one", "TRAIN", "TRAIN", "2024-01-01T22:00:00Z", "2024-01-02T02:00:00Z", 0.0);
    entry("fit-zero", "TRAIN", "TRAIN", "2024-01-01T22:00:00Z", "2024-01-02T02:00:00Z", 4.0);
    entry("val-zero", "TRAIN", "TEST", "2024-01-05T15:00:00Z", "2024-01-06T10:00:00Z", 4.0);
    entry("val-one", "TRAIN", "TEST", "2024-01-05T15:00:00Z", "2024-01-08T10:00:00Z", 4.0);
    entry("crossing-zero", "TRAIN", "CROSSES", "2024-01-03T09:00:00Z", "2024-01-03T18:00:00Z",
        4.0);
    entry("crossing-ten", "TRAIN", "CROSSES", "2024-01-03T10:00:00Z", "2024-01-17T11:00:00Z",
        4.0);
    entry("test-zero", "TEST", "", "2024-01-03T09:00:00Z", "2024-01-03T18:00:00Z", 4.0);
    entry("test-five", "TEST", "", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0);
    final BusinessDays.Population p = BusinessDays.population(entries);
    assertEquals(List.of("fit-one", "val-one", "crossing-ten", "test-five"),
        p.entries().stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of(1.0, 1.0, 10.0, 5.0),
        p.entries().stream().map(e -> e.unit().duration()).toList());
    assertEquals(new RegistrationInProgress.Sizes(6, 2, 2, 2), p.all());
    assertEquals(new RegistrationInProgress.Sizes(3, 1, 1, 1), p.kept());
    assertEquals(entries.get(0).row(), p.entries().get(0).row());
    assertEquals(entries.get(0).parts(), p.entries().get(0).parts());
  }

  @Test
  @DisplayName("a matrix without the day boundary, written before ticket 14, is refused")
  void noBoundary() {
    entry("old", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", null);
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> BusinessDays.population(entries));
    assertTrue(e.getMessage().contains(BusinessDays.BOUNDARY), e.getMessage());
  }

  @Test
  @DisplayName("the error by band of the true business days: 1, 2-3, 4-10, 11+; the bands only "
      + "group the rows, nothing is clipped")
  void bands() {
    final double[] observed = {1, 1, 2, 3, 4, 10, 11, 60};
    final double[] predicted = {1, 3, 2, 1, 4, 4, 20, 5};
    final List<app.study.model.Breakdown.Band> b = BusinessDays.bands(predicted, observed);
    assertEquals(List.of("1", "2-3", "4-10", "11+"),
        b.stream().map(app.study.model.Breakdown.Band::label).toList());
    assertEquals(List.of(2, 2, 2, 2), b.stream().map(x -> x.n()).toList());
    assertEquals(new app.study.model.Breakdown.Band("1", 2, 1.0, 1.0, 1.0), b.get(0));
    assertEquals(new app.study.model.Breakdown.Band("2-3", 2, 2.5, 1.0, 1.0), b.get(1));
    assertEquals(new app.study.model.Breakdown.Band("4-10", 2, 7.0, 3.0, 3.0), b.get(2));
    assertEquals(new app.study.model.Breakdown.Band("11+", 2, 35.5, 32.0, 32.0), b.get(3),
        "60 against 5 counts 55, unclipped");
  }
}
