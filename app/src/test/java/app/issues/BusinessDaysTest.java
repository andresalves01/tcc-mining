package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whole business days between two instants, dates cut at a boundary hour. */
class BusinessDaysTest {

  private static final Augmentation.Variant INNER = Augmentation.Variant.INNER;

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

  /** One status change of issue "i" to {@code status}, at {@code instant}. */
  private static Corpus.Event status(final String instant, final String status) {
    return new Corpus.Event("i", at(instant), "status", "u", null, status, null);
  }

  @Test
  @DisplayName("an issue never sent back to Open or Reopened keeps the whole interval's count")
  void activeWithoutStoppedPeriod() {
    final List<Corpus.Event> events = List.of(status("2024-01-04T10:00:00Z", "In Progress"),
        new Corpus.Event("i", at("2024-01-05T10:00:00Z"), "assignee", "u", "a", "b", null),
        status("2024-01-10T10:00:00Z", "Resolved"));
    assertEquals(4, BusinessDays.active(events, at("2024-01-04T10:00:00Z"),
        at("2024-01-10T10:00:00Z"), 4));
    assertEquals(4, BusinessDays.active(List.of(), at("2024-01-04T10:00:00Z"),
        at("2024-01-10T10:00:00Z"), 4), "no event at all");
  }

  @Test
  @DisplayName("a stopped period crossing a weekend: its dates, the weekend's and the Monday's, "
      + "are not counted; each active stretch is counted at the issue's boundary and summed")
  void activeAcrossWeekend() {
    // Thursday 10:00 In Progress, Friday 10:00 back to Open, Tuesday 10:00 In Progress again,
    // Wednesday 10:00 resolved: the whole interval reaches Friday, Monday, Tuesday and Wednesday,
    // 4; active, Thursday to Friday (1) and Tuesday to Wednesday (1), 2
    final List<Corpus.Event> events = List.of(status("2024-01-04T10:00:00Z", "In Progress"),
        status("2024-01-05T10:00:00Z", "Open"), status("2024-01-09T10:00:00Z", "In Progress"),
        status("2024-01-10T10:00:00Z", "Resolved"));
    final long t0 = at("2024-01-04T10:00:00Z");
    final long t1 = at("2024-01-10T10:00:00Z");
    assertEquals(4, BusinessDays.count(t0, t1, 4));
    assertEquals(2, BusinessDays.active(events, t0, t1, 4));
    // at a boundary of 12:00 every instant is on the previous date: Wednesday's to Thursday's,
    // then Monday's to Tuesday's, 2 again
    assertEquals(2, BusinessDays.active(events, t0, t1, 12));
  }

  @Test
  @DisplayName("status changes sharing an instant follow the changelog's order: Open then In "
      + "Progress stops nothing, In Progress then Open stops until the next change")
  void activeSameInstant() {
    final long t0 = at("2024-01-04T10:00:00Z");
    final long t1 = at("2024-01-10T10:00:00Z");
    final List<Corpus.Event> reopenedAtOnce = List.of(status("2024-01-04T10:00:00Z",
        "In Progress"), status("2024-01-05T10:00:00Z", "Open"),
        status("2024-01-05T10:00:00Z", "In Progress"), status("2024-01-10T10:00:00Z",
        "Resolved"));
    assertEquals(BusinessDays.count(t0, t1, 4), BusinessDays.active(reopenedAtOnce, t0, t1, 4));
    final List<Corpus.Event> stoppedAtOnce = List.of(status("2024-01-04T10:00:00Z",
        "In Progress"), status("2024-01-05T10:00:00Z", "In Progress"),
        status("2024-01-05T10:00:00Z", "Open"), status("2024-01-09T10:00:00Z", "In Progress"),
        status("2024-01-10T10:00:00Z", "Resolved"));
    assertEquals(2, BusinessDays.active(stoppedAtOnce, t0, t1, 4));
  }

  @Test
  @DisplayName("a stopped period is clipped to [first In Progress, first resolution): what lies "
      + "before the start or after the end takes nothing away")
  void activeClipped() {
    // sent back to Reopened on Monday, before the start on Tuesday, until Thursday 10:00: only
    // Tuesday 10:00 to Thursday 10:00 is stopped; Thursday to the next Monday counts 2
    final List<Corpus.Event> before = List.of(status("2024-01-01T10:00:00Z", "Reopened"),
        status("2024-01-04T10:00:00Z", "In Progress"),
        status("2024-01-08T10:00:00Z", "Resolved"));
    assertEquals(2, BusinessDays.active(before, at("2024-01-02T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
    // sent back to Open on Friday 10:00, the next status change long after the end on Monday
    // 10:00: Tuesday 10:00 to Friday 10:00 counts 3, the rest is stopped
    final List<Corpus.Event> after = List.of(status("2024-01-02T10:00:00Z", "In Progress"),
        status("2024-01-05T10:00:00Z", "Open"), status("2024-01-20T10:00:00Z", "Resolved"));
    assertEquals(3, BusinessDays.active(after, at("2024-01-02T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
    // a last change to Open with no status change after it: stopped up to the end
    assertEquals(3, BusinessDays.active(after.subList(0, 2), at("2024-01-02T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
    // stopped periods wholly before the start or after the end change nothing
    final List<Corpus.Event> outside = List.of(status("2023-12-01T10:00:00Z", "Open"),
        status("2023-12-02T10:00:00Z", "In Progress"), status("2024-02-01T10:00:00Z", "Open"),
        status("2024-02-02T10:00:00Z", "Closed"));
    assertEquals(5, BusinessDays.active(outside, at("2024-01-01T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
  }

  @Test
  @DisplayName("back-to-back stopped periods, Open then Reopened, stop the issue from the first "
      + "until the next change to another status")
  void activeBackToBack() {
    // Monday 10:00 In Progress, Tuesday 10:00 Open, Wednesday 10:00 Reopened, Friday 10:00 In
    // Progress, next Monday 10:00 resolved: Monday to Tuesday (1) and Friday to Monday (1)
    final List<Corpus.Event> events = List.of(status("2024-01-01T10:00:00Z", "In Progress"),
        status("2024-01-02T10:00:00Z", "Open"), status("2024-01-03T10:00:00Z", "Reopened"),
        status("2024-01-05T10:00:00Z", "In Progress"),
        status("2024-01-08T10:00:00Z", "Resolved"));
    assertEquals(2, BusinessDays.active(events, at("2024-01-01T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
  }

  @Test
  @DisplayName("Patch Available, waiting for review, is not removed; nor is another field's change "
      + "to a value named Open")
  void activeKeepsPatchAvailable() {
    final List<Corpus.Event> events = List.of(status("2024-01-01T10:00:00Z", "In Progress"),
        status("2024-01-02T10:00:00Z", "Patch Available"),
        new Corpus.Event("i", at("2024-01-03T10:00:00Z"), "resolution", "u", null, "Open", null),
        status("2024-01-05T10:00:00Z", "In Progress"),
        status("2024-01-08T10:00:00Z", "Resolved"));
    assertEquals(5, BusinessDays.active(events, at("2024-01-01T10:00:00Z"),
        at("2024-01-08T10:00:00Z"), 4));
  }

  private final List<RowCache.Entry> entries = new ArrayList<>();

  /**
   * An issue clicked at {@code start}, resolved at {@code end}, its boundary at {@code hour}, never
   * sent back: its active business days, in the matrix's column, those of the whole interval.
   */
  private void entry(final String id, final String outer, final String inner, final String start,
      final String end, final Double hour) {
    entry(id, outer, inner, start, end, hour, hour == null ? null
        : (double) BusinessDays.count(at(start), at(end), hour.intValue()));
  }

  /** The same with {@code active} business days in the matrix's column, none when null. */
  private void entry(final String id, final String outer, final String inner, final String start,
      final String end, final Double hour, final Double active) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant s = Instant.parse(start);
    final Map<String, Double> values = new HashMap<>();
    if (hour != null) {
      values.put(BusinessDays.BOUNDARY, hour);
    }
    if (active != null) {
      values.put(BusinessDays.ACTIVE, active);
      // no silent day: the target is the active count in both variants
      for (final Augmentation.Variant v : Augmentation.Variant.values()) {
        values.put(BusinessDays.recorded(v), active);
        values.put(BusinessDays.threshold(v), BusinessDays.LEAST_SILENT);
      }
    }
    entries.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p", "r", "e", s,
        Instant.parse(end), "Fixed", false, s), new Row(id, values, Set.of()), parts));
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
    final BusinessDays.Population p = BusinessDays.population(entries, INNER);
    assertEquals(List.of("fit-one", "val-one", "crossing-ten", "test-five"),
        p.entries().stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of(1.0, 1.0, 10.0, 5.0),
        p.entries().stream().map(e -> e.unit().duration()).toList());
    assertEquals(new ProjectSplit.Sizes(6, 2, 2, 2), p.all());
    assertEquals(new ProjectSplit.Sizes(3, 1, 1, 1), p.kept());
    assertEquals(entries.get(0).row(), p.entries().get(0).row());
    assertEquals(entries.get(0).parts(), p.entries().get(0).parts());
  }

  @Test
  @DisplayName("the population reads the active business days: time back in Open or Reopened "
      + "taken out can bring an issue under one business day, and out of every set")
  void populationReadsActive() {
    // Monday 10:00 to the next Monday 10:00, five business days over the whole interval
    entry("fit-two", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, 2.0);
    entry("fit-none", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0,
        0.0);
    entry("val-five", "TRAIN", "TEST", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0);
    entry("val-none", "TRAIN", "TEST", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, 0.0);
    entry("test-none", "TEST", "", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, 0.0);
    final BusinessDays.Population p = BusinessDays.population(entries, INNER);
    assertEquals(List.of("fit-two", "val-five"),
        p.entries().stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of(2, 5), p.entries().stream().map(e -> e.unit().businessDays()).toList());
    assertEquals(new ProjectSplit.Sizes(4, 1, 2, 2), p.all());
    assertEquals(new ProjectSplit.Sizes(2, 0, 1, 1), p.kept());
  }

  /** Entry {@code k}'s target and threshold in {@code v}'s columns, {@code days} null for none. */
  private void target(final int k, final Augmentation.Variant v, final Double days,
      final double threshold) {
    final RowCache.Entry e = entries.get(k);
    final Map<String, Double> values = new HashMap<>(e.row().values());
    values.remove(BusinessDays.recorded(v));
    if (days != null) {
      values.put(BusinessDays.recorded(v), days);
    }
    values.put(BusinessDays.threshold(v), threshold);
    entries.set(k, new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, Set.of()),
        e.parts()));
  }

  @Test
  @DisplayName("the restored duration retains silent days in both variants and keeps the split")
  void populationReadsActiveDays() {
    for (final String[] e : new String[][] {{"fit", "TRAIN", "TRAIN"}, {"val", "TRAIN", "TEST"},
        {"test", "TEST", ""}, {"none", "TRAIN", "TRAIN"}}) {
      entry(e[0], e[1], e[2], "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0,
          "none".equals(e[0]) ? 0.0 : 5.0);
    }
    for (int k = 0; k < 4; k++) {
      target(k, INNER, k == 3 ? 0.0 : 1.0, 2);
      target(k, Augmentation.Variant.OUTER, k == 3 ? 0.0 : 2.0, 3);
    }
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final var p = BusinessDays.population(entries, v);
      assertEquals(List.of("fit", "val", "test"),
          p.entries().stream().map(e -> e.unit().issueId()).toList());
      assertEquals(List.of(5.0, 5.0, 5.0),
          p.entries().stream().map(e -> e.unit().duration()).toList());
      assertEquals(5.0, TailCut.fence(p.entries().stream().map(RowCache.Entry::unit).toList(), 3));
      assertEquals(new ProjectSplit.Sizes(3, 1, 2, 1), p.all());
      assertEquals(new ProjectSplit.Sizes(2, 1, 1, 1), p.kept());
    }
  }

  @Test
  @DisplayName("the restored target needs no recorded-activity count or gap threshold")
  void onlyStatusAdjustedDaysRequired() {
    entry("a", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, 5.0);
    final var e = entries.getFirst();
    final var values = new HashMap<>(e.row().values());
    BusinessDays.LEGACY_RECORDED_COLUMNS.forEach(values::remove);
    entries.set(0, new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, Set.of()), e.parts()));
    assertEquals(5.0, BusinessDays.population(entries, INNER).entries().getFirst().unit().duration());
  }

  @Test
  @DisplayName("a matrix without the active business days, written before ticket 18, is refused; "
      + "so is a count that is not a whole number from 0 to the whole interval's")
  void noActive() {
    entry("old", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, null);
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> BusinessDays.population(entries, INNER));
    assertTrue(e.getMessage().contains(BusinessDays.ACTIVE), e.getMessage());
    for (final double wrong : new double[] {6, -1, 2.5}) {
      entries.clear();
      entry("x", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", 4.0, wrong);
      assertThrows(IllegalArgumentException.class, () -> BusinessDays.population(entries, INNER),
          Double.toString(wrong));
    }
  }

  @Test
  @DisplayName("a matrix's columns: refused without the day boundary or the active business "
      + "days, each named, with the stage to run again")
  void unwritten() {
    final List<String> all = new ArrayList<>(BusinessDays.COLUMNS);
    all.add("x");
    assertEquals(null, BusinessDays.unwritten(all));
    all.addAll(BusinessDays.LEGACY_RECORDED_COLUMNS);
    final String retired = BusinessDays.unwritten(all);
    assertTrue(retired.contains("recorded-activity") && retired.contains("regenerated")
        && retired.contains("issues-in-progress-similarity"), retired);
    final String old = BusinessDays.unwritten(List.of("x", BusinessDays.BOUNDARY));
    assertTrue(old.contains(BusinessDays.ACTIVE) && !old.contains(BusinessDays.BOUNDARY)
        && old.contains("issues-in-progress-similarity"), old);
    final String older = BusinessDays.unwritten(List.of("x"));
    assertTrue(older.contains(BusinessDays.ACTIVE) && older.contains(BusinessDays.BOUNDARY),
        older);
  }

  @Test
  @DisplayName("a matrix without the day boundary, written before ticket 14, is refused")
  void noBoundary() {
    entry("old", "TRAIN", "TRAIN", "2024-01-01T10:00:00Z", "2024-01-08T10:00:00Z", null);
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> BusinessDays.population(entries, INNER));
    assertTrue(e.getMessage().contains(BusinessDays.BOUNDARY), e.getMessage());
  }

  @Test
  @DisplayName("the error by band of the true business days, cut at the quartiles of the fitting "
      + "rows (ticket 24); the bands only group the rows, nothing is clipped")
  void bands() {
    // fitting rows whose quartiles, type 7, are 1, 3 and 10
    final double[] tops = TailCut.quartiles(new double[] {10, 1, 3, 1, 10, 1, 10});
    assertEquals(List.of(1.0, 3.0, 10.0), List.of(tops[0], tops[1], tops[2]));
    final double[] observed = {1, 1, 2, 3, 4, 10, 11, 60};
    final double[] predicted = {1, 3, 2, 1, 4, 4, 20, 5};
    final List<app.study.model.Breakdown.Band> b =
        BusinessDays.bands(predicted, observed, tops);
    assertEquals(List.of("<=1", "(1, 3]", "(3, 10]", ">10"),
        b.stream().map(app.study.model.Breakdown.Band::label).toList());
    assertEquals(List.of(2, 2, 2, 2), b.stream().map(x -> x.n()).toList());
    assertEquals(new app.study.model.Breakdown.Band("<=1", 2, 1.0, 1.0, 1.0), b.get(0));
    assertEquals(new app.study.model.Breakdown.Band("(1, 3]", 2, 2.5, 1.0, 1.0), b.get(1));
    assertEquals(new app.study.model.Breakdown.Band("(3, 10]", 2, 7.0, 3.0, 3.0), b.get(2));
    assertEquals(new app.study.model.Breakdown.Band(">10", 2, 35.5, 32.0, 32.0), b.get(3),
        "60 against 5 counts 55, unclipped");
    // quartiles between whole days keep their fraction in the labels
    assertEquals(List.of("<=1.75", "(1.75, 2.5]", "(2.5, 3.25]", ">3.25"), List.of(
        BusinessDays.bandLabels(TailCut.quartiles(new double[] {4, 3, 2, 1}))));
  }
}
