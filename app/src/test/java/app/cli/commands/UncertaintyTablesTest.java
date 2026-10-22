package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.EstimationUncertainty;
import app.issues.PredictionIntervals;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UncertaintyTablesTest {

  @Test
  void machineOutputsKeepFractionUnitsCountsAndUndefinedValuesSeparateFromPresentation() {
    final var result = EstimationUncertainty.read(new EstimationUncertainty.Sample("all",
        List.of("a", "b"), new boolean[] {true, false}, new double[] {1, 3},
        Map.of("model", new double[] {0, 2}), new double[] {1, 2, 3}),
        Map.of("model", new PredictionIntervals.Calibration(20,
            new double[] {Math.log(2), Math.log(3)}, 0, 2)), 1);
    final var tables = UncertaintyTables.render(List.of(result));
    assertEquals(1 + 9 * 6, tables.uncertainty().size());
    assertEquals(1 + 5, tables.percentiles().size());
    assertEquals(9 * 6 + 5, tables.documents().size());
    final String mean = tables.uncertainty().stream().filter(l ->
        l.startsWith("all\tmodel\toverall\tall\t2\t1\t1\t2\t1\tmean_signed_relative_error\t"))
        .findFirst().orElseThrow();
    assertEquals("0.5", mean.split("\t", -1)[10]);
    assertTrue(tables.documents().stream().anyMatch(d ->
        d.get("kind").equals("estimation_uncertainty") && d.get("value") == null));
    assertTrue(tables.markdown().stream().anyMatch(l -> l.contains("50.00")));
    assertTrue(tables.markdown().stream().anyMatch(l ->
        l.contains("not validated acceptable-error thresholds")
            || l.contains("not ") && l.contains("acceptable-error thresholds")));
    assertTrue(tables.markdown().contains("## SPSP uncertainty context"));
    assertTrue(tables.markdown().stream().anyMatch(l ->
        l.contains("not ") && l.contains("percentile-specific coverage")));
  }
}
