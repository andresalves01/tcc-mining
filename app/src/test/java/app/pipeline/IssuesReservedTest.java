package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.IssueUnit;
import app.issues.Registration;
import app.issues.RowCache;
import app.issues.Scenario;
import app.issues.Universe.Population;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.paper5.Row;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reserved run's rules, on a corpus small enough to check by hand. */
class IssuesReservedTest {

  private static final String CELL = "ALZUBAIDI / C1";

  /** Six projects; per project 20 inner-fit, 10 inner-selection, 10 test and 2 crossing rows. */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(7);
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    final List<Attribute> columns = Attributes.of(Family.ALZUBAIDI, Scenario.C1);
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 6; p++) {
      for (int k = 0; k < 42; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 40 ? new String[] {"TEST", ""} : new String[] {"CROSSES", ""};
        final Map<String, Double> values = new HashMap<>();
        double signal = 0;
        for (final Attribute a : columns) {
          final double v = random.nextInt(5);
          values.put(a.name(), v);
          signal += v;
        }
        final double hours = 1 + 10 * p + signal + random.nextDouble() * 20;
        final String id = "i" + (n++);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.FIXED, part);
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e" + (k % 3),
            t0, t0.plusMillis((long) (hours * 3_600_000)), "Fixed", k % 7 == 0),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  private static final Progress SILENT = Progress.silent();

  @Test
  @DisplayName("the reserved mode fits on the whole outer training side and scores only the test")
  void reservedSides() {
    final Fitting.Sides s = Fitting.sides(corpus(), Fitting.Mode.RESERVED, Population.FIXED);
    assertEquals(6 * 30, s.fitUnits().size(), "inner fit and inner selection both train");
    assertEquals(6 * 10, s.scoreUnits().size(), "crossing rows are in neither side");
  }

  @Test
  @DisplayName("each cell is refitted with the chosen lambda times the ratio of rows, no "
      + "recalibration, and the subset reads the same predictions")
  void reservedRun() {
    final List<RowCache.Entry> entries = corpus();
    final IssuesEvaluationTask.Report r = new IssuesEvaluationTask(SILENT).run(entries,
        List.of(new IssuesEvaluationTask.Frozen(Population.FIXED, CELL, 0.5, 10.0)),
        Population.FIXED, Fitting.Mode.RESERVED, null);
    assertEquals(180, r.fitRows());
    assertEquals(120, r.innerFitRows());
    assertEquals(60, r.scoredRows());
    assertEquals(10.0 * 180 / 120, r.evaluated().get(0).fitted().lambda(), 1e-12);
    assertTrue(r.recalibrated().isEmpty(), "decision 22");
    assertTrue(r.unseenProjects().isEmpty());

    final Set<String> some = new HashSet<>();
    for (int i = 0; i < r.scoredUnits().size(); i += 4) {
      some.add(r.scoredUnits().get(i).issueId());
    }
    final IssuesEvaluationTask.Report withSubset = new IssuesEvaluationTask(SILENT).run(entries,
        List.of(new IssuesEvaluationTask.Frozen(Population.FIXED, CELL, 0.5, 10.0)),
        Population.FIXED, Fitting.Mode.RESERVED, some);
    assertEquals(some.size(), withSubset.neverScored().n());
    final double[] model = withSubset.evaluated().get(0).fitted().predictedHours();
    double sum = 0;
    for (int i = 0; i < model.length; i += 4) {
      sum += Math.abs(model[i] - withSubset.observed()[i]);
    }
    final var cellRow = withSubset.neverScored().rows().get(3);
    assertEquals(CELL, cellRow.score().label());
    assertEquals(sum / some.size(), cellRow.score().maeHours(), 1e-9,
        "the same predictions, read only on the subset");
  }

  @Test
  @DisplayName("the main reference is whichever errs less on the inner selection")
  void mainReference() {
    assertTrue(new IssuesEvaluationTask.MainReference(772.52, 796.45).constant());
    assertFalse(new IssuesEvaluationTask.MainReference(800, 796.45).constant());
  }

  @Test
  @DisplayName("the paired project interval: the point gap, and a tie when it holds zero")
  void projectGap() {
    final List<String> projects = new ArrayList<>();
    final double[] a = new double[60];
    final double[] b = new double[60];
    final double[] c = new double[60];
    for (int i = 0; i < 60; i++) {
      projects.add("p" + (i % 6));
      a[i] = 1 + i % 5;
      b[i] = a[i] + 2;
      c[i] = a[i] + (i % 2 == 0 ? 1 : -1);
    }
    final ProjectBootstrap.Gap better = ProjectBootstrap.compare(projects, "a", a, "b", b);
    assertEquals(-2.0, better.gapHours(), 1e-12);
    assertEquals(-2.0, better.low(), 1e-12, "every draw keeps the same gap");
    assertFalse(better.tied());
    final ProjectBootstrap.Gap even = ProjectBootstrap.compare(projects, "a", a, "c", c);
    assertEquals(0.0, even.gapHours(), 1e-12);
    assertTrue(even.tied());
  }

  @Test
  @DisplayName("the registration refuses a different matrix, parameters or size")
  void registrationGate() {
    assertTrue(Registration.differences(Registration.MATRIX_SHA256,
        Registration.PARAMETERS_SHA256, 33, Registration.SIZES).isEmpty());
    assertEquals(1, Registration.differences("0", Registration.PARAMETERS_SHA256, 33,
        Registration.SIZES).size());
    final Map<Population, Registration.Sizes> off = new EnumMap<>(Registration.SIZES);
    off.put(Population.BROAD, new Registration.Sizes(105_891, 47_831, 68_832, 31_768, 31_644));
    assertEquals(1, Registration.differences(Registration.MATRIX_SHA256,
        Registration.PARAMETERS_SHA256, 33, off).size());
    assertEquals(4, Registration.differences(Registration.MATRIX_SHA256,
        Registration.PARAMETERS_SHA256, 32, Map.of()).size());
  }
}
