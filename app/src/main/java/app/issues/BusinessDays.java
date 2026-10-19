package app.issues;

import app.study.model.Breakdown;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The in-progress similarity chain's target (tickets 14, 18 and 27): whole business days from the
 * first In Progress to the first resolution, without the time the issue was sent back to Open or
 * Reopened in between, and without the whole working days inside the silent gaps between its
 * records: the active business days with recorded activity.
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
 * #ACTIVE}): it decides the population and is kept as a diagnostic no model reads.
 *
 * <p><b>Recorded activity</b> (ticket 27, item 62 of the retrospective decisions). The records
 * are the issue's changelog events, comments and human implementation commits' author and
 * committer dates ({@link Records}); a gap between consecutive ones of g active business days
 * ({@link #gaps}) is silent when g reaches the threshold T and leaves out its g - 1 whole working
 * days ({@link #recorded}). T is read once per variant from the gaps of its fitting side alone
 * ({@link #thresholds}), after the split is drawn; at least one day always stays, so the
 * population and the split are those of the active count. The similarity stage writes the target
 * and T of each variant ({@link #recorded(Augmentation.Variant)}, {@link
 * #threshold(Augmentation.Variant)}); every later step reads the target of its side from there
 * ({@link #population}): the inner chain the inner one, the sealed test the outer one.
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
   * the similarity stage from the changelog: the target until ticket 27, now the population's
   * count (at least one) and a diagnostic no model reads.
   */
  public static final String ACTIVE = "active_business_days";

  /**
   * The matrix column holding the target of {@code v}'s side: each issue's active business days
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
  public static final List<String> COLUMNS = List.of(BOUNDARY, ACTIVE,
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
    final List<String> missing = COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
    return missing.isEmpty() ? null : "the matrix has no " + String.join(", ", missing)
        + "; it was written before business days or before the time back in Open or Reopened "
        + "was taken out of them or before the days with recorded activity (ticket 27), run "
        + "issues-in-progress-similarity again";
  }

  /**
   * The statuses an issue is sent back to when nobody is working on it: the time from a change to
   * one of them until the next status change is not counted ({@link #active}).
   */
  public static final Set<String> STOPPED = Set.of("Open", "Reopened");

  /** The least gap, in business days, that is silent whatever the fence ({@link #threshold}). */
  public static final double LEAST_SILENT = 2;

  /** The Tukey multiplier of the IQR the silent gaps' fence is drawn at ({@link #threshold}). */
  public static final double SILENT_K = 3;

  /** How the target leaves out the silent days, in words for the run records and the reports. */
  public static final String SILENCE = "records = the issue's changelog events, its comments "
      + "and the author and committer dates of its human implementation commits visible before "
      + "the first resolution, strictly between the first In Progress and the first resolution "
      + "and outside the time back in Open or Reopened; a gap between consecutive records (the "
      + "first In Progress and the first resolution bound the first and the last) spans g "
      + "active business days at the issue's day boundary; it is silent when g >= T and then "
      + "leaves out its g - 1 whole working days without any record; T = max(2, Q3 + 3 IQR of "
      + "every gap of the variant's fitting side: the inner fitting set for the inner variant, "
      + "the outer training for the outer), quartiles of type 7; every issue keeps at least one "
      + "day, so the population and the split are those of the active business days";

  /** What the target is, in words for the help text and the run records. */
  public static final String TARGET = "active business days with recorded activity: whole "
      + "business days (Monday to Friday dates reached) from the first In Progress to the first "
      + "resolution, dates cut at each issue's day boundary, without the time the issue was "
      + "sent back to Open or Reopened in between (each period from a status change to either "
      + "until the next status change, clipped to the interval; the business days of the "
      + "remaining stretches summed; time in Patch Available kept): time nobody was working on "
      + "the issue is not counted; then the whole working days inside the silent gaps between "
      + "records left out (" + SILENCE + "); fitted as log1p, predictions back with expm1 held "
      + "at zero, never rounded or clipped";

  /** Who is studied, in words for the help text and the run records. */
  public static final String POPULATION = "issues of at least one business day: the model "
      + "predicts how long an issue takes given that it lasts past its start's working date; "
      + "the extraction counts them and draws the split over them alone, so the issues of 0 "
      + "business days are never drawn";

  /** What the count leaves out, in words for the help text and the run records. */
  public static final String HOLIDAYS = "holidays ignored: the data hold no country per person";

  /**
   * The issues of at least one active business day, each unit carrying its target, the active
   * business days with recorded activity of one variant's side.
   *
   * @param entries those issues, in the given order, rows and splits as they were
   * @param all the four split sizes before the drop
   * @param kept the four split sizes after it
   * @param variant the side whose threshold the target was counted at
   * @param threshold that threshold T
   * @param leftOut per split of the variant the entries hold (its fitting side, then its scored
   *     one), the silent days the target leaves out of its issues' active business days, summed
   */
  public record Population(List<RowCache.Entry> entries, RegistrationInProgress.Sizes all,
      RegistrationInProgress.Sizes kept, Augmentation.Variant variant, double threshold,
      Map<String, Integer> leftOut) {

    /** The rule, T and the days left out, for the run records. */
    public Map<String, Object> silence() {
      final Map<String, Object> out = new LinkedHashMap<>();
      out.put("rule", SILENCE);
      out.put("variant", variant.name().toLowerCase(Locale.ROOT));
      out.put("threshold_business_days", threshold);
      out.put("days_left_out", leftOut);
      return out;
    }
  }

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
   * Every issue's active business days, read from its {@link #ACTIVE} column, the issues of at
   * least one, each carrying its target at {@code variant}'s threshold, read from its {@link
   * #recorded} column; refuses a row without the boundary, as a matrix written before ticket 14
   * has, a row without the active count, as one written before ticket 18 has, a row without the
   * target or its threshold, as one written before ticket 27 has, an active count that is not a
   * whole number from 0 to the business days of the whole interval at the row's boundary, a
   * target that is not a whole number from 1 to the active count, and thresholds that differ.
   */
  public static Population population(final List<RowCache.Entry> entries,
      final Augmentation.Variant variant) {
    final String target = recorded(variant);
    final String bound = threshold(variant);
    final boolean inner = variant == Augmentation.Variant.INNER;
    final String fitting = inner ? "inner_fitting" : "outer_training";
    final String scored = inner ? "inner_validation" : "outer_test";
    // the fitting side first, then the scored one, each only when the entries hold it
    final Map<String, Integer> leftOut = new LinkedHashMap<>();
    final Map<String, Integer> scoredOut = new LinkedHashMap<>();
    Double threshold = null;
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
      final Double recorded = e.row().values().get(target);
      final Double t = e.row().values().get(bound);
      if (recorded == null || t == null) {
        throw new IllegalArgumentException(e.unit().issueId() + " has no " + target + " or "
            + bound + "; the matrix was written before the days with recorded activity "
            + "(ticket 27), run issues-in-progress-similarity again");
      }
      if (threshold == null) {
        threshold = t;
      } else if (Double.compare(threshold, t) != 0) {
        throw new IllegalArgumentException(e.unit().issueId() + " has " + t + " in " + bound
            + ", another row " + threshold + ": one threshold per variant");
      }
      final int days = active.intValue();
      if (days < 1) {
        continue;
      }
      if (recorded != Math.rint(recorded) || recorded < 1 || recorded > days) {
        throw new IllegalArgumentException(e.unit().issueId() + " has " + recorded + " in "
            + target + ", not a whole number from 1 to its " + days + " active business days");
      }
      out.add(new RowCache.Entry(e.unit().withBusinessDays(recorded.intValue()), e.row(),
          e.parts()));
      final String o = e.outer(Universe.Population.IN_PROGRESS);
      final String i = e.inner(Universe.Population.IN_PROGRESS);
      if (variant.fitting(o, i)) {
        leftOut.merge(fitting, days - recorded.intValue(), Integer::sum);
      } else if (variant.scored(o, i)) {
        scoredOut.merge(scored, days - recorded.intValue(), Integer::sum);
      }
    }
    leftOut.putAll(scoredOut);
    return new Population(List.copyOf(out), ElasticNetSelection.sizes(entries),
        ElasticNetSelection.sizes(out), variant, threshold == null ? LEAST_SILENT : threshold,
        Collections.unmodifiableMap(leftOut));
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
   * The business days of each gap between consecutive records of an issue (ticket 27, item 62),
   * in order: the records are the instants strictly inside {@code (start, end)} that fall in an
   * active stretch ({@link #stretches}), {@code start} and {@code end} bound the first and the
   * last gap, and each gap counts only the business days of the active stretches it overlaps, at
   * {@code boundaryHour}. Records at one instant give gaps of 0. The gaps sum to {@link #active}.
   *
   * @param records the issue's record instants ({@link Records}), in any order
   */
  public static int[] gaps(final List<Corpus.Event> events, final long[] records,
      final long start, final long end, final int boundaryHour) {
    final List<long[]> stretches = stretches(events, start, end);
    final List<Long> bounds = new ArrayList<>(records.length + 2);
    bounds.add(start);
    for (final long r : records) {
      if (r > start && r < end && stretches.stream().anyMatch(s -> r >= s[0] && r < s[1])) {
        bounds.add(r);
      }
    }
    bounds.add(end);
    bounds.sort(null);
    final int[] out = new int[bounds.size() - 1];
    for (int k = 0; k < out.length; k++) {
      final long g0 = bounds.get(k);
      final long g1 = bounds.get(k + 1);
      for (final long[] s : stretches) {
        final long a = Math.max(s[0], g0);
        final long b = Math.min(s[1], g1);
        if (b > a) {
          out[k] += count(a, b, boundaryHour);
        }
      }
    }
    return out;
  }

  /**
   * The active business days with recorded activity (ticket 27, item 62): the sum of the gaps
   * ({@link #gaps}), each silent one, of at least {@code threshold} business days, counted as 1,
   * its g - 1 whole working days without any record left out. At least 1 whenever the {@link
   * #active} count is: a gap of at least one day always keeps one.
   */
  public static int recorded(final int[] gaps, final double threshold) {
    int days = 0;
    for (final int g : gaps) {
      days += g >= threshold ? 1 : g;
    }
    return days;
  }

  /** The same, from the changelog and the records ({@link #gaps}). */
  public static int recorded(final List<Corpus.Event> events, final long[] records,
      final long start, final long end, final int boundaryHour, final double threshold) {
    return recorded(gaps(events, records, start, end, boundaryHour), threshold);
  }

  /**
   * The silent gaps' threshold T: Tukey's fence Q3 + {@value #SILENT_K} IQR of every gap of
   * {@code gaps} pooled, quartiles by {@link TailCut#quartiles}, or {@value #LEAST_SILENT} when
   * that fence is below it or there is no gap (one business day is only the night or the time
   * zone).
   */
  public static double threshold(final Collection<int[]> gaps) {
    final double[] all = gaps.stream().flatMapToInt(java.util.Arrays::stream)
        .asDoubleStream().toArray();
    if (all.length == 0) {
      return LEAST_SILENT;
    }
    final double[] q = TailCut.quartiles(all);
    return Math.max(LEAST_SILENT, q[2] + SILENT_K * (q[2] - q[0]));
  }

  /**
   * The threshold of each variant ({@link #threshold}), from the gaps of its fitting side alone
   * ({@link Augmentation.Variant#fitting}): never a validation or test issue's.
   *
   * @param gaps per issue id, its gaps ({@link #gaps})
   * @param parts per issue id, its outer and inner verdicts
   */
  public static Map<Augmentation.Variant, Double> thresholds(final Map<String, int[]> gaps,
      final Map<String, String[]> parts) {
    final Map<Augmentation.Variant, Double> out = new java.util.EnumMap<>(
        Augmentation.Variant.class);
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      final List<int[]> fitting = new ArrayList<>();
      gaps.forEach((id, g) -> {
        final String[] p = parts.get(id);
        if (p != null && v.fitting(p[0], p[1])) {
          fitting.add(g);
        }
      });
      out.put(v, threshold(fitting));
    }
    return out;
  }

  /**
   * Every issue's record instants, as the target with recorded activity reads them ({@link
   * #gaps}): its changelog events and its comments with an instant, bot accounts' left out ({@link
   * DayBoundaries#bots}, as the day boundaries leave them out), and the author and committer dates
   * of its implementation commits ({@link World#implementation}: human, linked, visible before its
   * first resolution). Records are human activity. Nothing is clipped here; {@link #gaps} keeps
   * what falls inside.
   */
  public static final class Records {

    private final Universe universe;
    private final World world;
    private final Map<String, Long> committed;
    private final Set<String> bots;

    private Records(final Universe universe, final World world,
        final Map<String, Long> committed, final Set<String> bots) {
      this.universe = universe;
      this.world = world;
      this.committed = committed;
      this.bots = bots;
    }

    /** {@code bots}: the raw accounts whose events and comments are not records. */
    public static Records of(final Corpus corpus, final Universe universe, final World world,
        final Set<String> bots) {
      final Map<String, Long> committed = new HashMap<>();
      for (final Corpus.Commit c : corpus.commits()) {
        if (c.committedAt() != null) {
          committed.put(c.id(), c.committedAt());
        }
      }
      return new Records(universe, world, committed, Set.copyOf(bots));
    }

    private boolean bot(final String author) {
      return author != null && bots.contains(author);
    }

    /** The records of In-Progress issue {@code id}, in no particular order. */
    public long[] of(final String id) {
      final Universe.Unit u = universe.unit(Universe.Population.IN_PROGRESS, id);
      if (u == null) {
        throw new IllegalArgumentException(id + " is not an In-Progress issue of the corpus");
      }
      final List<Long> out = new ArrayList<>();
      universe.events(id).stream().filter(e -> !bot(e.author())).map(Corpus.Event::at)
          .filter(java.util.Objects::nonNull).forEach(out::add);
      world.comments(id).stream().filter(c -> !bot(c.author()))
          .map(Corpus.Comment::at).filter(java.util.Objects::nonNull).forEach(out::add);
      for (final World.Commit c : world.implementation(id, u.resolved())) {
        out.add(c.authoredAt());
        final Long at = committed.get(c.id());
        if (at != null) {
          out.add(at);
        }
      }
      return out.stream().mapToLong(Long::longValue).toArray();
    }
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
