package app.features;

import java.util.Optional;

/**
 * What one developer had accumulated in one project before an instant.
 *
 * <p>Both line counts are carried so that {@code LOC_tot} can be defined over the language
 * {@code McCC} is measured in or over every file, without re-reading the ledger to change the
 * definition. Neither is netted: added plus deleted, as {@code Seniority.allLines()} explains.
 *
 * @param tenureDays from the pair's first commit to the instant asked about, which is calendar time
 *     rather than work done -- the two are kept apart because they turn out to behave differently
 * @param complexity empty when no per-commit complexity table has been built, which is a different
 *     statement from a developer who touched only simple code
 */
public record DeveloperHistory(long javaLines, long allLines, int commits, double tenureDays,
    Optional<AccumulatedComplexity> complexity) {

  /**
   * A developer who had not yet committed in this project: zero volume, not absent data.
   *
   * @param withComplexity whether a complexity table exists, so that a first task reads as an
   *     accumulated complexity of zero rather than as an unmeasured one
   */
  public static DeveloperHistory none(final boolean withComplexity) {
    return new DeveloperHistory(0, 0, 0, 0, withComplexity
        ? Optional.of(new AccumulatedComplexity(0, 0, 0, 0)) : Optional.empty());
  }
}
