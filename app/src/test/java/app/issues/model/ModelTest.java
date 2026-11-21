package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.study.Row;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The modelling pieces, checked on numbers small enough to verify by hand. */
class ModelTest {

  @Test
  @DisplayName("the development mode never fits on nor scores an outer test row")
  void innerNeverSeesTheTestRows() {
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    final List<RowCache.Entry> entries = new ArrayList<>();
    final String[][] parts = {{"TRAIN", "TRAIN"}, {"TRAIN", "TEST"}, {"TEST", ""}};
    for (int i = 0; i < parts.length; i++) {
      final Map<Population, String[]> p = new EnumMap<>(Population.class);
      p.put(Population.FIRST_COMMIT, parts[i]);
      entries.add(new RowCache.Entry(new IssueUnit("i" + i, "K-" + i, "p", "r", "e", t0,
          t0.plusSeconds(7200), "Fixed", false, t0.plusSeconds(3600)),
          new Row("i" + i, Map.of(), Set.of()), p));
    }
    final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, Population.FIRST_COMMIT);
    assertEquals(List.of("i0"), inner.fitUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(List.of("i1"), inner.scoreUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(1.0, inner.observed()[0], 1e-9, "one hour from the In Progress");
  }

  private static int size(final String label) {
    return Cell.byLabel(label).attributes().size();
  }

  @Test
  @DisplayName("the literature sets: each holds its family's columns, the retrospective one adds "
      + "the twelve observed up to the resolution")
  void theLiteratureSets() {
    assertEquals(List.of("ALZUBAIDI / C1", "KIKAS / C1", "KIKAS / C2"),
        Cell.literature().stream().map(Cell::label).toList());
    assertEquals(12, size("ALZUBAIDI / C1"), "12 of Al-Zubaidi's 13");
    assertEquals(14, size("KIKAS / C1"), "13 of Kikas's plus K13x");
    assertEquals(26, size("KIKAS / C2"), "plus the twelve exposure columns");
    for (final Cell cell : Cell.literature()) {
      for (final Attribute a : cell.attributes()) {
        assertEquals(cell.family(), a.family(), cell.label() + " " + a.name());
        assertTrue(a.atCreation() || cell.scenario().admits(a.block()), cell.label() + a.name());
        assertFalse(a.name().equals(Attributes.SENSITIVITY_A15W), "A15w is only a sensitivity");
      }
    }
  }

  @Test
  @DisplayName("the log recalibration stretches a shrunk model back and keeps a constant one as is")
  void recalibrationUndoesShrinking() {
    final int n = 200;
    final double[][] x = new double[n][1];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = (i - n / 2.0) / (n / 4.0);
      y[i] = 3 + x[i][0];
    }
    final app.model.ElasticNetModel shrunk =
        app.model.ElasticNetModel.fit(x, y, List.of("x"), 0.95, 150.0);
    final double[][] xs = {{1.0}, {-1.0}};
    final Fitting.Recalibration r = Fitting.recalibrate(shrunk, x, y, xs);
    assertTrue(shrunk.predict(xs[0]) < 4.0, "the penalty shrank the slope");
    assertTrue(r.b() > 1.0, "b > 1 stretches it back: " + r.b());
    assertEquals(Math.expm1(4.0), r.predicted()[0], 1e-6);
    assertEquals(Math.expm1(2.0), r.predicted()[1], 1e-6);

    final app.model.ElasticNetModel flat =
        app.model.ElasticNetModel.fit(x, y, List.of("x"), 0.95, 1e9);
    final Fitting.Recalibration none = Fitting.recalibrate(flat, x, y, xs);
    assertEquals(1.0, none.b());
    assertEquals(0.0, none.a());
  }
}
