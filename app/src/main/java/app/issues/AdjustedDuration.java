package app.issues;

import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned two-phase label; the old duration and the original partitions are never replaced. */
public final class AdjustedDuration {
  public static final String VERSION = "adjusted_duration_version";
  public static final String DAYS = "adjusted_business_days";
  public static final String PREFIX = "adjusted_prefix_days";
  public static final String IMPLEMENTATION = "adjusted_implementation_days";
  public static final String REVIEW = "adjusted_review_activity_days";
  public static final String WAIT = "adjusted_post_commit_wait_days";
  public static final String CAP = "adjusted_usual_gap_cap";
  public static final String ORIGINAL = "adjusted_has_original_commits";
  public static final List<String> COLUMNS = List.of(VERSION, DAYS, PREFIX, IMPLEMENTATION,
      REVIEW, WAIT, CAP, ORIGINAL);
  public static final String RULE = "two-phase recorded-activity duration v1: first In Progress "
      + "to first resolution; full prefix to first in-window commit retained; inter-commit "
      + "business days capped at the largest observed non-outlier under Q3+1.5 IQR learned "
      + "once on inner fitting and frozen; explicit human activity/commit dates are a floor; "
      + "after last authored code change only dates with human comments, attachments or PR "
      + "reviews count; administrative changes do not; Open/Reopened silence excluded but "
      + "explicit activity dates restored; zero retained; no eligible commit means missing";

  public record Threshold(double q1, double median, double q3, double fence, int cap, int gaps) {}
  public record Count(Integer total, int prefix, int implementation, int review, int waitDays,
      int oldDays, int pausedActivityDays, int removedInternalDays, int originalCommits,
      int commits, Long lastAuthoredAt, String quality, List<String> warnings) {}
  public record Result(Threshold threshold, List<RowCache.Entry> entries, Map<String, Count> counts) {}

  private AdjustedDuration() {}

  public static boolean marked(final RowCache.Entry e) {
    return e.row().values().containsKey(VERSION);
  }

  public static boolean marked(final Collection<RowCache.Entry> entries) {
    return entries.stream().anyMatch(AdjustedDuration::marked);
  }

  public static LocalDate date(final long at, final int hour) {
    return Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).minusHours(hour).toLocalDate();
  }

  private static Set<LocalDate> dates(final long start, final long end, final int hour) {
    final Set<LocalDate> out = new HashSet<>();
    for (LocalDate d = date(start, hour).plusDays(1); !d.isAfter(date(end, hour)); d = d.plusDays(1)) {
      if (d.getDayOfWeek().getValue() <= 5) out.add(d);
    }
    return out;
  }

  private record Prepared(RowCache.Entry row, DurationEvidence evidence, long start, long end,
      int hour, Set<LocalDate> allowed, Set<LocalDate> positive,
      List<DurationEvidence.Change> commits, int oldDays, int restored) {
    Set<LocalDate> between(final long a, final long b) {
      final Set<LocalDate> out = dates(a, b, hour);
      out.retainAll(allowed);
      return out;
    }
  }

  private static Prepared prepare(final RowCache.Entry row, final DurationEvidence evidence) {
    final long start = row.unit().startedAt().toEpochMilli(), end = row.unit().resolvedAt().toEpochMilli();
    final Double rawHour = row.row().values().get(BusinessDays.BOUNDARY);
    if (rawHour == null || rawHour != Math.rint(rawHour) || rawHour < 0 || rawHour > 23)
      throw new IllegalArgumentException("invalid frozen day boundary: " + row.unit().key());
    final int hour = rawHour.intValue();
    final Set<LocalDate> whole = dates(start, end, hour), allowed = new HashSet<>();
    for (final long[] span : BusinessDays.stretches(evidence.events(), start, end))
      allowed.addAll(dates(span[0], span[1], hour));
    final int oldDays = allowed.size();
    final Double old = row.row().values().get(BusinessDays.ACTIVE);
    if (old == null || old != oldDays)
      throw new IllegalArgumentException("status/day-boundary parity failed: " + row.unit().key());
    final Map<String, DurationEvidence.Change> unique = new LinkedHashMap<>();
    for (final var c : evidence.changes()) {
      if (c.authoredAt() >= end) continue;
      final var prior = unique.get(c.sha());
      if (prior == null || c.original() && !prior.original()) unique.put(c.sha(), c);
    }
    final List<DurationEvidence.Change> commits = unique.values().stream()
        .sorted(Comparator.comparingLong(DurationEvidence.Change::authoredAt)
            .thenComparing(DurationEvidence.Change::sha)).toList();
    final Set<LocalDate> positive = new HashSet<>();
    for (final var a : evidence.activities())
      if (a.at() >= start && a.at() <= end) positive.add(date(a.at(), hour));
    for (final var c : commits)
      if (c.authoredAt() >= start) positive.add(date(c.authoredAt(), hour));
    positive.retainAll(whole);
    allowed.addAll(positive); // Explicit work overrides a paused status, not the interval bounds.
    return new Prepared(row, evidence, start, end, hour, allowed, positive, commits,
        oldDays, allowed.size() - oldDays);
  }

  private static DurationEvidence required(final Map<String, DurationEvidence> evidence, final String id) {
    final DurationEvidence found = evidence.get(id);
    if (found == null) throw new IllegalArgumentException("missing evidence envelope (status history required even without code): " + id);
    return found;
  }

  private static List<DurationEvidence.Change> inside(final Prepared p) {
    return p.commits().stream().filter(c -> c.authoredAt() >= p.start()).toList();
  }

  /** Only inner-fitting records may define this label's one frozen threshold. */
  public static Threshold threshold(final List<RowCache.Entry> rows,
      final Map<String, DurationEvidence> evidence) {
    final List<Integer> gaps = new ArrayList<>();
    for (final var r : rows) {
      if (!"TRAIN".equals(r.outer(Population.IN_PROGRESS))
          || !"TRAIN".equals(r.inner(Population.IN_PROGRESS))) continue;
      final Prepared p = prepare(r, required(evidence, r.unit().issueId()));
      final var cs = inside(p);
      for (int i = 1; i < cs.size(); i++)
        gaps.add(p.between(cs.get(i - 1).authoredAt(), cs.get(i).authoredAt()).size());
    }
    if (gaps.isEmpty()) throw new IllegalArgumentException("no fitting inter-commit gaps; cannot invent a usual wait");
    final double[] sorted = gaps.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
    final double q1 = TailCut.quantile(sorted, .25), q3 = TailCut.quantile(sorted, .75);
    final double fence = q3 + 1.5 * (q3 - q1);
    final int cap = gaps.stream().filter(g -> g <= fence).mapToInt(Integer::intValue).max().orElseThrow();
    return new Threshold(q1, TailCut.quantile(sorted, .5), q3, fence, cap, gaps.size());
  }

  public static Count count(final RowCache.Entry row, final DurationEvidence evidence, final int cap) {
    if (cap < 0) throw new IllegalArgumentException("negative usual wait");
    final Prepared p = prepare(row, evidence);
    if (p.commits().isEmpty()) return new Count(null, 0, 0, 0, 0, p.oldDays(), p.restored(),
        0, 0, 0, null, evidence.quality(), evidence.warnings());
    final var cs = inside(p);
    final long last = p.commits().getLast().authoredAt(), anchor = Math.max(p.start(), last);
    int prefix = 0, implementation = 0, removed = 0;
    if (!cs.isEmpty()) {
      prefix = p.between(p.start(), cs.getFirst().authoredAt()).size();
      implementation = prefix;
      for (int i = 1; i < cs.size(); i++) {
        final Set<LocalDate> working = p.between(cs.get(i - 1).authoredAt(), cs.get(i).authoredAt());
        final Set<LocalDate> explicit = new HashSet<>(working);
        explicit.retainAll(p.positive());
        final int kept = Math.max(Math.min(working.size(), cap), explicit.size());
        implementation += kept;
        removed += working.size() - kept;
      }
    }
    final Set<LocalDate> tail = p.between(anchor, p.end());
    final Set<LocalDate> reviewDates = new HashSet<>(tail);
    reviewDates.retainAll(p.positive());
    final int review = reviewDates.size(), total = implementation + review;
    if (total > p.allowed().size()) throw new IllegalStateException("double-counted activity date");
    return new Count(total, prefix, implementation, review, tail.size() - review, p.oldDays(),
        p.restored(), removed, (int) p.commits().stream().filter(DurationEvidence.Change::original).count(),
        p.commits().size(), last, evidence.quality(), evidence.warnings());
  }

  public static Result apply(final List<RowCache.Entry> rows, final Map<String, DurationEvidence> evidence) {
    return apply(rows, evidence, threshold(rows, evidence));
  }

  /** A saved threshold is reused verbatim on future validation/evaluation rows. */
  public static Result apply(final List<RowCache.Entry> rows, final Map<String, DurationEvidence> evidence,
      final Threshold threshold) {
    final Map<String, Count> counts = new LinkedHashMap<>();
    final List<RowCache.Entry> output = new ArrayList<>();
    for (final var r : rows) {
      if (marked(r)) throw new IllegalArgumentException("input already has an adjusted target");
      final Count c = count(r, required(evidence, r.unit().issueId()), threshold.cap());
      counts.put(r.unit().issueId(), c);
      final Map<String, Double> values = new HashMap<>(r.row().values());
      final Set<String> absent = new HashSet<>(r.row().absent());
      values.put(VERSION, 1.0); values.put(CAP, (double) threshold.cap());
      if (c.total() == null) {
        for (final String col : COLUMNS) if (!Set.of(VERSION, CAP).contains(col)) {
          values.remove(col); absent.add(col);
        }
      } else {
        values.put(DAYS, c.total().doubleValue()); values.put(PREFIX, (double) c.prefix());
        values.put(IMPLEMENTATION, (double) c.implementation()); values.put(REVIEW, (double) c.review());
        values.put(WAIT, (double) c.waitDays()); values.put(ORIGINAL, c.originalCommits() > 0 ? 1.0 : 0.0);
        absent.removeAll(COLUMNS);
      }
      output.add(new RowCache.Entry(r.unit(), new Row(r.row().unitId(), values, absent), r.parts()));
    }
    return new Result(threshold, List.copyOf(output), Map.copyOf(counts));
  }
}
