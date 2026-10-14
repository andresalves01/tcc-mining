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
 * @param startedAt where the duration starts: the creation in the main study; the first In
 *     Progress in the secondary one, where {@code executor} is the person known at that instant
 */
public record IssueUnit(String issueId, String key, String project, String reporter,
    String executor, Instant createdAt, Instant resolvedAt, String reason, boolean moved,
    Instant startedAt) implements Outcome {

  /** An issue whose duration starts at its creation. */
  public IssueUnit(final String issueId, final String key, final String project,
      final String reporter, final String executor, final Instant createdAt,
      final Instant resolvedAt, final String reason, final boolean moved) {
    this(issueId, key, project, reporter, executor, createdAt, resolvedAt, reason, moved,
        createdAt);
  }

  /** Hours from the start to the first resolution. */
  @Override
  public double durationHours() {
    return Duration.between(startedAt, resolvedAt).toMillis() / 3_600_000.0;
  }
}
