package app.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mean and spread of the team's experience, with the divisor the study publishes. */
class TeamStatisticsTest {

  @Test
  @DisplayName("an empty team has no statistics rather than statistics of zero")
  void emptyTeam() {
    assertEquals(Optional.empty(), TeamStatistics.of(new double[0]),
        "the study excludes these observations from the full model and counts them; a zero mean "
            + "would enter the regression as a fact about a team that was not there");
  }

  @Test
  @DisplayName("a single member has no spread, by arithmetic and not by a special case")
  void singleMember() {
    final TeamStatistics statistics = TeamStatistics.of(new double[] {42}).orElseThrow();

    assertEquals(42.0, statistics.mean(), 1e-9);
    assertEquals(0.0, statistics.deviation(), 1e-9,
        "dividing by n answers this case; a sample deviation would divide by zero to say it");
  }

  @Test
  @DisplayName("the deviation divides by n, matching the published definition")
  void populationDivisor() {
    // Values 2, 4, 4, 4, 5, 5, 7, 9: mean 5, squared deviations summing to 32.
    // Over n = 8 that is 4, so the deviation is 2. Over n - 1 it would be sqrt(32/7) = 2.138.
    final TeamStatistics statistics =
        TeamStatistics.of(new double[] {2, 4, 4, 4, 5, 5, 7, 9}).orElseThrow();

    assertEquals(5.0, statistics.mean(), 1e-9);
    assertEquals(2.0, statistics.deviation(), 1e-9, "the population deviation, not the sample one");
  }

  @Test
  @DisplayName("a team whose members all hold the same volume has no spread to report")
  void identicalMembers() {
    final TeamStatistics statistics =
        TeamStatistics.of(new double[] {100, 100, 100}).orElseThrow();

    assertEquals(100.0, statistics.mean(), 1e-9);
    assertTrue(statistics.deviation() < 1e-12, "identical members spread by nothing");
  }
}
