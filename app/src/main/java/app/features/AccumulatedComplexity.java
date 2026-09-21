package app.features;

/**
 * The complexity of the code a developer had already touched, summed over their prior commits.
 *
 * <p>Counted <b>once per commit</b>, so a method touched in two commits months apart contributes
 * twice. That is deliberately not the rule the task metrics use, which count a method once per
 * issue: there the unit is one piece of work being described, here it is work done repeatedly, and
 * collapsing the second visit would erase it. {@link app.labels.MethodDeduplication} carries the
 * same note from the other side.
 *
 * @param unrecoverable methods the commits touched whose {@code McCC} the corpus never recorded.
 *     They are absent from {@code mccc}, not counted as zero, and the study reports this coverage
 *     rather than reading it as a history of simple code
 */
public record AccumulatedComplexity(double mccc, double nl, int methods, int unrecoverable) {

  /** Share of the methods reached whose metrics were recoverable, or empty when none were reached. */
  public java.util.OptionalDouble coverage() {
    final int reached = methods + unrecoverable;
    return reached == 0 ? java.util.OptionalDouble.empty()
        : java.util.OptionalDouble.of((double) methods / reached);
  }
}
