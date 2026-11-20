package app.issues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.study.model.Breakdown;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The error by band of the predicted duration, beside the one by band of the observed one. */
class PredictedBandsTest {

  /** The quartiles of the cut fitting rows, as the report records them: 2, 5 and 8 days. */
  private static final double[] TOPS = {2, 5, 8};

  @Test
  @DisplayName("the band comes from the prediction: observed 1 with a high prediction is in the "
      + "high band, and in the lowest band by observation")
  void bandFollowsThePrediction() {
    final PredictedBands.Reading r = PredictedBands.of(new double[] {20}, new double[] {1}, TOPS);
    assertEquals(List.of(0, 0, 0, 1), r.byPrediction().stream()
        .map(PredictedBands.Band::n).toList());
    assertEquals(List.of(1, 0, 0, 0), r.byObservation().stream()
        .map(PredictedBands.Band::n).toList());
    assertEquals(">8", r.byPrediction().get(3).label());
    assertEquals(19.0, r.byPrediction().get(3).mae());
  }

  @Test
  @DisplayName("a prediction equal to a limit falls in the band below, as the labels say")
  void limitIsHalfOpen() {
    final PredictedBands.Reading r = PredictedBands.of(new double[] {2, 5, 8, 8.01},
        new double[] {3, 3, 3, 3}, TOPS);
    assertEquals(List.of(1, 1, 1, 1), r.byPrediction().stream().map(PredictedBands.Band::n)
        .toList());
    assertEquals("<=2", r.byPrediction().get(0).label());
  }

  @Test
  @DisplayName("an empty band has n 0 and no figures, without an exception")
  void emptyBand() {
    final PredictedBands.Reading r = PredictedBands.of(new double[] {1}, new double[] {1}, TOPS);
    final PredictedBands.Band empty = r.byPrediction().get(2);
    assertEquals(0, empty.n());
    assertTrue(Double.isNaN(empty.mae()) && Double.isNaN(empty.medianError())
        && Double.isNaN(empty.bias()));
  }

  @Test
  @DisplayName("the error is the whole one: no clipping, the bias keeps its sign, the median is "
      + "of the absolute errors")
  void errorIsWhole() {
    final PredictedBands.Reading r = PredictedBands.of(new double[] {20, 30, 100},
        new double[] {1, 40, 50}, TOPS);
    final PredictedBands.Band high = r.byPrediction().get(3);
    assertEquals(3, high.n());
    assertEquals((19 + 10 + 50) / 3.0, high.mae(), 1e-12);
    assertEquals(19.0, high.medianError());
    assertEquals((-19 + 10 - 50) / 3.0, high.bias(), 1e-12);
  }

  @Test
  @DisplayName("by observation the bands are what Breakdown.byEdges gives")
  void observedBandsAgreeWithBreakdown() {
    final double[] predicted = {1, 4, 9, 3, 12, 6};
    final double[] observed = {2, 7, 5, 30, 8, 1};
    final List<Breakdown.Band> expected = Breakdown.byEdges(predicted, observed, TOPS,
        BusinessDays.bandLabels(TOPS));
    final List<PredictedBands.Band> got = PredictedBands.of(predicted, observed, TOPS)
        .byObservation();
    for (int b = 0; b < 4; b++) {
      assertEquals(expected.get(b).n(), got.get(b).n());
      assertEquals(expected.get(b).mae(), got.get(b).mae(), 1e-12);
      assertEquals(expected.get(b).medianError(), got.get(b).medianError(), 1e-12);
    }
  }

  @Test
  @DisplayName("the long band: the four counts, precision and recall in a known case")
  void longBand() {
    // predicted long: rows 0, 1, 2; observed long: rows 0, 3
    final PredictedBands.LongBand l = PredictedBands.of(new double[] {9, 10, 8.5, 1, 1},
        new double[] {9, 3, 3, 12, 1}, TOPS).longBand();
    assertEquals(1, l.both());
    assertEquals(2, l.predictedOnly());
    assertEquals(1, l.observedOnly());
    assertEquals(1, l.neither());
    assertEquals(1 / 3.0, l.precision(), 1e-12);
    assertEquals(1 / 2.0, l.recall(), 1e-12);
  }

  @Test
  @DisplayName("nothing predicted long gives no precision; nothing observed long, no recall")
  void longBandDivisionByZero() {
    final PredictedBands.LongBand noPrediction = PredictedBands.of(new double[] {1, 2},
        new double[] {20, 1}, TOPS).longBand();
    assertTrue(Double.isNaN(noPrediction.precision()));
    assertEquals(0.0, noPrediction.recall());
    final PredictedBands.LongBand noObserved = PredictedBands.of(new double[] {20, 2},
        new double[] {1, 1}, TOPS).longBand();
    assertEquals(0.0, noObserved.precision());
    assertTrue(Double.isNaN(noObserved.recall()));
  }

  @Test
  @DisplayName("the usual flow keeps the issues observed at or under the fence, the limits "
      + "unchanged")
  void usualFlow() {
    final PredictedBands.Reading r = PredictedBands.usualFlow(new double[] {20, 9, 9},
        new double[] {1, 30, 31}, TOPS, 30);
    assertEquals(List.of(0, 0, 0, 2), r.byPrediction().stream().map(PredictedBands.Band::n)
        .toList());
    assertEquals(">8", r.byPrediction().get(3).label());
    assertEquals(1, r.longBand().both());
    assertEquals(1, r.longBand().predictedOnly());
    assertEquals(0, r.longBand().observedOnly() + r.longBand().neither());
  }

  private static final List<String> TABLE = List.of(
      "id\tkey\tproject\tobserved_business_days\tglobal_median\tmodel_a\tmodel_b",
      "1\tP-1\tp\t5.0\t5.0\t4.5\t6.0", "2\tP-2\tp\t18.0\t5.0\t3.0\t7.5");

  @Test
  @DisplayName("the parser reads the observed column and each wanted model's column by name")
  void parsesTheTable() {
    final PredictedBands.Predictions p = PredictedBands.parse(TABLE,
        List.of("model_b", "model_a"));
    assertArrayEquals(new double[] {5, 18}, p.observed());
    assertArrayEquals(new double[] {6, 7.5}, p.predicted().get("model_b"));
    assertArrayEquals(new double[] {4.5, 3}, p.predicted().get("model_a"));
  }

  @Test
  @DisplayName("a model column that is missing is refused, by name")
  void missingColumnIsRefused() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> PredictedBands.parse(TABLE, List.of("model_a", "kikas_start")));
    assertTrue(e.getMessage().contains("kikas_start"));
  }

  @Test
  @DisplayName("the band tops and the usual-flow fence are read from the run's log lines")
  void readsTopsAndFenceFromTheLog() {
    final List<String> log = List.of("  noise",
        "  readings: usual flow (main reading): the scored issues at or below the k = 3 fence, "
            + "985 issues (fence 30.000 bd on the outer training set); all issues "
            + "(complementary reading): every scored issue, 996 issues",
        "  error bands: bands of the observed business days at the quartiles; here 2, 5.5 and 8 "
            + "business days, of the outer training rows fitted");
    assertArrayEquals(new double[] {2, 5.5, 8}, PredictedBands.bandTops(log));
    assertEquals(30.0, PredictedBands.usualFence(log));
    assertThrows(IllegalArgumentException.class, () -> PredictedBands.bandTops(List.of("x")));
    assertThrows(IllegalArgumentException.class, () -> PredictedBands.usualFence(List.of("x")));
  }

  @Test
  @DisplayName("the usual-flow fence is read from the revised design's line too, where all issues, "
      + "its main reading, come first and the usual flow, with its fence, second")
  void readsTheFenceFromTheRevisedLine() {
    assertEquals(30.0, PredictedBands.usualFence(List.of("  noise",
        "  readings: all issues (main reading): every scored issue, those above the k = 3 fence "
            + "included, 996 issues; usual flow (complementary reading): the scored issues at or "
            + "below the k = 3 fence, 985 issues (fence 30.000 bd on the outer training set)")));
    assertEquals(30.5, PredictedBands.usualFence(List.of(
        "  readings: all issues (main reading): y, 1.996 issues; usual flow (complementary "
            + "reading): x, 1.985 issues (fence 30,500 bd on the outer training set)")));
    // the line as the test stage prints it under the revised rule
    assertEquals(12.346, PredictedBands.usualFence(List.of(String.format(java.util.Locale.ROOT,
        PredictedBands.readingsLine(SplitRule.REVISED),
        TailCut.Reading.ALL.description(SplitRule.REVISED), 996,
        TailCut.Reading.USUAL_FLOW.description(SplitRule.REVISED), 985, 12.3456)
        .stripTrailing())));
    // and as it prints it under every other rule, the usual flow first
    assertEquals(12.346, PredictedBands.usualFence(List.of(String.format(java.util.Locale.ROOT,
        PredictedBands.readingsLine(SplitRule.TEMPORAL),
        TailCut.Reading.USUAL_FLOW.description(SplitRule.TEMPORAL), 985, 12.3456,
        TailCut.Reading.ALL.description(SplitRule.TEMPORAL), 996).stripTrailing())));
  }

  private static final String TEST_BANDS = "  error bands: bands of the observed business days; "
      + "here 2, 5,5 and 8 business days, of the outer training rows fitted";

  private static final String INNER_BANDS = "- error bands: bands of the observed business days; "
      + "here 1, 3 and 4 business days, of the 700 cut inner fitting issues; quartiles";

  @Test
  @DisplayName("the inner report's line of the same shape is ignored; a comma decimal is read")
  void readsOnlyTheTestStageLine() {
    assertArrayEquals(new double[] {2, 5.5, 8},
        PredictedBands.bandTops(List.of(INNER_BANDS, TEST_BANDS)));
    assertEquals(30.5, PredictedBands.usualFence(List.of(
        "  readings: usual flow (main reading): x, 1.985 issues (fence 30,500 bd on the outer "
            + "training set); all issues (c): y, 996 issues")));
  }

  @Test
  @DisplayName("two test-stage lines are refused, for the tops and for the fence")
  void twoTestLinesAreRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> PredictedBands.bandTops(List.of(TEST_BANDS, TEST_BANDS)));
    final String readings = "  readings: usual flow (main reading): x, 9 issues (fence 30.000 bd "
        + "on the outer training set); all issues (c): y, 10 issues";
    assertThrows(IllegalArgumentException.class,
        () -> PredictedBands.usualFence(List.of(readings, readings)));
    final String revised = "  readings: all issues (main reading): y, 10 issues; usual flow "
        + "(complementary reading): x, 9 issues (fence 30.000 bd on the outer training set)";
    assertThrows(IllegalArgumentException.class,
        () -> PredictedBands.usualFence(List.of(revised, revised)));
    assertThrows(IllegalArgumentException.class,
        () -> PredictedBands.usualFence(List.of(readings, revised)));
  }

  @Test
  @DisplayName("the tops and the fence are validated")
  void validates() {
    PredictedBands.validate(new double[] {2, 2, 8}, 30);
    for (final double[] bad : new double[][] {{2, 5}, {2, 5, 8, 9}, {5, 2, 8},
        {2, Double.NaN, 8}, {2, 5, Double.POSITIVE_INFINITY}}) {
      assertThrows(IllegalArgumentException.class, () -> PredictedBands.validate(bad, 30));
    }
    for (final double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class,
          () -> PredictedBands.validate(new double[] {2, 5, 8}, bad));
    }
  }
}
