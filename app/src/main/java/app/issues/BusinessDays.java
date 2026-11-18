package app.issues;

import app.study.model.Breakdown;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The restored in-progress target: whole business days from the first In Progress to the first
 * resolution, excluding periods back in Open or Reopened. Days without recorded activity stay.
 *
 * <p><b>Counting.</b> Each date runs from the issue's day boundary, an hour of the day in UTC
 * ({@link DayBoundaries}), to the same hour the next day. The count is the number of
 * Monday-to-Friday dates reached after the start's date, up to and including the end's date: the same date is 0,
 * Friday to Monday is 1, Friday to Saturday is 0, and a start on a Saturday or Sunday belongs to
 * that weekend date, so Saturday to Monday is 1. An end exactly at the boundary is on the next
 * date. Holidays are ignored: the data hold no country per person.
 *
 * <p><b>Status-adjusted time.</b> Periods marked Open or Reopened inside the interval are cut
 * out, and the business days of each remaining
 * stretch are summed ({@link #active}). Time in Patch Available, waiting for review, stays: review
 * is part of getting the change done. Reopenings after the first resolution never counted. The
 * similarity stage, which reads the changelog, writes each issue's count to the matrix ({@link
 * #ACTIVE}): every model and similarity history reads that count.
 *
 * <p>The recorded-activity helpers below remain available for historical diagnostics. They
 * do not define the target or the similarity histories. A matrix carrying their old target
 * columns is refused by the CLI until the histories are regenerated.
 *
 * <p><b>Population.</b> Only issues of at least one business day are studied: the model is
 * conditional, predicting how long an issue takes given that it lasts past its start's working
 * date. Since ticket 21 the extraction counts them ({@link #of}) and draws the split over them
 * alone ({@link ProjectSplit}); the issues of 0 business days are never drawn and enter no fitting,
 * validation, training or test set and no history. {@link #population} still drops any it meets,
 * a guard that drops nothing from a matrix of ticket 21.
 */
public final class BusinessDays {

  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;

  /** The matrix column holding each issue's day boundary, an hour of the day in UTC. */
  public static final String BOUNDARY = "day_boundary_hour";

  /**
   * The matrix column holding each issue's active business days ({@link #active}), written by
   * the similarity stage from the changelog: the restored target and the population's
   * count (at least one).
   */
  public static final String ACTIVE = "active_business_days";

  /**
   * Historical matrix column holding the retired target of {@code v}'s side: each issue's active business days
   * with recorded activity ({@link #recorded}) at that variant's threshold (ticket 27).
   */
  public static String recorded(final Augmentation.Variant v) {
    return "recorded_business_days" + v.suffix();
  }

  /** The matrix column holding {@code v}'s threshold T ({@link #threshold}), on every row. */
  public static String threshold(final Augmentation.Variant v) {
    return "silent_gap_threshold" + v.suffix();
  }

  /** The matrix columns the target is read from, in the order the similarity stage writes them. */
  public static final List<String> COLUMNS = List.of(BOUNDARY, ACTIVE);

  /** Marks matrices whose similarity histories used the retired recorded-activity target. */
  public static final List<String> LEGACY_RECORDED_COLUMNS = List.of(
      recorded(Augmentation.Variant.INNER), recorded(Augmentation.Variant.OUTER),
      threshold(Augmentation.Variant.INNER), threshold(Augmentation.Variant.OUTER));

  /**
   * Why a matrix with {@code columns} cannot give the target, null when it can: one without the
   * day boundary was written before business days (ticket 14), one without the active business
   * days before the time back in Open or Reopened was taken out (ticket 18).
   */
  /**
   * The business days of the whole interval, from the first In Progress to the first resolution,
   * at {@code boundaryHour}: the bound the active count never exceeds.
   */
  public static int whole(final IssueUnit unit, final int boundaryHour) {
    return count(unit.startedAt().toEpochMilli(), unit.resolvedAt().toEpochMilli(), boundaryHour);
  }

  public static String unwritten(final Collection<String> columns) {
    if (columns.stream().anyMatch(LEGACY_RECORDED_COLUMNS::contains)) {
      return "the matrix contains recorded-activity target columns; its similarity histories "
          + "must be regenerated for the restored target that retains days without records; "
          + "run issues-in-progress-similarity again from the extracted cache";
    }
    final List<String> missing = COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
    return missing.isEmpty() ? null : "the matrix has no " + String.join(", ", missing)
        + "; it was written before business days or before the time back in Open or Reopened "
        + "was taken out of them, run "
        + "issues-in-progress-similarity again";
  }

  /**
   * {@link #unwritten(Collection)} for the matrix of {@code population}: for {@link
   * Universe.Population#FIRST_COMMIT}, why a matrix without the target or its day boundary the
   * extraction writes cannot give the target, null when it can; the status is not read, so the
   * In Progress columns are not asked for.
   */
  public static String unwritten(final Collection<String> columns,
      final Universe.Population population) {
    if (population == Universe.Population.IN_PROGRESS) {
      return unwritten(columns);
    }
    final List<String> missing =
        columns(population).stream().filter(c -> !columns.contains(c)).toList();
    return missing.isEmpty() ? null : "the matrix has no " + String.join(", ", missing)
        + "; give the matrix issues-in-progress-retrospective wrote from the first-commit "
        + "extraction";
  }

  /**
   * The explicit paused statuses: time from a change to one of them until the next status
   * change is not counted ({@link #active}); this is a workflow measure, not measured effort.
   */
  public static final Set<String> STOPPED = Set.of("Open", "Reopened");

  /** What the target is, in words for the help text and the run records. */
  public static final String TARGET = "whole business days (Monday to Friday dates reached) from "
      + "the first In Progress to the first resolution, dates cut at each issue's day boundary, "
      + "without the time the issue was sent back to Open or Reopened in between (each period "
      + "from a status change to either until the next status change, clipped to the interval; "
      + "the business days of the remaining stretches summed; time in Patch Available kept): "
      + "days without recorded activity are retained; fitted as log1p, predictions back "
      + "with expm1 held at zero, never rounded or clipped";

  /** Who is studied, in words for the help text and the run records. */
  public static final String POPULATION = "issues of at least one business day: the model "
      + "predicts how long an issue takes given that it lasts past its start's working date; "
      + "the extraction counts them and draws the split over them alone, so the issues of 0 "
      + "business days are never drawn";

  /** What the count leaves out, in words for the help text and the run records. */
  public static final String HOLIDAYS = "holidays ignored: the data hold no country per person";

  /** The same status-adjusted duration and population in both split variants. */
  public record Population(List<RowCache.Entry> entries, ProjectSplit.Sizes all,
      ProjectSplit.Sizes kept) {}

  /**
   * The issues' day boundaries and active business days, as the extraction draws the split over
   * them and the similarity stage writes them to the matrix.
   *
   * @param boundaries per issue id, its day boundary
   * @param active per issue id, its active business days at that boundary
   * @param bots the raw accounts recognised as bots and left out of every boundary's history
   */
  public record Counted(Map<String, DayBoundaries.Boundary> boundaries,
      Map<String, Integer> active, Set<String> bots) {}

  private BusinessDays() {}

  /**
   * Each In-Progress issue's day boundary ({@link DayBoundaries}), read from the corpus's tracker
   * activity before its first In Progress by the person who made that move, and its active
   * business days at it ({@link #active}); one rule for both stages that count them.
   *
   * @param ids issues of {@link Universe.Population#IN_PROGRESS}; another refuses
   */
  public static Counted of(final Corpus corpus, final Universe universe,
      final Collection<String> ids) {
    final DayBoundaries days = DayBoundaries.of(corpus, universe);
    final Map<String, DayBoundaries.Boundary> boundaries = new HashMap<>(ids.size() * 2);
    final Map<String, Integer> active = new HashMap<>(ids.size() * 2);
    for (final String id : ids) {
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      if (u == null) {
        throw new IllegalArgumentException(id + " is not an In-Progress issue of the corpus");
      }
      final String clicker = World.person(u.startedBy(), corpus.people());
      final DayBoundaries.Boundary boundary = days.at(clicker, u.project(), u.start());
      boundaries.put(id, boundary);
      active.put(id, active(universe.events(id), u.start(), u.resolved(), boundary.hour()));
    }
    return new Counted(boundaries, active, days.bots());
  }

  /**
   * Every issue's active business days, read from its {@link #ACTIVE} column, and the issues of at
   * least one; refuses a row without the boundary, as a matrix written before ticket 14 has, a row
   * without the active count, as one written before ticket 18 has, and a count that is not a whole
   * number from 0 to the business days of the whole interval at the row's boundary.
   */
  public static Population population(final List<RowCache.Entry> entries,
      final Augmentation.Variant variant) {
    if (firstCommit(entries)) {
      return firstCommitPopulation(entries);
    }
    if (AdjustedDuration.marked(entries) && entries.stream().anyMatch(e -> !AdjustedDuration.marked(e)))
      throw new IllegalArgumentException("mixed legacy and adjusted targets");
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
      final int whole = whole(e.unit(), hour.intValue());
      if (active != Math.rint(active) || active < 0 || active > whole) {
        throw new IllegalArgumentException(e.unit().issueId() + " has " + active + " in "
            + ACTIVE + ", not a whole number from 0 to its " + whole + " business days");
      }
      final boolean adjusted = AdjustedDuration.marked(e);
      final Double target = adjusted ? e.row().values().get(AdjustedDuration.DAYS) : active;
      if (adjusted && e.row().values().get(AdjustedDuration.VERSION) != 1.0)
        throw new IllegalArgumentException("unsupported adjusted duration version");
      if (target == null) continue; // No eligible code evidence, never fall back to the old target.
      if (!Double.isFinite(target) || target != Math.rint(target) || target < 0 || target > whole)
        throw new IllegalArgumentException("invalid adjusted target: " + e.unit().issueId());
      final int days = target.intValue();
      if (days >= (adjusted ? 0 : 1)) {
        out.add(new RowCache.Entry(e.unit().withBusinessDays(days), e.row(), e.parts()));
      }
    }
    return new Population(List.copyOf(out), ElasticNetSelection.sizes(entries),
        ElasticNetSelection.sizes(out));
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
   * The matrix columns the target is read from, in {@code population}: the day boundary and the
   * active business days the similarity stage writes for {@link Universe.Population#IN_PROGRESS},
   * the target and its day boundary the extraction writes for {@link
   * Universe.Population#FIRST_COMMIT}.
   */
  public static List<String> columns(final Universe.Population population) {
    return switch (population) {
      case IN_PROGRESS -> COLUMNS;
      case FIRST_COMMIT -> List.of(FirstCommit.BOUNDARY_COLUMN, FirstCommit.TARGET_COLUMN);
    };
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

  public static String target(final List<RowCache.Entry> entries) {
    return target(entries, FirstCommit.DEFAULT_WAIT_K);
  }

  /**
   * The target in words; for the first-commit study's rows, with the wait fence at {@code
   * waitK}, the k the matrix was extracted with.
   */
  public static String target(final List<RowCache.Entry> entries, final double waitK) {
    if (firstCommit(entries)) {
      return FirstCommit.rule(waitK);
    }
    return AdjustedDuration.marked(entries) ? AdjustedDuration.RULE : TARGET;
  }

  public static String populationRule(final List<RowCache.Entry> entries) {
    if (firstCommit(entries)) {
      return FirstCommit.POPULATION;
    }
    return AdjustedDuration.marked(entries)
        ? "frozen original partitions; eligible code evidence required; zero durations retained"
        : POPULATION;
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
    int days = 0;
    for (final long[] s : stretches(events, start, end)) {
      days += count(s[0], s[1], boundaryHour);
    }
    return days;
  }

  /**
   * The active stretches of {@code [start, end)}, each {@code [from, to)} in order: the interval
   * without its stopped periods, as {@link #active} counts it.
   */
  static List<long[]> stretches(final List<Corpus.Event> events, final long start,
      final long end) {
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
    final List<long[]> out = new ArrayList<>();
    long from = start;
    for (final long[] p : stopped) {
      final long a = Math.max(p[0], start);
      final long b = Math.min(p[1], end);
      if (b > a) {
        if (a > from) {
          out.add(new long[] {from, a});
        }
        from = Math.max(from, b);
      }
    }
    if (end > from) {
      out.add(new long[] {from, end});
    }
    return out;
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
