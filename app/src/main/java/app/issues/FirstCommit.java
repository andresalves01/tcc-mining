package app.issues;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The first-commit population and its target: issues whose first commit starts the work and whose
 * code piles up after it, up to the first resolution.
 *
 * <p><b>Selection</b>, in this order, the first rule an issue fails being its verdict ({@link
 * Rule}): a commit that counts; created before the first one; at least two commits once the
 * duplicates are gone ({@link #dedupe}); the first commit below {@link #SHARE} of the issue's
 * changed lines; code on at least two dates; no commit linked to another issue; a target of at
 * least one business day. The dates are cut at the day boundary of the first commit's author,
 * read at that commit ({@link Boundary}).
 *
 * <p><b>Target.</b> From the first commit to the first resolution. The marks are the commits and
 * the human patches attached after the first commit; each wait between consecutive marks counts its
 * business days up to a cap learned once on the inner fitting ({@link #threshold}), never fewer than
 * its dates with explicit human activity. After the last mark only the dates with a human comment,
 * attachment or review count. The status is not read.
 */
public final class FirstCommit {

  /** The first commit must carry less than this share of the issue's changed lines. */
  public static final double SHARE = 0.5;

  /** The limits of the first commit's share that the audit also reports. */
  public static final List<Double> SENSITIVITY = List.of(0.3, 0.5, 0.7);

  /** An attachment whose file name says it is a patch. */
  public static final Pattern PATCH = Pattern.compile("(?i)patch|\\.diff$");

  /** The population in words, for the run records. */
  public static final String POPULATION = "issues resolved as Fixed at their first resolution, "
      + "created before their first commit that counts, with at least two commits once "
      + "duplicates are gone, the first commit below 50% of the changed lines of every file, "
      + "code on at least two dates, no commit linked to another issue, and a target of at least "
      + "one business day; the split is drawn over them alone";

  /**
   * The k of the wait fence, Q3 + k IQR, of the official run. Not to be confused with the usual
   * flow's k of the tail cut ({@code TailCut.USUAL_FLOW_K}), a fence over the duration.
   */
  public static final double DEFAULT_WAIT_K = 1.5;

  /** The k that cuts no wait: an infinite fence, so the cap is the largest wait. */
  public static final double NO_WAIT_CAP = Double.POSITIVE_INFINITY;

  /** The rules in words, for the audit. */
  public static final String RULE = rule(DEFAULT_WAIT_K);

  /** The run setting naming the wait fence's k: empty is {@link #DEFAULT_WAIT_K}, {@code none}. */
  public static final String WAIT_FENCE_SETTING = "WAIT_FENCE_K";

  private static final String NONE = "none";

  /** The fence in words for a k: {@code Q3 + 1.5 IQR}, or that no wait is capped. */
  public static String fenceText(final double k) {
    return k == NO_WAIT_CAP ? "no cap on the waits" : "Q3 + " + number(k) + " IQR";
  }

  private static String number(final double k) {
    return k == Math.rint(k) ? Long.toString((long) k) : Double.toString(k);
  }

  /** The rules in words, for the audit, with the wait fence at {@code k}. */
  public static String rule(final double k) {
    final String fence = k == NO_WAIT_CAP ? "no cap on the waits: every wait counted, never "
        + "below" : "capped at the largest gap not above " + fenceText(k)
        + " of the inner fitting's gaps, never below";
    return "first commit to first resolution; marks are the commits "
        + "and the human patches after the first commit; business days between marks "
        + fence + " the "
        + "dates with explicit human activity; after the last mark only dates with human comments, "
        + "attachments or reviews; status not read";
  }

  /**
   * The k a run setting names: empty is {@link #DEFAULT_WAIT_K}, {@code none} is {@link
   * #NO_WAIT_CAP}; zero, a negative number, NaN, infinity or text is refused.
   */
  public static double waitFenceK(final String setting) {
    if (setting == null || setting.isBlank()) {
      return DEFAULT_WAIT_K;
    }
    final String s = setting.strip();
    if (NONE.equals(s)) {
      return NO_WAIT_CAP;
    }
    final double k;
    try {
      k = Double.parseDouble(s);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(WAIT_FENCE_SETTING + " must be a positive number or '"
          + NONE + "', got '" + setting + "'");
    }
    if (!(k > 0) || Double.isInfinite(k)) {
      throw new IllegalArgumentException(WAIT_FENCE_SETTING + " must be a positive number or '"
          + NONE + "', got '" + setting + "'");
    }
    return k;
  }

  /** A k as the run record and the audit write it: {@code none} or the number. */
  public static String waitFenceLabel(final double k) {
    return k == NO_WAIT_CAP ? NONE : Double.toString(k);
  }

  /** The matrix column of the target in business days, written by the extraction. */
  public static final String TARGET_COLUMN = "FC_target_days";

  /** The matrix column of the hour, in UTC, the target's dates turn at. */
  public static final String BOUNDARY_COLUMN = "FC_day_boundary_hour";

  /**
   * Decision 16a, a start attribute: the first commit's lines added plus removed in every file,
   * known at the first commit.
   */
  public static final String FIRST_LINES = "FC_first_commit_lines";

  /**
   * Decision 16a, a retrospective attribute: the first commit's share of the issue's changed
   * lines, whose total is known only at the first resolution.
   */
  public static final String FIRST_SHARE = "FC_first_commit_share";

  /** The two attributes of decision 16a, in this order. */
  public static final List<String> ATTRIBUTE_COLUMNS = List.of(FIRST_LINES, FIRST_SHARE);

  private static final long HOUR = 3_600_000L;

  /**
   * One commit that counts for an issue: human, authored before the first resolution, not a merge;
   * a native commit or an original recovered from a pull request.
   *
   * @param author the canonical person who wrote it
   * @param lines lines added plus removed in every file; null when unknown
   * @param title the first line of its message
   * @param others the other issues it is linked to, by key
   */
  public record Code(String sha, String author, long at, Long lines, String title,
      Set<String> others, boolean original) {}

  /**
   * What the rules read of one issue resolved as Fixed at its first resolution.
   *
   * @param code its commits that count, in any order, duplicates included
   * @param patches the instants of its human patch attachments
   * @param activity the instants of its human comments, attachments and reviews
   */
  public record Issue(String id, String key, String project, long created, long resolved,
      List<Code> code, List<Long> patches, List<Long> activity) {}

  /** The day boundary, an hour in UTC, of {@code author} working in {@code project} at {@code at}. */
  @FunctionalInterface
  public interface Boundary {
    int hour(String author, String project, long at);
  }

  /** Why an issue leaves, in the order the rules are applied. */
  public enum Rule {
    NO_CODE("sem commit que conta"),
    CREATED_AFTER_FIRST_COMMIT("criada no instante do primeiro commit ou depois"),
    FEWER_THAN_TWO("menos de 2 commits depois das duplicatas"),
    UNKNOWN_SIZE("tamanho de algum commit desconhecido, ou nenhuma linha alterada"),
    FIRST_TOO_LARGE("primeiro commit com o limite das linhas ou mais"),
    ONE_DAY("código num só dia"),
    SHARED("commit compartilhado com outra issue"),
    ZERO("alvo 0");

    private final String label;

    Rule(final String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** How many commits each duplicate rule removed from one issue. */
  public record Duplicates(int sameAuthorDate, int sameTitleLines) {}

  /**
   * One issue under the rules.
   *
   * @param failed the first rule it fails; null when it is in the population
   * @param kept its commits after the duplicates, by authoring instant then hash
   * @param firstShare the first commit's share of the changed lines; null before that rule
   * @param hour the day boundary its dates are cut at; null before the rule of the dates
   * @param codeDays the dates with code at that boundary; 0 before that rule
   */
  public record Verdict(Rule failed, List<Code> kept, Duplicates duplicates, Double firstShare,
      Integer hour, int codeDays) {

    public boolean eligible() {
      return failed == null;
    }
  }

  /**
   * One issue's target.
   *
   * @param total the business days counted: {@code implementation + tail}
   * @param implementation from the first commit to the last mark, each wait capped
   * @param tail the dates with human activity after the last mark
   * @param capped the business days the cap took out of the waits
   * @param marks the commits and patches the waits run between
   * @param patches how many of the marks are patches
   */
  public record Count(int total, int implementation, int tail, int capped, int marks,
      int patches) {}

  /**
   * The cap on the waits between marks, learned once.
   *
   * @param fence Q3 + 1.5 IQR of the gaps
   * @param cap the largest gap not above the fence
   * @param gaps how many gaps it was learned on
   */
  public record Threshold(double q1, double median, double q3, double fence, int cap, int gaps) {

    /** This threshold, refused when its cap is below one business day. */
    public Threshold requirePositive() {
      if (cap < 1) {
        throw new IllegalStateException("a cap of " + cap + " business days: the zero rule of "
            + "the population assumes a cap of at least one");
      }
      return this;
    }
  }

  private FirstCommit() {}

  // ------------------------------------------------------------------ selection

  private static final Comparator<Code> ORDER =
      Comparator.comparingLong(Code::at).thenComparing(Code::sha);

  /** Commits after the duplicates, and how many each rule removed. */
  record Kept(List<Code> code, Duplicates removed) {}

  /**
   * The commits that stay, by authoring instant then hash. A commit with the same author and the
   * same instant as an earlier one counts once. A commit with the same first message line and the
   * same changed lines as an earlier one at another instant counts once, the oldest kept; an
   * unknown size or an empty title matches nothing. A dropped commit hands the issues it also links
   * to the one that stays.
   */
  static Kept dedupe(final List<Code> code) {
    final List<Code> sorted = new ArrayList<>(code);
    sorted.sort(ORDER);
    final List<Code> kept = new ArrayList<>();
    final Map<String, Integer> byAuthorDate = new HashMap<>();
    final Map<String, Integer> byTitleLines = new HashMap<>();
    int sameAuthorDate = 0;
    int sameTitleLines = 0;
    for (final Code c : sorted) {
      final String authorDate = c.author() + "\u0000" + c.at();
      final Integer first = byAuthorDate.get(authorDate);
      if (first != null) {
        kept.set(first, absorb(kept.get(first), c));
        sameAuthorDate++;
        continue;
      }
      final String title = c.title() == null ? "" : c.title().strip();
      final String titleLines = title.isEmpty() || c.lines() == null ? null
          : title + "\u0000" + c.lines();
      final Integer replica = titleLines == null ? null : byTitleLines.get(titleLines);
      if (replica != null && kept.get(replica).at() != c.at()) {
        kept.set(replica, absorb(kept.get(replica), c));
        sameTitleLines++;
        continue;
      }
      byAuthorDate.put(authorDate, kept.size());
      if (titleLines != null) {
        byTitleLines.putIfAbsent(titleLines, kept.size());
      }
      kept.add(c);
    }
    return new Kept(List.copyOf(kept), new Duplicates(sameAuthorDate, sameTitleLines));
  }

  private static Code absorb(final Code kept, final Code dropped) {
    if (dropped.others().isEmpty()) {
      return kept;
    }
    final Set<String> others = new LinkedHashSet<>(kept.others());
    others.addAll(dropped.others());
    return new Code(kept.sha(), kept.author(), kept.at(), kept.lines(), kept.title(),
        Set.copyOf(others), kept.original());
  }

  /** The issue under the rules, the first commit below {@code limit} of the changed lines. */
  public static Verdict select(final Issue issue, final double limit, final Boundary boundary) {
    final Duplicates none = new Duplicates(0, 0);
    if (issue.code().isEmpty()) {
      return new Verdict(Rule.NO_CODE, List.of(), none, null, null, 0);
    }
    final long first = issue.code().stream().mapToLong(Code::at).min().orElseThrow();
    if (issue.created() >= first) {
      return new Verdict(Rule.CREATED_AFTER_FIRST_COMMIT, List.of(), none, null, null, 0);
    }
    final Kept k = dedupe(issue.code());
    final List<Code> kept = k.code();
    if (kept.size() < 2) {
      return new Verdict(Rule.FEWER_THAN_TWO, kept, k.removed(), null, null, 0);
    }
    long total = 0;
    boolean known = true;
    for (final Code c : kept) {
      if (c.lines() == null) {
        known = false;
      } else {
        total += c.lines();
      }
    }
    if (!known || total == 0) {
      return new Verdict(Rule.UNKNOWN_SIZE, kept, k.removed(), null, null, 0);
    }
    final double share = kept.getFirst().lines() / (double) total;
    if (share >= limit) {
      return new Verdict(Rule.FIRST_TOO_LARGE, kept, k.removed(), share, null, 0);
    }
    final Code head = kept.getFirst();
    final int hour = boundary.hour(head.author(), issue.project(), head.at());
    final int days = (int) kept.stream().map(c -> date(c.at(), hour)).distinct().count();
    if (days < 2) {
      return new Verdict(Rule.ONE_DAY, kept, k.removed(), share, hour, days);
    }
    if (kept.stream().anyMatch(c -> !c.others().isEmpty())) {
      return new Verdict(Rule.SHARED, kept, k.removed(), share, hour, days);
    }
    final Verdict in = new Verdict(null, kept, k.removed(), share, hour, days);
    // With a cap of at least one day, a target is above 0 exactly when it is uncapped.
    if (count(issue, in, Integer.MAX_VALUE).total() == 0) {
      return new Verdict(Rule.ZERO, kept, k.removed(), share, hour, days);
    }
    return in;
  }

  // ------------------------------------------------------------------ target

  static LocalDate date(final long at, final int hour) {
    return Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).minusHours(hour).toLocalDate();
  }

  /** The Monday-to-Friday dates after {@code a}'s date up to and including {@code b}'s. */
  private static Set<LocalDate> between(final long a, final long b, final int hour) {
    final Set<LocalDate> out = new HashSet<>();
    for (LocalDate d = date(a, hour).plusDays(1); !d.isAfter(date(b, hour)); d = d.plusDays(1)) {
      if (d.getDayOfWeek().getValue() <= 5) {
        out.add(d);
      }
    }
    return out;
  }

  private static Verdict requireCounted(final Verdict v) {
    if (v.hour() == null || v.kept().size() < 2) {
      throw new IllegalArgumentException("only an issue past the rule of the dates has a target");
    }
    return v;
  }

  /** The commits and the patches after the first commit and before the resolution, in order. */
  public static List<Long> marks(final Issue issue, final Verdict v) {
    requireCounted(v);
    final long start = v.kept().getFirst().at();
    final List<Long> out = new ArrayList<>();
    v.kept().forEach(c -> out.add(c.at()));
    for (final long p : issue.patches()) {
      if (p > start && p < issue.resolved()) {
        out.add(p);
      }
    }
    out.sort(Long::compare);
    return out;
  }

  /** The business days of each wait between consecutive marks, uncapped. */
  public static List<Integer> gaps(final Issue issue, final Verdict v) {
    final List<Long> m = marks(issue, v);
    final List<Integer> out = new ArrayList<>(m.size());
    for (int i = 1; i < m.size(); i++) {
      out.add(between(m.get(i - 1), m.get(i), v.hour()).size());
    }
    return out;
  }

  /**
   * One wait between two consecutive marks, as the target counts it.
   *
   * @param businessDays its business days, uncapped
   * @param active of them, the dates with explicit human activity or a mark: the floor of the cap
   * @param counted the business days the target counts: the cap, never below {@code active}
   * @param cut the business days the cap took out
   */
  public record Wait(long from, long to, int businessDays, int active, int counted, int cut) {}

  /**
   * How one issue's target is made: its marks, each wait with what the cap cut, the dates of the
   * tail, and the business days of the calendar over the same span.
   *
   * @param count the target and its parts
   * @param marks the commits and patches, in order
   * @param waits the waits between consecutive marks
   * @param tail the dates with human activity after the last mark, in order
   * @param calendar the business days from the first commit's date, excluded, to the resolution's
   * @param hour the day boundary the dates are cut at, as in {@link Verdict}
   */
  public record Explanation(Count count, List<Long> marks, List<Wait> waits,
      List<LocalDate> tail, int calendar, int hour) {

    /** The business days the target counts. */
    public int total() {
      return count.total();
    }
  }

  /** The issue's target with the waits capped at {@code cap} business days. */
  public static Count count(final Issue issue, final Verdict v, final int cap) {
    return explain(issue, v, cap).count();
  }

  /** The date of an instant at the day boundary {@code hour}. */
  public static LocalDate dateOf(final long at, final int hour) {
    return date(at, hour);
  }

  /**
   * The issue's target with the waits capped at {@code cap} business days, and how it is made;
   * {@link #count} is its {@code count()}.
   */
  public static Explanation explain(final Issue issue, final Verdict v, final int cap) {
    final List<Long> m = marks(issue, v);
    final int hour = v.hour();
    final long start = m.getFirst();
    final long end = issue.resolved();
    final Set<LocalDate> whole = between(start, end, hour);
    final Set<LocalDate> active = new HashSet<>();
    for (final long a : issue.activity()) {
      if (a >= start && a <= end) {
        active.add(date(a, hour));
      }
    }
    active.retainAll(whole);
    final Set<LocalDate> explicit = new HashSet<>(active);
    m.forEach(t -> explicit.add(date(t, hour)));
    explicit.retainAll(whole);
    int implementation = 0;
    int capped = 0;
    final List<Wait> waits = new ArrayList<>(m.size());
    for (int i = 1; i < m.size(); i++) {
      final Set<LocalDate> wait = between(m.get(i - 1), m.get(i), hour);
      final Set<LocalDate> worked = new HashSet<>(wait);
      worked.retainAll(explicit);
      final int counted = Math.max(Math.min(wait.size(), cap), worked.size());
      implementation += counted;
      capped += wait.size() - counted;
      waits.add(new Wait(m.get(i - 1), m.get(i), wait.size(), worked.size(), counted,
          wait.size() - counted));
    }
    final Set<LocalDate> tail = between(m.getLast(), end, hour);
    tail.retainAll(active);
    final int total = implementation + tail.size();
    if (total > whole.size()) {
      throw new IllegalStateException("a date counted twice: " + issue.key());
    }
    final int patches = m.size() - v.kept().size();
    return new Explanation(
        new Count(total, implementation, tail.size(), capped, m.size(), patches), m,
        List.copyOf(waits), tail.stream().sorted().toList(), whole.size(), hour);
  }

  // ------------------------------------------------------------------ cap and summaries

  /** A distribution of business days, quantiles by {@link TailCut#quantile}. */
  public record Summary(int n, double min, double q1, double median, double q3, double p90,
      double max, double mean, long sum) {}

  /** The distribution of {@code values}; refuses none. */
  public static Summary summary(final List<Integer> values) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException("no value to summarise");
    }
    final double[] sorted = values.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
    final long sum = values.stream().mapToLong(Integer::longValue).sum();
    return new Summary(sorted.length, sorted[0], TailCut.quantile(sorted, .25),
        TailCut.quantile(sorted, .5), TailCut.quantile(sorted, .75), TailCut.quantile(sorted, .9),
        sorted[sorted.length - 1], sum / (double) sorted.length, sum);
  }

  /** The cap learned on {@code gaps}: the largest one not above Q3 + 1.5 IQR. */
  public static Threshold threshold(final List<Integer> gaps) {
    return threshold(gaps, DEFAULT_WAIT_K);
  }

  /**
   * The cap learned on {@code gaps}: the largest one not above Q3 + {@code k} IQR; with {@link
   * #NO_WAIT_CAP} the fence is infinite and the cap is the largest gap. A k that is not positive
   * is refused.
   */
  public static Threshold threshold(final List<Integer> gaps, final double k) {
    if (!(k > 0)) {
      throw new IllegalArgumentException("the wait fence's k must be positive, got " + k);
    }
    if (gaps.isEmpty()) {
      throw new IllegalArgumentException("no gap between marks to learn a cap from");
    }
    final double[] sorted = gaps.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
    final double q1 = TailCut.quantile(sorted, .25);
    final double q3 = TailCut.quantile(sorted, .75);
    // an explicit infinity: with an IQR of zero, 0 * Infinity would be NaN
    final double fence = k == NO_WAIT_CAP ? NO_WAIT_CAP : q3 + k * (q3 - q1);
    final int cap = gaps.stream().filter(g -> g <= fence).mapToInt(Integer::intValue).max()
        .orElseThrow();
    return new Threshold(q1, TailCut.quantile(sorted, .5), q3, fence, cap, gaps.size());
  }
}
