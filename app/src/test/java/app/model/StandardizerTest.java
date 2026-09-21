package app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Centring and scaling, and the one thing that must not vary between partitions. */
class StandardizerTest {

  private static Observation row(final double... values) {
    return new Observation("i", "p", "dev", 1.0, values);
  }

  private static Observations of(final List<String> columns, final Observation... rows) {
    return new Observations(columns, List.of(rows), 0);
  }

  @Test
  @DisplayName("the selection side is scaled by the fit side's statistics, not by its own")
  void scalingComesFromTheFitSideOnly() {
    // If each partition were scaled by itself, a coefficient would describe a different variable
    // on each side and the selection score would belong to a model that was never fitted.
    final Standardizer standardizer = Standardizer.fittedOn(
        of(List.of("x"), row(0.0), row(2.0)));          // fit: mean 1, population deviation 1

    assertEquals(0.0, standardizer.apply(row(1.0))[0], 1e-9, "the fit side's mean lands on zero");
    assertEquals(9.0, standardizer.apply(row(10.0))[0], 1e-9,
        "a selection value far outside the fit range stays far outside after scaling");
  }

  @Test
  @DisplayName("a column that never varies in training is dropped, and named")
  void constantColumnsAreDroppedByName() {
    final Standardizer standardizer = Standardizer.fittedOn(
        of(List.of("varia", "constante"), row(1.0, 7.0), row(3.0, 7.0)));

    assertEquals(List.of("varia"), standardizer.columns());
    assertEquals(List.of("constante"), standardizer.dropped(),
        "naming it is what makes an absent predictor visible in the report instead of silent");
    assertEquals(1, standardizer.apply(row(3.0, 7.0)).length);
  }

  @Test
  @DisplayName("scaling uses the population deviation, matching the team statistics")
  void populationDeviation() {
    final Standardizer standardizer = Standardizer.fittedOn(
        of(List.of("x"), row(2.0), row(4.0), row(4.0), row(4.0),
            row(5.0), row(5.0), row(7.0), row(9.0)));   // mean 5, population deviation 2

    assertEquals(1.0, standardizer.apply(row(7.0))[0], 1e-9,
        "seven is one population deviation above a mean of five");
  }

  @Test
  @DisplayName("every column constant leaves nothing to fit, rather than dividing by zero")
  void allConstant() {
    final Standardizer standardizer = Standardizer.fittedOn(
        of(List.of("a", "b"), row(1.0, 2.0), row(1.0, 2.0)));

    assertTrue(standardizer.columns().isEmpty());
    assertEquals(List.of("a", "b"), standardizer.dropped());
  }
}
