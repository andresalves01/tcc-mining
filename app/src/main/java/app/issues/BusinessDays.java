package app.issues;

import app.study.model.Breakdown;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The study's business days: the first-commit target the extraction writes ({@link
 * FirstCommit#TARGET_COLUMN}), read back with its day boundary, and how business days are counted.
 *
 * <p><b>Counting.</b> Each date runs from the issue's day boundary, an hour of the day in UTC
 * ({@link DayBoundaries}), to the same hour the next day. The count is the number of
 * Monday-to-Friday dates reached after the start's date, up to and including the end's date: the
 * same date is 0, Friday to Monday is 1, Friday to Saturday is 0, and a start on a Saturday or
 * Sunday belongs to that weekend date, so Saturday to Monday is 1. An end exactly at the boundary
 * is on the next date. Holidays are ignored: the data hold no country per person.
 *
 * <p><b>Population.</b> Only issues of at least one business day are studied: the model is
 * conditional, predicting how long an issue takes given that it lasts past its start's working
 * date. The extraction draws the split over them alone; {@link #population} refuses a row whose
 * target is not a whole number from 1 to the business days of its interval.
 */
public final class BusinessDays {

  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;

  /**
   * The business days of the whole interval, from the start to the first resolution, at {@code
   * boundaryHour}: the bound the target never exceeds.
   */
  public static int whole(final IssueUnit unit, final int boundaryHour) {
    return count(unit.startedAt().toEpochMilli(), unit.resolvedAt().toEpochMilli(), boundaryHour);
  }

  /**
   * Why a matrix without the target or its day boundary the extraction writes cannot give the
   * target, null when it can; the status is not read.
   */
  public static String unwritten(final Collection<String> columns,
      final Universe.Population population) {
    final List<String> missing =
        columns(population).stream().filter(c -> !columns.contains(c)).toList();
    return missing.isEmpty() ? null : "the matrix has no " + String.join(", ", missing)
        + "; give the matrix issues-in-progress-retrospective wrote from the first-commit "
        + "extraction";
  }

  /** What the count leaves out, in words for the help text and the run records. */
  public static final String HOLIDAYS = "holidays ignored: the data hold no country per person";

  /** The same status-adjusted duration and population in both split variants. */
  public record Population(List<RowCache.Entry> entries, ProjectSplit.Sizes all,
      ProjectSplit.Sizes kept) {}

  private BusinessDays() {}

  /**
   * Every issue's business days, read from the first-commit target the extraction wrote; refuses
   * rows without it ({@link #firstCommitPopulation}).
   */
  public static Population population(final List<RowCache.Entry> entries,
      final Augmentation.Variant variant) {
    if (!entries.isEmpty() && !firstCommit(entries)) {
      throw new IllegalArgumentException("the rows carry no first-commit target ("
          + FirstCommit.TARGET_COLUMN + "): give the matrix the first-commit extraction wrote");
    }
    return firstCommitPopulation(entries);
  }

  /**
   * Whether the rows are of the first-commit study ({@link FirstCommit}), whose target the
   * extraction wrote to {@link FirstCommit#TARGET_COLUMN}; refuses a mixture with other rows.
   */
  public static boolean firstCommit(final List<RowCache.Entry> entries) {
    final long marked = entries.stream()
        .filter(e -> e.row().values().containsKey(FirstCommit.TARGET_COLUMN)).count();
    if (marked > 0 && marked < entries.size()) {
      throw new IllegalArgumentException("mixed first-commit and other targets");
    }
    return marked > 0;
  }

  /**
   * The matrix columns the target is read from, in {@code population}: the target and its day
   * boundary the extraction writes.
   */
  public static List<String> columns(final Universe.Population population) {
    return List.of(FirstCommit.BOUNDARY_COLUMN, FirstCommit.TARGET_COLUMN);
  }

  /**
   * The first-commit rows, each carrying its target ({@link FirstCommit#TARGET_COLUMN}) as its
   * business days: the status is not read, and every history and model reads that target. Refuses
   * a row without it or its day boundary, and a target that is not a whole number from 1 to the
   * business days from the first commit to the first resolution at that boundary: the extraction
   * draws only issues of at least one.
   */
  private static Population firstCommitPopulation(final List<RowCache.Entry> entries) {
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Double hour = e.row().values().get(FirstCommit.BOUNDARY_COLUMN);
      if (hour == null || hour != Math.rint(hour) || hour < 0 || hour > 23) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no day boundary in "
            + FirstCommit.BOUNDARY_COLUMN + " (" + hour + ")");
      }
      final double target = e.row().values().get(FirstCommit.TARGET_COLUMN);
      final int whole = whole(e.unit(), hour.intValue());
      if (target != Math.rint(target) || target < 1 || target > whole) {
        throw new IllegalArgumentException(e.unit().issueId() + " has " + target + " in "
            + FirstCommit.TARGET_COLUMN + ", not a whole number from 1 to its " + whole
            + " business days");
      }
      out.add(new RowCache.Entry(e.unit().withBusinessDays((int) target), e.row(), e.parts()));
    }
    return new Population(List.copyOf(out), ElasticNetSelection.sizes(entries),
        ElasticNetSelection.sizes(out));
  }

  /**
   * The target in words; for the first-commit study's rows, with the wait fence at {@code
   * waitK}, the k the matrix was extracted with.
   */
  public static String target(final List<RowCache.Entry> entries, final double waitK) {
    return FirstCommit.rule(waitK);
  }

  public static String populationRule(final List<RowCache.Entry> entries) {
    return FirstCommit.POPULATION;
  }

  /**
   * The entries as they are, every one carrying its business days; refuses one that does not, as
   * an entry that did not go through {@link #population}: the chain's target is never the hours.
   */
  static List<RowCache.Entry> counted(final List<RowCache.Entry> entries) {
    for (final RowCache.Entry e : entries) {
      if (e.unit().businessDays() == null) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no business days set: the "
            + "entries the models read are BusinessDays.population's");
      }
    }
    return entries;
  }

  /** How the error bands are cut (ticket 24, item 53 vii), for the report and the run record. */
  public static final String BANDS = "bands of the observed business days at the quartiles of "
      + "the observed business days of the cut fitting rows (the fitting side, never validation "
      + "or test labels): up to Q1, above Q1 up to the median, above the median up to Q3, above Q3";

  /**
   * The predictions' error by band of the true business days ({@link Breakdown#byEdges}), the
   * bands cut at {@code tops}, the quartiles of the observed business days of the cut fitting rows
   * ({@link TailCut#quartiles}): up to Q1, above it up to the median, above that up to Q3, above
   * Q3. Equal quartiles leave a band empty. The bands only group the rows; every error is the
   * whole, unclipped one. They replace the fixed bands 1, 2-3, 4-10 and 11 or more (ticket 24).
   */
  public static List<Breakdown.Band> bands(final double[] predicted, final double[] observed,
      final double[] tops) {
    return Breakdown.byEdges(predicted, observed, tops, bandLabels(tops));
  }

  /**
   * The bands' labels at {@code tops}, a half-open range each: {@code <=a}, {@code (a, b]},
   * {@code (b, c]}, {@code >c}.
   */
  public static String[] bandLabels(final double[] tops) {
    if (tops.length != 3) {
      throw new IllegalArgumentException("three quartiles, not " + tops.length);
    }
    return new String[] {"<=" + days(tops[0]), "(" + days(tops[0]) + ", " + days(tops[1]) + "]",
        "(" + days(tops[1]) + ", " + days(tops[2]) + "]", ">" + days(tops[2])};
  }

  /** The bands' limits as the headers write them: {@code a, b and c}, as the labels do. */
  public static String bandLimits(final double[] tops) {
    return days(tops[0]) + ", " + days(tops[1]) + " and " + days(tops[2]);
  }

  /**
   * Business days as the bands' labels and headers write them: a whole number without its
   * decimal point, any other rounded to two decimals, trailing zeros dropped.
   */
  public static String days(final double v) {
    return v == Math.rint(v) ? Long.toString((long) v)
        : java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
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
