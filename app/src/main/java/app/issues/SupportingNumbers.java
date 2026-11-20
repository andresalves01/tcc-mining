package app.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The numbers that support the text of the first-commit study: how many issues the filter of the
 * first commit's share meets with a commit before their creation, before their In Progress, or
 * alone; and which three issues of the outer training the worked example explains.
 */
public final class SupportingNumbers {

  /**
   * The counts over the issues resolved as Fixed that have at least one commit that counts; each
   * count is its own, not a step of the funnel, so an issue can be in several.
   *
   * @param base the issues read, Fixed at their first resolution
   * @param withCode of them, those with at least one commit that counts: the denominator
   * @param createdAfterFirstCommit of those, created strictly after their first commit: the
   *     issues with a commit before their creation
   * @param createdAtFirstCommit of those, created at the very instant of their first commit
   * @param withInProgress of those, the ones that had an In Progress: the denominator of the next
   * @param firstBeforeInProgress of those, the first commit strictly before the first In Progress
   * @param single of those, exactly one commit that counts, before the duplicates are removed
   */
  public record Counts(int base, int withCode, int createdAfterFirstCommit,
      int createdAtFirstCommit,
      int withInProgress, int firstBeforeInProgress, int single) {}

  private SupportingNumbers() {}

  /**
   * The counts over {@code issues}. The first commit is the earliest that counts, before the
   * duplicates are removed, as the first rule of the filter reads it. {@code inProgress} gives an
   * issue's first In Progress, and null when it had none: the creation is never used instead.
   */
  public static Counts count(final Collection<FirstCommit.Issue> issues,
      final Function<String, Long> inProgress) {
    int withCode = 0;
    int createdAfter = 0;
    int createdAt = 0;
    int withInProgress = 0;
    int before = 0;
    int single = 0;
    for (final FirstCommit.Issue i : issues) {
      if (i.code().isEmpty()) {
        continue;
      }
      withCode++;
      final long first = i.code().stream().mapToLong(FirstCommit.Code::at).min().orElseThrow();
      if (i.created() > first) {
        createdAfter++;
      } else if (i.created() == first) {
        createdAt++;
      }
      final Long started = inProgress.apply(i.id());
      if (started != null) {
        withInProgress++;
        if (first < started) {
          before++;
        }
      }
      if (i.code().size() == 1) {
        single++;
      }
    }
    return new Counts(issues.size(), withCode, createdAfter, createdAt,
        withInProgress, before, single);
  }

  /** An issue of the outer training with how its target is made. */
  public record Entry(String id, String key, FirstCommit.Explanation explanation) {}

  /**
   * One worked example.
   *
   * @param criterion the criterion that chose it
   * @param value its value for that criterion, in business days
   * @param note empty, or why it is not the criterion's best issue
   */
  public record Pick(String criterion, Entry entry, long value, String note) {}

  /** The calendar business days minus the business days of the study. */
  public static final String GAP = "calendar_minus_study";

  /** The business days the cap took out of the waits. */
  public static final String CAPPED = "capped";

  /**
   * The business days after the last mark that did not count ({@link
   * FirstCommit.Explanation#tailUncounted}): where the tail weighed most on the gap between the
   * calendar and the study.
   */
  public static final String TAIL = "tail_not_counted";

  /**
   * The worked examples among {@code entries}, which the caller draws from the outer training
   * alone (under the revised rule, from its issues that teach): the largest gap between the
   * calendar's business days and the study's, then the most business days cut from the waits,
   * then the most business days after the last mark that did not count. Ties go to the smaller
   * id. An issue already chosen is skipped, and the next best is taken with a note. With fewer
   * issues than criteria, fewer are picked.
   */
  public static List<Pick> choose(final Collection<Entry> entries) {
    final List<Pick> picks = new ArrayList<>();
    final Set<String> taken = new LinkedHashSet<>();
    final List<Entry> pool = entries.stream().sorted(Comparator.comparing(Entry::id)).toList();
    pick(picks, taken, pool, GAP, e -> e.explanation().calendar() - e.explanation().total());
    pick(picks, taken, pool, CAPPED, e -> e.explanation().count().capped());
    pick(picks, taken, pool, TAIL, e -> e.explanation().tailUncounted());
    return List.copyOf(picks);
  }

  private static void pick(final List<Pick> picks, final Set<String> taken,
      final List<Entry> pool, final String criterion, final ToLongFunction<Entry> value) {
    // pool is ordered by id, and the sort is stable: the smaller id wins a tie
    final List<Entry> ranked = pool.stream()
        .sorted(Comparator.comparingLong(value).reversed()).toList();
    final List<String> skipped = new ArrayList<>();
    for (final Entry e : ranked) {
      if (taken.contains(e.id())) {
        final Pick by = picks.stream().filter(p -> p.entry().id().equals(e.id())).findFirst()
            .orElseThrow();
        skipped.add(e.id() + " (already chosen for " + by.criterion() + ")");
        continue;
      }
      taken.add(e.id());
      final String note = skipped.isEmpty() ? "" : "the best by " + criterion + " was "
          + String.join(", ", skipped) + "; this is the next distinct issue";
      picks.add(new Pick(criterion, e, value.applyAsLong(e), note));
      return;
    }
  }
}
