package app.features;

/**
 * What one developer had accumulated in one project before an instant.
 *
 * <p>Both line counts are carried so that {@code LOC_tot} can be defined over the language
 * {@code McCC} is measured in or over every file, without re-reading the ledger to change the
 * definition. Neither is netted: added plus deleted, as {@code Seniority.allLines()} explains.
 *
 * @param tenureDays from the pair's first commit to the instant asked about, which is calendar time
 *     rather than work done -- the two are kept apart because they turn out to behave differently
 */
public record DeveloperHistory(long javaLines, long allLines, int commits, double tenureDays) {

  /** A developer who had not yet committed in this project: zero volume, not absent data. */
  public static DeveloperHistory none() {
    return new DeveloperHistory(0, 0, 0, 0);
  }
}
