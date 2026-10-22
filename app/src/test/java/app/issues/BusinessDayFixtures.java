package app.issues;

import app.study.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic issues of the in-progress similarity chain in business days, as its two steps take
 * them: each issue ends a given count of business days after its start, and the entries reach the
 * steps through {@link BusinessDays#population}, every day boundary at {@value #HOUR}:00 UTC.
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
   * The entries with every day boundary at {@value #HOUR}:00 UTC and, no fixture issue ever sent
   * back to Open or Reopened, active business days those of the whole interval: the target in both variants.
   */
  static List<RowCache.Entry> bounded(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.put(BusinessDays.BOUNDARY, (double) HOUR);
      final double active = BusinessDays.count(
          e.unit().startedAt().toEpochMilli(), e.unit().resolvedAt().toEpochMilli(), HOUR);
      values.put(BusinessDays.ACTIVE, active);
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
