package app.labels;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Duration with weekends discounted.
 *
 * <p>Exists because a good part of the variation we were trying to explain is not about any
 * person. A request opened on a Friday night and merged on Monday morning takes two and a half days
 * of wall-clock time and almost no working time, and no predictor in this study can know the
 * difference. Saturday and Sunday are 2/7 of the calendar, so they are the largest single slice of
 * that noise that can be removed without inventing anything.
 *
 * <p><b>What this measure does not do, and why.</b> It does not discount nights. Discounting them
 * would need the time zone of whoever worked, and the corpus does not carry that on the pull
 * request -- {@code created_at} is UTC, with no offset. Across 98 open-source projects with people
 * spread around the world, assuming a single set of business hours would trade a known bias for an
 * invented one. Weekends have the same problem to a lesser degree, because the rest calendar is not
 * universal, but Saturday and Sunday coincide almost everywhere this corpus comes from.
 *
 * <p>The measure is continuous, not rounded to days: an interval within the same working day stays
 * equal to the wall-clock one, and only shrinks when it crosses a weekend.
 */
public final class WorkingTime {

  private static final double DAY_MILLIS = 86_400_000.0;

  private WorkingTime() {}

  /** Fractional working days between two instants, discounting Saturdays and Sundays. */
  public static double daysBetween(final Instant from, final Instant to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    final long total = Duration.between(from, to).toMillis();
    return (total - weekendMillis(from, to)) / DAY_MILLIS;
  }

  /** How many milliseconds of the interval fall on a Saturday or Sunday, in UTC. */
  private static long weekendMillis(final Instant from, final Instant to) {
    long weekend = 0;
    ZonedDateTime cursor = from.atZone(ZoneOffset.UTC);
    final ZonedDateTime end = to.atZone(ZoneOffset.UTC);
    while (cursor.isBefore(end)) {
      final ZonedDateTime nextMidnight = cursor.toLocalDate().plusDays(1)
          .atStartOfDay(ZoneOffset.UTC);
      final ZonedDateTime sliceEnd = nextMidnight.isBefore(end) ? nextMidnight : end;
      final DayOfWeek day = cursor.getDayOfWeek();
      if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
        weekend += Duration.between(cursor, sliceEnd).toMillis();
      }
      cursor = sliceEnd;
    }
    return weekend;
  }
}
