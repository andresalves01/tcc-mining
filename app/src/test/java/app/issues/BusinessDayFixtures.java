package app.issues;

import app.study.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic issues of the study in business days, as its steps take them: each issue ends a given
 * count of business days after its start, carries that count as the first-commit target, and
 * reaches the steps through {@link BusinessDays#population}, every day boundary at {@value
 * #HOUR}:00 UTC.
 */
final class BusinessDayFixtures {

  /** The day boundary of every fixture issue. */
  static final int HOUR = 4;

  private BusinessDayFixtures() {}

  /**
   * The first instant at the same time of day as {@code start}, whole days later, that is {@code
   * days} business days after it at the boundary {@value #HOUR}:00 UTC.
   */
  static Instant after(final Instant start, final int days) {
    Instant end = start;
    while (BusinessDays.count(start.toEpochMilli(), end.toEpochMilli(), HOUR) < days) {
      end = end.plus(Duration.ofDays(1));
    }
    return end;
  }

  /** The business days a fixture gives to an issue drawn as {@code hours}: a day per 24 hours. */
  static int days(final double hours) {
    return (int) Math.ceil(hours / 24);
  }

  /**
   * The entries with every day boundary at {@value #HOUR}:00 UTC and the target the business days
   * of the whole interval, at least one.
   */
  static List<RowCache.Entry> bounded(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.put(FirstCommit.BOUNDARY_COLUMN, (double) HOUR);
      final double days = BusinessDays.count(
          e.unit().startedAt().toEpochMilli(), e.unit().resolvedAt().toEpochMilli(), HOUR);
      values.put(FirstCommit.TARGET_COLUMN, Math.max(1.0, days));
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, e.row().absent()),
          e.parts()));
    }
    return out;
  }

  /** The entries as the chain's steps take them: bounded, then through the population. */
  static List<RowCache.Entry> counted(final List<RowCache.Entry> entries) {
    return BusinessDays.population(bounded(entries), Augmentation.Variant.INNER).entries();
  }
}
