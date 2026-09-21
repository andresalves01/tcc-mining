package app.features;

import java.util.Optional;

/**
 * The collective context around a task: who was active, and how their experience was spread.
 *
 * <p>Statistics are empty when the team is, which the study excludes from the full model and counts
 * separately. Size is kept beside them because a mean and a deviation say nothing about how many
 * people produced them, and load counted over two people reads the same as load counted over
 * twenty.
 *
 * @param accumulatedComplexity empty for every row until the per-commit complexity table exists;
 *     absent, never zero, because an unmeasured team is not a team of complexity zero
 */
public record TeamContext(
    int size,
    Optional<TeamStatistics> accumulatedLines,
    Optional<TeamStatistics> accumulatedComplexity) {

  /** Nobody committed in the window. */
  public static TeamContext empty() {
    return new TeamContext(0, Optional.empty(), Optional.empty());
  }

  public boolean isEmpty() {
    return size == 0;
  }
}
