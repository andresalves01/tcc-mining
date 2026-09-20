package app.labels;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One training row: an issue, the developer who wrote its code, the interval, and the demand.
 *
 * <p>{@code linesDeleted} is carried alongside {@code linesAdded} and is never netted against it.
 * That holds even when a deleted method's metrics could not be recovered: the lines are observed in
 * the diff regardless of whether the parser left a record of the entity they belonged to, so a gap
 * in the method metrics must not silently shrink the volume as well.
 *
 * @param methods deduplicated by long name across the issue's commits; empty means no changed
 *     method was recoverable, which excludes the issue rather than scoring it as zero complexity
 */
public record IssueLabel(
    String issueId,
    String projectName,
    Instant createdAt,
    Instant resolvedAt,
    String canonicalAuthorId,
    Instant firstCommitAt,
    int commits,
    long javaLinesAdded,
    long javaLinesDeleted,
    List<ChangedMethod> methods) {

  /** Fractional days, because whole days admit {@code TTC = 0} and {@code ln(0)} is undefined. */
  public double ttcDays() {
    return Duration.between(createdAt, resolvedAt).toMillis() / 86_400_000.0;
  }

  public long javaLinesChanged() {
    return javaLinesAdded + javaLinesDeleted;
  }

  /**
   * Mean cyclomatic complexity over the methods this issue changed.
   *
   * <p>Empty rather than zero when nothing was recoverable. A method whose prior state the corpus
   * never recorded is unmeasured, and an unmeasured method is not a method of complexity one, let
   * alone zero — the difference decides whether the issue is excluded or silently pulls the mean
   * down.
   */
  public OptionalDouble meanMccc() {
    return methods.stream().mapToDouble(ChangedMethod::mccc).average();
  }

  public OptionalDouble meanNl() {
    return methods.stream().mapToDouble(ChangedMethod::nl).average();
  }

  public boolean hasRecoverableMethods() {
    return !methods.isEmpty();
  }
}
