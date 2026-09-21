package app.features;

import java.util.Optional;

/**
 * Mean and spread of one accumulated indicator across the active team.
 *
 * <p>The deviation is the population one, dividing by {@code n}. That is what the study declares,
 * and it settles the single-member case by arithmetic rather than by a special rule: one member
 * has no spread, and a sample deviation would divide by zero to say so.
 *
 * <p>These come from the observable values alone. The weights of the individual score are estimated
 * by the regression and must not be applied here, or the team context would carry the fit's own
 * opinion of experience back into its inputs.
 */
public record TeamStatistics(double mean, double deviation) {

  /** Empty for an empty team, which the study excludes from the full model and counts apart. */
  public static Optional<TeamStatistics> of(final double[] values) {
    if (values.length == 0) {
      return Optional.empty();
    }
    double sum = 0;
    for (final double value : values) {
      sum += value;
    }
    final double mean = sum / values.length;
    double squares = 0;
    for (final double value : values) {
      squares += (value - mean) * (value - mean);
    }
    return Optional.of(new TeamStatistics(mean, Math.sqrt(squares / values.length)));
  }
}
