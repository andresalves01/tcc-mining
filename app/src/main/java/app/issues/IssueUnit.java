package app.issues;

import app.study.Outcome;
import java.time.Duration;
import java.time.Instant;

/**
 * One studied issue with its outcome.
 *
 * @param reporter the canonical person who opened it, known at creation
 * @param executor the canonical author of the first linked commit visible before the resolution,
 *     empty when there is none: known only afterwards, read only by the conditioned reference
 * @param reason the resolution in force at the first resolution (Fixed, Duplicate, ...)
 * @param moved whether the issue changed Jira project at some point (the sensitivity without them)
 * @param startedAt where the duration starts: the first In Progress, where {@code executor} is the
 *     person known at that instant; the creation when no start is given
 * @param businessDays the whole business days from the start to the first resolution, the time
 *     back in Open or Reopened left out ({@link BusinessDays}), set only in the in-progress
 *     similarity chain, whose target they are; null everywhere else, where the target is the hours
 */
public record IssueUnit(String issueId, String key, String project, String reporter,
    String executor, Instant createdAt, Instant resolvedAt, String reason, boolean moved,
    Instant startedAt, Integer businessDays) implements Outcome {

  /** An issue whose target is its hours from {@code startedAt}. */
  public IssueUnit(final String issueId, final String key, final String project,
      final String reporter, final String executor, final Instant createdAt,
      final Instant resolvedAt, final String reason, final boolean moved,
      final Instant startedAt) {
    this(issueId, key, project, reporter, executor, createdAt, resolvedAt, reason, moved,
        startedAt, null);
  }

  /** The same issue with its target in {@code days} business days. */
  public IssueUnit withBusinessDays(final int days) {
    return new IssueUnit(issueId, key, project, reporter, executor, createdAt, resolvedAt, reason,
        moved, startedAt, days);
  }

  /** Hours from the start to the first resolution. */
  @Override
  public double durationHours() {
    return Duration.between(startedAt, resolvedAt).toMillis() / 3_600_000.0;
  }

  /**
   * The target: the business days when the unit carries them, which {@link
   * BusinessDays#population} sets. The model stages never read the hours here: both refuse a unit
   * without business days ({@link ElasticNetSelection#run(java.util.List, int)}, {@link
   * SimilarityScoring}).
   */
  @Override
  public double duration() {
    return businessDays == null ? durationHours() : businessDays;
  }
}
