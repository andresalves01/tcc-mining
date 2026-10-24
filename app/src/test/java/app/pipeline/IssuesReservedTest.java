package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The outer test's sides and the paired project interval, on a corpus small enough to check. */
class IssuesReservedTest {

  private static final String CELL = "ALZUBAIDI / C1";

  /**
   * Six projects; per project 20 inner-fit, 10 inner-validation, 10 test and 2 rows outside the
   * population.
   */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(7);
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    final List<Attribute> columns = Cell.byLabel(CELL).attributes();
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 6; p++) {
      for (int k = 0; k < 42; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 40 ? new String[] {"TEST", ""} : null;
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
        if (part != null) {
          parts.put(Population.IN_PROGRESS, part);
        }
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e" + (k % 3),
            t0, t0.plusMillis((long) (hours * 3_600_000)), "Fixed", k % 7 == 0,
            t0),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  @Test
  @DisplayName("the reserved mode fits on the whole outer training side and scores only the test")
  void reservedSides() {
    final Fitting.Sides s = Fitting.sides(corpus(), Fitting.Mode.RESERVED, Population.IN_PROGRESS);
    assertEquals(6 * 30, s.fitUnits().size(), "inner fit and inner validation both train");
    assertEquals(6 * 10, s.scoreUnits().size(), "rows outside the population are in neither");
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
    assertEquals(-2.0, better.gap(), 1e-12);
    assertEquals(-2.0, better.low(), 1e-12, "every draw keeps the same gap");
    assertFalse(better.tied());
    final ProjectBootstrap.Gap even = ProjectBootstrap.compare(projects, "a", a, "c", c);
    assertEquals(0.0, even.gap(), 1e-12);
    assertTrue(even.tied());
  }
}
