package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The references the model has to beat, and the rule that keeps them honest. */
class BaselinesTest {

  private static Observation row(final String project, final String developer, final double ttc) {
    return new Observation(project + developer + ttc, project, developer, ttc, new double[] {0});
  }

  @Test
  @DisplayName("the per-pair reference is built only from the side it was given")
  void noOutcomeFromTheScoredSideTakesPart() {
    // The whole point of the reference is that it knows nothing about the partition it scores.
    final List<Observation> fit = List.of(row("p", "alice", 2), row("p", "alice", 4));
    final ToDoubleFunction<Observation> reference = Baselines.perPair(fit);

    assertEquals(Math.log(4 + 1e-6), reference.applyAsDouble(row("p", "alice", 999)), 1e-9,
        "the upper median of alice's two fit outcomes, whatever the scored row turned out to be");
  }

  @Test
  @DisplayName("an unseen pair falls back to the project, and an unseen project to the corpus")
  void theFallbackChainIsStated() {
    final List<Observation> fit = List.of(
        row("p", "alice", 2), row("p", "bob", 8), row("outro", "carol", 50));
    final ToDoubleFunction<Observation> reference = Baselines.perPair(fit);

    assertEquals(Math.log(8 + 1e-6), reference.applyAsDouble(row("p", "novo", 1)), 1e-9,
        "no history for this pair, so the project's median stands in");
    assertEquals(Math.log(8 + 1e-6), reference.applyAsDouble(row("desconhecido", "novo", 1)), 1e-9,
        "and with no project either, the corpus median");
  }

  @Test
  @DisplayName("how often the pair is actually found is measured, not assumed")
  void coverageIsMeasured() {
    final List<Observation> fit = List.of(row("p", "alice", 2));
    final List<Observation> scored = List.of(row("p", "alice", 3), row("p", "bob", 3));

    assertEquals(0.5, Baselines.pairCoverage(fit, scored), 1e-9,
        "a reference that mostly falls back is not a per-pair reference, and the report has to "
            + "be able to say which it was");
  }

  @Test
  @DisplayName("guessing the same number every time is the floor a model has to clear")
  void theConstantFloor() {
    final List<Observation> fit = List.of(row("p", "a", 1), row("p", "b", 5), row("p", "c", 9));

    assertEquals(Math.log(5 + 1e-6),
        Baselines.constant(fit).applyAsDouble(row("p", "z", 100)), 1e-9);
  }
}
