package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement that decides whether team selection has a basis, and the ways it can lie.
 *
 * <p>Every one of these tests exists because the first implementation got the answer wrong by
 * matching tasks on size alone: it reported 59.6% where matching on size and description reports
 * 55.8%, and the difference was task ordering answering a question asked about people.
 */
class DeveloperOrderingTest {

  /** {@code values} is {size, description}; the outcome is what really happened. */
  private static Observation row(final String developer, final double ttc,
      final double size, final double description) {

    return new Observation(developer + ttc, "p", developer, ttc,
        new double[] {size, description});
  }

  private static final int[] BOTH = {0, 1};

  @Test
  @DisplayName("the same person compared with themselves is not a comparison between people")
  void sameDeveloperIsSkipped() {
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 1, 0, 0), row("alice", 9, 0, 0)),
        Observation::logTtc, BOTH, 0.5);

    assertEquals(0, result.pairs());
  }

  @Test
  @DisplayName("a column left unmatched lets the task answer for the person")
  void unmatchedColumnsContaminate() {
    // Alice is slow on a task with a long description, Bob fast on a short one. A predictor that
    // only reads the description gets both right -- and has said nothing about alice or bob.
    final List<Observation> rows = List.of(
        row("alice", 10, 0, 5), row("bob", 1, 0, 0));

    final DeveloperOrdering.Result matchingSizeOnly =
        DeveloperOrdering.of(rows, o -> o.values()[1], new int[] {0}, 0.5);
    final DeveloperOrdering.Result matchingBoth =
        DeveloperOrdering.of(rows, o -> o.values()[1], BOTH, 0.5);

    assertEquals(1, matchingSizeOnly.pairs());
    assertEquals(1.0, matchingSizeOnly.agreement(), 1e-9,
        "with the description unmatched it scores a perfect hit on a task attribute");
    assertEquals(0, matchingBoth.pairs(),
        "matched on both, the pair is correctly refused as not comparable");
  }

  @Test
  @DisplayName("a prediction identical for two people ties every pair rather than scoring zero")
  void aConstantPredictorDistinguishesNothing() {
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 10, 0, 0), row("bob", 1, 0, 0)),
        o -> 3.0, BOTH, 0.5);

    assertEquals(0, result.pairs(),
        "no pair was decided, which the report has to show as 'does not distinguish' and not as "
            + "an agreement of zero");
  }

  @Test
  @DisplayName("a predictor that orders people correctly scores above the coin flip")
  void aWorkingPredictorScores() {
    final List<Observation> rows = List.of(
        row("alice", 10, 0, 0), row("bob", 1, 0, 0),
        row("alice", 12, 0, 0), row("carol", 2, 0, 0));

    final DeveloperOrdering.Result result = DeveloperOrdering.of(rows,
        o -> o.developer().equals("alice") ? 5.0 : 0.0, BOTH, 0.5);

    assertEquals(1.0, result.agreement(), 1e-9);
    assertEquals(4, result.pairs(), "alice's two against bob and carol");
  }

  @Test
  @DisplayName("people in different projects are never compared")
  void projectsAreKeptApart() {
    final Observation elsewhere = new Observation("x", "outro", "bob", 1.0, new double[] {0, 0});
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 10, 0, 0), elsewhere), Observation::logTtc, BOTH, 0.5);

    assertEquals(0, result.pairs());
  }
}
