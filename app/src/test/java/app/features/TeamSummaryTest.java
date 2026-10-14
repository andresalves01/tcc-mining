package app.features;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Median and spread of the team's history, the revised summary: a member without a measure is
 * left out, and a team left with nobody has no summary rather than a summary of zero.
 */
class TeamSummaryTest {

  @Test
  @DisplayName("the median is the middle member, or the mean of the two middle ones")
  void median() {
    assertEquals(40.0, TeamSummary.of(List.of(900.0, 10.0, 40.0)).orElseThrow().median(), 1e-9,
        "the one member with 900 lines moves the mean to 316.7 but not the median");
    assertEquals(25.0, TeamSummary.of(List.of(40.0, 10.0, 900.0, 0.0)).orElseThrow().median(),
        1e-9, "sorted 0, 10, 40, 900: the middle two are 10 and 40");
  }

  @Test
  @DisplayName("the spread is the deviation the team means already use, dividing by n")
  void spread() {
    // Values 2, 4, 4, 4, 5, 5, 7, 9: mean 5, squared deviations summing to 32.
    // Over n = 8 that is 4, so the deviation is 2. Over n - 1 it would be sqrt(32/7) = 2.138.
    final TeamSummary summary =
        TeamSummary.of(List.of(9.0, 2.0, 4.0, 5.0, 4.0, 7.0, 4.0, 5.0)).orElseThrow();

    assertEquals(4.5, summary.median(), 1e-9);
    assertEquals(2.0, summary.deviation(), 1e-9, "the population deviation, not the sample one");
  }

  @Test
  @DisplayName("a single member with a measure has zero spread")
  void singleMember() {
    final TeamSummary summary = TeamSummary.of(List.of(42.0)).orElseThrow();

    assertEquals(42.0, summary.median(), 1e-9);
    assertEquals(0.0, summary.deviation(), 1e-9, "observed, and spread by nothing");
  }

  @Test
  @DisplayName("a team with no members has no summary rather than a summary of zero")
  void noMembers() {
    assertEquals(Optional.empty(), TeamSummary.of(List.of()),
        "a zero would enter the model as a fact about a team that was not there");
  }

  @Test
  @DisplayName("a member without a measure is left out, not counted as zero")
  void memberWithoutMeasure() {
    final TeamSummary summary = TeamSummary.of(Arrays.asList(10.0, null, 30.0)).orElseThrow();

    assertEquals(20.0, summary.median(), 1e-9, "counted as zero, the median would be 10");
    assertEquals(10.0, summary.deviation(), 1e-9, "over the two measured members only");
  }

  @Test
  @DisplayName("a team whose members have no measure has no summary")
  void noMeasures() {
    assertEquals(Optional.empty(), TeamSummary.of(Arrays.asList(null, null)),
        "nobody's history was measured: missing, not zero experience");
  }
}
