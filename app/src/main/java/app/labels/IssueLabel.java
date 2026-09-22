package app.labels;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * @param examinedAt when someone other than the author first looked at the work, splitting the
 *     interval into waiting and the cycle after it. Absent where the corpus records no external
 *     examination, which is 21,9% of merged pull requests and every issue label -- the issue
 *     pipeline has no such notion and leaves it empty
 * @param examOrigin how that first examination was recognised, absent for the same rows
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
    List<ChangedMethod> methods,
    Optional<Instant> examinedAt,
    Optional<ExamOrigin> examOrigin) {

  /**
   * The shape every issue label has: no external examination recorded, because the issue pipeline
   * does not observe one. Additive, so that no existing construction site had to change.
   */
  public IssueLabel(
      final String issueId,
      final String projectName,
      final Instant createdAt,
      final Instant resolvedAt,
      final String canonicalAuthorId,
      final Instant firstCommitAt,
      final int commits,
      final long javaLinesAdded,
      final long javaLinesDeleted,
      final List<ChangedMethod> methods) {

    this(issueId, projectName, createdAt, resolvedAt, canonicalAuthorId, firstCommitAt, commits,
        javaLinesAdded, javaLinesDeleted, methods, Optional.empty(), Optional.empty());
  }

  /** Fractional days, because whole days admit {@code TTC = 0} and {@code ln(0)} is undefined. */
  public double ttcDays() {
    return Duration.between(createdAt, resolvedAt).toMillis() / 86_400_000.0;
  }

  /**
   * Creation to the first external examination. Absent, never zero, where none was recorded: a
   * zero would assert the work was looked at the instant it appeared.
   */
  public OptionalDouble waitDays() {
    return examinedAt
        .map(at -> OptionalDouble.of(Duration.between(createdAt, at).toMillis() / 86_400_000.0))
        .orElseGet(OptionalDouble::empty);
  }

  /** The first external examination to resolution. Absent on the same rows, for the same reason. */
  public OptionalDouble authorDays() {
    return examinedAt
        .map(at -> OptionalDouble.of(Duration.between(at, resolvedAt).toMillis() / 86_400_000.0))
        .orElseGet(OptionalDouble::empty);
  }

  /** Da abertura a resolucao, sem os fins de semana. */
  public double workingDays() {
    return WorkingTime.daysBetween(createdAt, resolvedAt);
  }

  /** Da abertura ao primeiro exame, sem os fins de semana. Ausente pelo mesmo motivo de sempre. */
  public OptionalDouble workingWaitDays() {
    return examinedAt
        .map(at -> OptionalDouble.of(WorkingTime.daysBetween(createdAt, at)))
        .orElseGet(OptionalDouble::empty);
  }

  /** Do primeiro exame a resolucao, sem os fins de semana. */
  public OptionalDouble workingAuthorDays() {
    return examinedAt
        .map(at -> OptionalDouble.of(WorkingTime.daysBetween(at, resolvedAt)))
        .orElseGet(OptionalDouble::empty);
  }

  /** The duration this fit treats as the outcome, absent where the row cannot supply it. */
  public OptionalDouble durationOf(final TtcTarget target) {
    return switch (target) {
      case TOTAL -> OptionalDouble.of(ttcDays());
      case ESPERA -> waitDays();
      case AUTOR -> authorDays();
      case TOTAL_UTIL -> OptionalDouble.of(workingDays());
      case ESPERA_UTIL -> workingWaitDays();
      case AUTOR_UTIL -> workingAuthorDays();
    };
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
