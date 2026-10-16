package app.issues;

import app.study.model.Breakdown;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The in-progress similarity chain's target (tickets 14 and 18): whole business days from the first
 * In Progress to the first resolution, without the time the issue was sent back to Open or
 * Reopened in between.
 *
 * <p><b>Counting.</b> Each date runs from the issue's day boundary, an hour of the day in UTC
 * ({@link DayBoundaries}), to the same hour the next day. The count is the number of
 * Monday-to-Friday dates reached after the start's date, up to and including the end's date: the same date is 0,
 * Friday to Monday is 1, Friday to Saturday is 0, and a start on a Saturday or Sunday belongs to
 * that weekend date, so Saturday to Monday is 1. An end exactly at the boundary is on the next
 * date. Holidays are ignored: the data hold no country per person.
 *
 * <p><b>Active time.</b> Time nobody was working on the issue is not counted: the periods it spent
 * back in Open or Reopened inside the interval are cut out, and the business days of each remaining
 * stretch are summed ({@link #active}). Time in Patch Available, waiting for review, stays: review
 * is part of getting the change done. Reopenings after the first resolution never counted. The
 * similarity stage, which reads the changelog, writes each issue's count to the matrix ({@link
 * #ACTIVE}); every later step reads it from there.
 *
 * <p><b>Population.</b> Only issues of at least one business day are studied: the model is
 * conditional, predicting how long an issue takes given that it lasts past its start's working
 * date. The issues of 0 business days are dropped after the split and crossing-row rules, which
 * stay those of the registered study; they enter no fitting, validation, training or test set and
 * no history.
 */
public final class BusinessDays {

  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;

  /** The matrix column holding each issue's day boundary, an hour of the day in UTC. */
  public static final String BOUNDARY = "day_boundary_hour";

  /**
   * The matrix column holding each issue's active business days ({@link #active}), the target;
   * written by the similarity stage from the changelog.
   */
  public static final String ACTIVE = "active_business_days";

  /** The matrix columns the target is read from, in the order the similarity stage writes them. */
  public static final List<String> COLUMNS = List.of(BOUNDARY, ACTIVE);

  /**
   * Why a matrix with {@code columns} cannot give the target, null when it can: one without the
   * day boundary was written before business days (ticket 14), one without the active business
   * days before the time back in Open or Reopened was taken out (ticket 18).
   */
  public static String unwritten(final Collection<String> columns) {
    final List<String> missing = COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
    return missing.isEmpty() ? null : "the matrix has no " + String.join(", ", missing)
        + "; it was written before business days or before the time back in Open or Reopened "
        + "was taken out of them, run issues-in-progress-similarity again";
  }

  /**
   * The statuses an issue is sent back to when nobody is working on it: the time from a change to
   * one of them until the next status change is not counted ({@link #active}).
   */
  public static final Set<String> STOPPED = Set.of("Open", "Reopened");

  /** What the target is, in words for the help text and the run records. */
  public static final String TARGET = "whole business days (Monday to Friday dates reached) from "
      + "the first In Progress to the first resolution, dates cut at each issue's day boundary, "
      + "without the time the issue was sent back to Open or Reopened in between (each period "
      + "from a status change to either until the next status change, clipped to the interval; "
      + "the business days of the remaining stretches summed; time in Patch Available kept): "
      + "time nobody was working on the issue is not counted; fitted as log1p, predictions back "
      + "with expm1 held at zero, never rounded or clipped";

  /** Who is studied, in words for the help text and the run records. */
  public static final String POPULATION = "issues of at least one business day: the model "
      + "predicts how long an issue takes given that it lasts past its start's working date; "
      + "issues of 0 business days are dropped after the split and crossing-row rules";

  /** What the count leaves out, in words for the help text and the run records. */
  public static final String HOLIDAYS = "holidays ignored: the data hold no country per person";

  /**
   * The issues of at least one business day, each unit carrying its count.
   *
   * @param entries those issues, in the given order, rows and splits as they were
   * @param all the four split sizes before the drop
   * @param kept the four split sizes after it
   */
  public record Population(List<RowCache.Entry> entries, RegistrationInProgress.Sizes all,
      RegistrationInProgress.Sizes kept) {}

  private BusinessDays() {}

  /**
   * Every issue's active business days, read from its {@link #ACTIVE} column, and the issues of at
   * least one; refuses a row without the boundary, as a matrix written before ticket 14 has, a row
   * without the active count, as one written before ticket 18 has, and a count that is not a whole
   * number from 0 to the business days of the whole interval at the row's boundary.
   */
  public static Population population(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Double hour = e.row().values().get(BOUNDARY);
      if (hour == null || hour != Math.rint(hour) || hour < 0 || hour > 23) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no day boundary in "
            + BOUNDARY + " (" + hour + "); the matrix was written before business days");
      }
      final Double active = e.row().values().get(ACTIVE);
      if (active == null) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no active business days "
            + "in " + ACTIVE + "; the matrix was written before the time back in Open or "
            + "Reopened was taken out, run issues-in-progress-similarity again");
      }
      final int whole = count(e.unit().startedAt().toEpochMilli(),
          e.unit().resolvedAt().toEpochMilli(), hour.intValue());
      if (active != Math.rint(active) || active < 0 || active > whole) {
        throw new IllegalArgumentException(e.unit().issueId() + " has " + active + " in "
            + ACTIVE + ", not a whole number from 0 to its " + whole + " business days");
      }
      final int days = active.intValue();
      if (days >= 1) {
        out.add(new RowCache.Entry(e.unit().withBusinessDays(days), e.row(), e.parts()));
      }
    }
    return new Population(List.copyOf(out), ElasticNetSelection.sizes(entries),
        ElasticNetSelection.sizes(out));
  }

  /**
   * The entries as they are, every one carrying its business days; refuses one that does not, as
   * an entry that did not go through {@link #population}: the chain's target is never the hours.
   */
  static List<RowCache.Entry> counted(final List<RowCache.Entry> entries) {
    for (final RowCache.Entry e : entries) {
      if (e.unit().businessDays() == null) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no business days set: the "
            + "entries of the in-progress similarity chain are BusinessDays.population's");
      }
    }
    return entries;
  }

  /** The upper ends of the bands of true business days the error is reported by, the last open. */
  private static final double[] BAND_TOPS = {1, 3, 10};

  private static final String[] BAND_LABELS = {"1", "2-3", "4-10", "11+"};

  /**
   * The predictions' error by band of the true business days: 1, 2-3, 4-10 and 11 or more ({@link
   * Breakdown#byEdges}). The bands only group the rows; every error is the whole, unclipped one.
   */
  public static List<Breakdown.Band> bands(final double[] predicted, final double[] observed) {
    return Breakdown.byEdges(predicted, observed, BAND_TOPS, BAND_LABELS);
  }

  /**
   * The business days an issue was worked on from {@code start} to {@code end} (ticket 18): the
   * interval without its stopped periods, each remaining stretch counted by {@link #count} at
   * {@code boundaryHour} and summed. A stopped period runs from a status change to one of {@link
   * #STOPPED} until the next status change of any kind, or without end when there is none, and is
   * clipped to {@code [start, end)}; back-to-back ones join. Patch Available and every other
   * status are kept. An issue never sent back counts as {@link #count} over the whole interval.
   *
   * @param events the issue's changelog in its order ({@link Universe#events}); only status
   *     changes with an instant are read
   */
  public static int active(final List<Corpus.Event> events, final long start, final long end,
      final int boundaryHour) {
    // the stopped periods in changelog order, each [from, to)
    final List<long[]> stopped = new ArrayList<>();
    Long since = null;
    for (final Corpus.Event e : events) {
      if (!"status".equals(e.field()) || e.at() == null) {
        continue;
      }
      if (since != null) {
        stopped.add(new long[] {since, e.at()});
      }
      since = STOPPED.contains(e.newValue()) ? e.at() : null;
    }
    if (since != null) {
      stopped.add(new long[] {since, Long.MAX_VALUE});
    }
    // the active stretches between them, clipped to [start, end)
    int days = 0;
    long from = start;
    for (final long[] p : stopped) {
      final long a = Math.max(p[0], start);
      final long b = Math.min(p[1], end);
      if (b > a) {
        if (a > from) {
          days += count(from, a, boundaryHour);
        }
        from = Math.max(from, b);
      }
    }
    if (end > from) {
      days += count(from, end, boundaryHour);
    }
    return days;
  }

  /**
   * Business days from {@code start} to {@code end}, epoch milliseconds, dates cut at {@code
   * boundaryHour} UTC.
   */
  public static int count(final long start, final long end, final int boundaryHour) {
    return (int) (weekdaysUpTo(date(end, boundaryHour))
        - weekdaysUpTo(date(start, boundaryHour)));
  }

  /** The date an instant is on when dates turn at {@code boundaryHour}: days since 1970-01-01. */
  private static long date(final long t, final int boundaryHour) {
    return Math.floorDiv(t - boundaryHour * HOUR, DAY);
  }

  /**
   * A running count of weekdays up to and including {@code date}; only differences are read.
   * 1970-01-01 was a Thursday, so {@code date + 3} is a multiple of seven on a Monday.
   */
  private static long weekdaysUpTo(final long date) {
    final long shifted = date + 3;
    return 5 * Math.floorDiv(shifted, 7) + Math.min(Math.floorMod(shifted, 7) + 1, 5);
  }
}
