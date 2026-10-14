package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement that decides whether team selection has a basis, and the ways it can lie.
 *
 * <p>Rows are built against the real column layout rather than a private one, because the whole
 * point of the matching is which of the model's own columns are held constant.
 */
class DeveloperOrderingTest {

  private static Observation row(final String developer, final double ttc,
      final double size, final double description, final String type) {

    final double[] values = new double[Columns.all().size()];
    values[Columns.taskSize()] = size;
    values[Columns.descriptionLength()] = description;
    for (final int i : Columns.typeIndices()) {
      values[i] = Columns.all().get(i).name().equals("type=" + type) ? 1 : 0;
    }
    return new Observation(developer + ttc, "p", developer, ttc, values);
  }

  @Test
  @DisplayName("the same person compared with themselves is not a comparison between people")
  void sameDeveloperIsSkipped() {
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 1, 0, 0, "Bug"), row("alice", 9, 0, 0, "Bug")),
        Observation::logTtc, DeveloperOrdering.Matching.ANY);

    assertEquals(0, result.pairs());
  }

  @Test
  @DisplayName("a column left unmatched lets the task answer for the person")
  void unmatchedColumnsContaminate() {
    // Alice is slow on a task with a long description, Bob fast on a short one. A predictor that
    // reads only the description gets both right -- and has said nothing about alice or bob.
    final List<Observation> rows =
        List.of(row("alice", 10, 0, 5, "Bug"), row("bob", 1, 0, 0, "Bug"));

    final DeveloperOrdering.Result loose = DeveloperOrdering.of(rows,
        o -> o.values()[Columns.descriptionLength()], DeveloperOrdering.Matching.SIZE);
    final DeveloperOrdering.Result tight = DeveloperOrdering.of(rows,
        o -> o.values()[Columns.descriptionLength()], DeveloperOrdering.Matching.TIGHT);

    assertEquals(1, loose.pairs());
    assertEquals(1.0, loose.agreement(), 1e-9,
        "with the description unmatched it scores a perfect hit on a task attribute");
    assertEquals(0, tight.pairs(),
        "matched on description too, the pair is correctly refused as not comparable");
  }

  @Test
  @DisplayName("matching on type keeps tasks of different kinds from being compared")
  void typeIsMatched() {
    final List<Observation> rows =
        List.of(row("alice", 10, 0, 0, "Bug"), row("bob", 1, 0, 0, "Task"));

    assertEquals(1, DeveloperOrdering.of(rows, Observation::logTtc,
        DeveloperOrdering.Matching.ANY).pairs());
    assertEquals(0, DeveloperOrdering.of(rows, Observation::logTtc,
        DeveloperOrdering.Matching.TYPE).pairs(), "different kinds of work, not compared");
  }

  @Test
  @DisplayName("a prediction identical for two people ties every pair rather than scoring zero")
  void aConstantPredictorDistinguishesNothing() {
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 10, 0, 0, "Bug"), row("bob", 1, 0, 0, "Bug")),
        o -> 3.0, DeveloperOrdering.Matching.ANY);

    assertEquals(0, result.pairs(),
        "no pair was decided, which the report has to show as 'does not distinguish' and not as "
            + "an agreement of zero");
  }

  @Test
  @DisplayName("a predictor that orders people correctly scores above the coin flip")
  void aWorkingPredictorScores() {
    final List<Observation> rows = List.of(
        row("alice", 10, 0, 0, "Bug"), row("bob", 1, 0, 0, "Bug"),
        row("alice", 12, 0, 0, "Bug"), row("carol", 2, 0, 0, "Bug"));

    final DeveloperOrdering.Result result = DeveloperOrdering.of(rows,
        o -> o.developer().equals("alice") ? 5.0 : 0.0, DeveloperOrdering.Matching.TIGHT);

    assertEquals(1.0, result.agreement(), 1e-9);
    assertEquals(4, result.pairs(), "alice's two against bob and carol");
  }

  @Test
  @DisplayName("people in different projects are never compared")
  void projectsAreKeptApart() {
    final Observation elsewhere =
        new Observation("x", "outro", "bob", 1.0, new double[Columns.all().size()]);
    final DeveloperOrdering.Result result = DeveloperOrdering.of(
        List.of(row("alice", 10, 0, 0, "Bug"), elsewhere), Observation::logTtc,
        DeveloperOrdering.Matching.ANY);

    assertEquals(0, result.pairs());
  }

  @Test
  @DisplayName("the gradient runs loosest to tightest, so a report cannot show only one of them")
  void theGradientIsOrdered() {
    assertEquals(5, DeveloperOrdering.Matching.GRADIENT.size());
    assertEquals(DeveloperOrdering.Matching.ANY, DeveloperOrdering.Matching.GRADIENT.get(0));
    assertEquals(DeveloperOrdering.Matching.TIGHT, DeveloperOrdering.Matching.GRADIENT.get(4));
    assertTrue(DeveloperOrdering.Matching.TIGHT.sizeTolerance()
        < DeveloperOrdering.Matching.SIZE.sizeTolerance(), "tighter means a smaller tolerance");
  }
}
