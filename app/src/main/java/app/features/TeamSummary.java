package app.features;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Median and spread of one historical measure across the team: the revised team summary.
 *
 * <p>The spread is {@link TeamStatistics}'s deviation, the population one dividing by {@code n};
 * one measured member has no spread. The median, not the mean, because a single long-standing
 * member moves the mean of an accumulated volume far more than the middle of the team.
 *
 * <p>A member whose measure is unknown ({@code null}) is left out rather than counted as zero, and
 * a team left with nobody, or with no members at all, has no summary.
 */
public record TeamSummary(double median, double deviation) {

  /** Over the measured members, in any order; empty when none of them has a measure. */
  public static Optional<TeamSummary> of(final List<Double> members) {
    final double[] values =
        members.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted()
            .toArray();
    final int n = values.length;
    return TeamStatistics.of(values).map(statistics -> new TeamSummary(
        n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0,
        statistics.deviation()));
  }
}
