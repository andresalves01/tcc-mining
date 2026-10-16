package app.issues;

import app.study.model.Breakdown;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-progress similarity chain's target (ticket 14): whole business days from the first In
 * Progress to the first resolution.
 *
 * <p><b>Counting.</b> Each date runs from the issue's day boundary, an hour of the day in UTC
 * ({@link DayBoundaries}), to the same hour the next day. The count is the number of
 * Monday-to-Friday dates reached after the start's date, up to and including the end's date: the same date is 0,
 * Friday to Monday is 1, Friday to Saturday is 0, and a start on a Saturday or Sunday belongs to
 * that weekend date, so Saturday to Monday is 1. An end exactly at the boundary is on the next
 * date. Holidays are ignored: the data hold no country per person.
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

  /** What the target is, in words for the help text and the run records. */
  public static final String TARGET = "whole business days (Monday to Friday dates reached) from "
      + "the first In Progress to the first resolution, dates cut at each issue's day boundary; "
      + "fitted as log1p, predictions back with expm1 held at zero, never rounded or clipped";

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
   * Every issue's business days from its first In Progress to its first resolution, at the
   * boundary in its {@link #BOUNDARY} column, and the issues of at least one; refuses a row
   * without the boundary, as a matrix written before ticket 14 has.
   */
  public static Population population(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Double hour = e.row().values().get(BOUNDARY);
      if (hour == null || hour != Math.rint(hour) || hour < 0 || hour > 23) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no day boundary in "
            + BOUNDARY + " (" + hour + "); the matrix was written before business days");
      }
      final int days = count(e.unit().startedAt().toEpochMilli(),
          e.unit().resolvedAt().toEpochMilli(), hour.intValue());
      if (days >= 1) {
        out.add(new RowCache.Entry(e.unit().withBusinessDays(days), e.row(), e.parts()));
      }
    }
    return new Population(List.copyOf(out), ElasticNetSelection.sizes(entries),
        ElasticNetSelection.sizes(out));
  }

  /** The upper ends of the bands of true business days the error is reported by, the last open. */
  private static final double[] BAND_TOPS = {1, 3, 10, Double.POSITIVE_INFINITY};

  private static final String[] BAND_LABELS = {"1", "2-3", "4-10", "11+"};

  /**
   * The predictions' error by band of the true business days: 1, 2-3, 4-10 and 11 or more. The
   * bands only group the rows; every error is the whole, unclipped one.
   */
  public static List<Breakdown.Band> bands(final double[] predicted, final double[] observed) {
    final List<Breakdown.Band> out = new ArrayList<>(BAND_LABELS.length);
    for (int b = 0; b < BAND_LABELS.length; b++) {
      final double low = b == 0 ? Double.NEGATIVE_INFINITY : BAND_TOPS[b - 1];
      final List<Double> truths = new ArrayList<>();
      final List<Double> errors = new ArrayList<>();
      for (int i = 0; i < observed.length; i++) {
        if (observed[i] > low && observed[i] <= BAND_TOPS[b]) {
          truths.add(observed[i]);
          errors.add(Math.abs(predicted[i] - observed[i]));
        }
      }
      out.add(new Breakdown.Band(BAND_LABELS[b], errors.size(), median(truths),
          errors.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
          median(errors)));
    }
    return List.copyOf(out);
  }

  private static double median(final List<Double> values) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    final double[] s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    final int n = s.length;
    return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
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
