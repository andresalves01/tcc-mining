package app.paper5.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.Unit;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mandatory reference, and the four things section 9.1 forbids it from doing. */
class BaselineTest {

  private static final Instant T0 = Instant.parse("2018-01-01T00:00:00Z");

  private static Unit unit(final String id, final String project, final double hours) {
    return new Unit(id, project, "ana", T0, T0.plusSeconds((long) (hours * 3600)));
  }

  @Test
  @DisplayName("it predicts the project's own median, learned on the fit side")
  void theProjectMedian() {
    final Baseline baseline = Baseline.learnedOn(List.of(
        unit("a", "p", 10), unit("b", "p", 20), unit("c", "p", 60),
        unit("d", "q", 5)));

    assertEquals(20.0, baseline.predictHours(unit("x", "p", 999)),
        "the median of 10, 20 and 60 is 20 -- and the scored row's own 999 hours is not consulted");
    assertEquals(5.0, baseline.predictHours(unit("y", "q", 1)));
    assertEquals(2, baseline.projects());
  }

  @Test
  @DisplayName("a project absent from the fit side falls back to the global median, and is counted")
  void theGlobalFallback() {
    // Six test rows do this under the frozen population: the inherited-history rule is off, so
    // projects reach the test side that never appear in training.
    final Baseline baseline = Baseline.learnedOn(List.of(
        unit("a", "p", 10), unit("b", "p", 20), unit("c", "q", 30)));

    final Unit unseen = unit("z", "novo", 1);
    assertEquals(20.0, baseline.predictHours(unseen),
        "the median of all three training rows");
    assertEquals(1, baseline.fellBackOn(List.of(unseen, unit("w", "p", 1))),
        "and the count is reported, because it is a property of the population");
  }

  @Test
  @DisplayName("an even number of rows takes the mean of the two middle values")
  void medianOfAnEvenCount() {
    final Baseline baseline =
        Baseline.learnedOn(List.of(unit("a", "p", 10), unit("b", "p", 20)));

    assertEquals(15.0, baseline.predictHours(unit("x", "p", 1)));
  }

  @Test
  @DisplayName("the score of a perfect prediction is zero error and the gain is zero against itself")
  void scoringIsConsistent() {
    final double[] truth = {10, 20, 30, 40};
    final Score perfect = Score.of("perfeito", truth.clone(), truth, 100.0, 80.0);

    assertEquals(0.0, perfect.maeHours(), 1e-12);
    assertEquals(100.0, perfect.gainHours(), 1e-12);
    assertEquals(100.0, perfect.gainPercent(), 1e-9);
    assertEquals(100.0, perfect.gainOverConstantPercent(), 1e-9);
    assertEquals(1.0, perfect.spearman(), 1e-9, "a perfect prediction orders perfectly too");

    final Score self = Score.reference("obrigatoria", truth.clone(), truth, 80.0);
    assertEquals(0.0, self.gainHours(), 1e-12, "a reference gains nothing against itself");
  }

  @Test
  @DisplayName("the gain against the constant is reported apart, because the two can disagree")
  void theTwoGainsAreSeparate() {
    // The case this study is actually in: the mandatory reference is worse than a single number, so
    // the same model looks much better against one than against the other.
    final Score score = Score.of("x", new double[] {100, 100}, new double[] {0, 0},
        /* mandatory baseline MAE */ 200.0, /* best constant MAE */ 110.0);

    assertEquals(100.0, score.maeHours(), 1e-12);
    assertEquals(50.0, score.gainPercent(), 1e-9, "half the mandatory reference's error");
    assertEquals(100.0 * (110.0 - 100.0) / 110.0, score.gainOverConstantPercent(), 1e-9);
    assertTrue(score.gainPercent() > score.gainOverConstantPercent(),
        "and reporting only the first one would flatter the model");
  }

  @Test
  @DisplayName("the mean is the wrong constant for absolute error, and the median is the right one")
  void theMedianMinimisesAbsoluteError() {
    // Why the reference is a median: on a skewed target the mean sits in the tail where almost
    // nothing is. This is the property, not a property of this corpus.
    final double[] truth = {1, 2, 3, 4, 100};
    final double median = 3;
    final double mean = 22;

    assertTrue(maeOf(median, truth) < maeOf(mean, truth));
    for (final double candidate : new double[] {0, 1, 2, 2.5, 3.5, 4, 10, 50, 100}) {
      assertTrue(maeOf(median, truth) <= maeOf(candidate, truth) + 1e-12,
          "no constant beats the median: " + candidate);
    }
  }

  private static double maeOf(final double constant, final double[] truth) {
    double sum = 0;
    for (final double value : truth) {
      sum += Math.abs(constant - value);
    }
    return sum / truth.length;
  }

  @Test
  @DisplayName("days are hours over twenty-four, so the two columns can never disagree")
  void hoursAndDaysAgree() {
    final Score score = Score.of("x", new double[] {48, 0}, new double[] {0, 0}, 100.0, 100.0);

    assertEquals(24.0, score.maeHours(), 1e-12);
    assertEquals(1.0, score.maeDays(), 1e-12);
    assertTrue(score.gainHours() > 0);
  }
}
