package app.paper6;

import app.paper5.Outcome;
import java.time.Duration;
import java.time.Instant;

/**
 * One resolved issue as the transposition sees it.
 *
 * <p>Two people on one row, on purpose. {@code reporterId} is whoever opened the issue, known at
 * creation, and the literature's constructs describe them. {@code authorId} is the canonical author
 * of the commits linked to it, the person the author's own specification describes -- and the reason
 * that specification's developer columns sit in their own temporal class.
 *
 * @param key the Jira key, such as {@code JENA-1234}, so the text constructs can tell a reference to
 *     another issue from the issue naming itself
 * @param commits the linked commits, and the two line counts after it, are the change that resolved
 *     the issue: known only at the end, and read only by the ceiling profile
 */
public record IssueUnit(
    String issueId,
    String key,
    String project,
    String reporterId,
    String authorId,
    Instant createdAt,
    Instant resolvedAt,
    Instant firstCommitAt,
    int commits,
    long javaLinesAdded,
    long javaLinesDeleted) implements Outcome {

  /** Hours, the scale the primary table is reported on, like the pull-request study. */
  @Override
  public double durationHours() {
    return Duration.between(createdAt, resolvedAt).toMillis() / 3_600_000.0;
  }

  /**
   * Resolution minus the first linked commit. Negative means the issue was marked resolved before
   * any of the code that resolved it existed -- resolved outside its branch's lifecycle, which is
   * the one thing this study excludes.
   */
  public double postCommitHours() {
    return Duration.between(firstCommitAt, resolvedAt).toMillis() / 3_600_000.0;
  }
}
