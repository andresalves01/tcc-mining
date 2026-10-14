package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.study.Row;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Scenario;
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

  private static RowCache.Entry entry(final String id, final String outer, final String inner,
      final double hours) {
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.FIXED, new String[] {outer, inner});
    return new RowCache.Entry(new IssueUnit(id, "K-" + id, "p", "r", null, t0,
        t0.plusMillis((long) (hours * 3_600_000)), "Fixed", false),
        new Row(id, Map.of(), Set.of()), parts);
  }

  @Test
  @DisplayName("the development mode never fits on nor scores an outer test row")
  void innerNeverSeesTheTestRows() {
    final List<RowCache.Entry> entries = List.of(entry("a", "TRAIN", "TRAIN", 1),
        entry("b", "TRAIN", "TEST", 2), entry("c", "TEST", "", 3),
        entry("d", "TRAIN", "CROSSES", 4));
    final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER, Population.FIXED);

    assertEquals(List.of("a"), inner.fitUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(List.of("b"), inner.scoreUnits().stream().map(IssueUnit::issueId).toList());
  }

  @Test
  @DisplayName("a row outside the population is in neither side")
  void otherPopulationsAreIgnored() {
    final List<RowCache.Entry> entries = List.of(entry("a", "TRAIN", "TRAIN", 1),
        entry("b", "TRAIN", "TEST", 2));
    final Fitting.Sides fixed = Fitting.sides(entries, Fitting.Mode.INNER, Population.FIXED);
    assertEquals(1, fixed.fitUnits().size());
    try {
      Fitting.sides(entries, Fitting.Mode.INNER, Population.BROAD);
      throw new AssertionError("a population with no rows must be refused");
    } catch (final IllegalStateException expected) {
      // nothing to fit
    }
  }

  @Test
  @DisplayName("the range is read, not filtered: its limits are placed as percentiles of the truth")
  void theWindowReadsTheRange() {
    final double[] observed = {0.5, 2, 10, 100, 9000};
    final double[] predicted = {1, 1, 10, 50, 9000};
    final Window w = Window.of(predicted, observed, Window.ONE_HOUR, Window.ONE_YEAR);

    assertEquals(3, w.n());
    assertEquals(20.0, w.lowPercentile(), 1e-9, "one of five is shorter than an hour");
    assertEquals(80.0, w.highPercentile(), 1e-9, "four of five are within a year");
    assertEquals((1 + 0 + 50) / 3.0, w.mae(), 1e-9);
  }

  @Test
  @DisplayName("the project bootstrap reports the point gain and brackets it")
  void projectBootstrap() {
    final List<String> projects = new ArrayList<>();
    final double[] observed = new double[60];
    final double[] model = new double[60];
    final double[] reference = new double[60];
    for (int i = 0; i < 60; i++) {
      projects.add("p" + (i % 6));
      observed[i] = 10 + i;
      model[i] = observed[i] + 1;
      reference[i] = observed[i] + 4;
    }
    final ProjectBootstrap.Interval gain =
        ProjectBootstrap.gain(projects, model, reference, observed);

    assertEquals(75.0, gain.gain(), 1e-9, "one hour of error against four");
    assertTrue(gain.low() <= 75.0 && gain.high() >= 75.0);
    assertTrue(gain.modelBetter());
  }

  private static int size(final Family f, final Scenario s) {
    return Cell.of(f, s).attributes().size();
  }

  @Test
  @DisplayName("the grid: eleven cells, and each block holds exactly its own columns")
  void theGrid() {
    assertEquals(11, Cell.grid().size());
    assertEquals(12, size(Family.ALZUBAIDI, Scenario.C1), "12 of Al-Zubaidi's 13");
    assertEquals(14, size(Family.KIKAS, Scenario.C1), "13 of Kikas's plus K13x");
    assertEquals(26, size(Family.KIKAS, Scenario.C2), "plus the twelve exposure columns");
    assertEquals(10, size(Family.PROPRIA, Scenario.C1));
    assertEquals(22, size(Family.PROPRIA, Scenario.C1_CONTEUDO));
    assertEquals(12, size(Family.PROPRIA, Scenario.C1_EXPOSICAO));
    assertEquals(24, size(Family.PROPRIA, Scenario.C2));
    assertEquals(35, size(Family.UNIAO, Scenario.C1), "K02 enters the union once, as Z10 + Z12");
    for (final Cell cell : Cell.grid()) {
      for (final Attribute a : cell.attributes()) {
        assertTrue(a.atCreation() || cell.scenario().admits(a.block()), cell.label() + a.name());
        assertFalse(a.name().equals(Attributes.SENSITIVITY_A15W), "A15w is only a sensitivity");
      }
    }
    assertTrue(Cell.of(Family.UNIAO, Scenario.C1_CONTEUDO).attributes().stream()
        .noneMatch(a -> a.block() == Scenario.Block.EXPOSICAO));
    assertTrue(Cell.of(Family.UNIAO, Scenario.C1_EXPOSICAO).attributes().stream()
        .noneMatch(a -> a.block() == Scenario.Block.CONTEUDO));
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
    assertEquals(Math.expm1(4.0), r.predictedHours()[0], 1e-6);
    assertEquals(Math.expm1(2.0), r.predictedHours()[1], 1e-6);

    final app.model.ElasticNetModel flat =
        app.model.ElasticNetModel.fit(x, y, List.of("x"), 0.95, 1e9);
    final Fitting.Recalibration none = Fitting.recalibrate(flat, x, y, xs);
    assertEquals(1.0, none.b());
    assertEquals(0.0, none.a());
  }
}
