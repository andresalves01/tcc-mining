package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.model.ElasticNetModel;
import app.model.RandomForestModel;
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

/** The random forest beside the Elastic Net: the same rows, a declared grid, the same forest. */
class ForestTest {

  private static final String CELL = "ALZUBAIDI / C1";

  /**
   * Six projects; per project 20 inner-fit, 10 inner-selection, 10 test and 2 crossing rows. The
   * duration rises with the first column's distance from its middle: a shape no line can follow.
   */
  private static List<RowCache.Entry> corpus() {
    final Random random = new Random(5);
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    final List<Attribute> columns = Cell.byLabel(CELL).attributes();
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 6; p++) {
      for (int k = 0; k < 42; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 40 ? new String[] {"TEST", ""} : new String[] {"CROSSES", ""};
        final Map<String, Double> values = new HashMap<>();
        for (final Attribute a : columns) {
          values.put(a.name(), (double) random.nextInt(9));
        }
        final double v = values.get(columns.get(0).name());
        final double hours = 1 + 40 * Math.abs(v - 4) + random.nextDouble() * 5;
        final String id = "i" + (n++);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.FIXED, part);
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e",
            t0, t0.plusMillis((long) (hours * 3_600_000)), "Fixed", false),
            new Row(id, values, Set.of()), parts));
      }
    }
    return out;
  }

  @Test
  @DisplayName("the forest sweeps its declared grid on the inner sides and keeps the point that "
      + "errs least there")
  void sweep() {
    final Fitting.Sides sides = Fitting.sides(corpus(), Fitting.Mode.INNER, Population.FIXED);
    final Forest.Fitted f = Forest.sweep(Cell.byLabel(CELL), sides, 1, 1);
    assertEquals(6 * 10, f.predictedHours().length, "only the inner selection is scored");
    assertEquals(Forest.MTRY_SHARES.length * Forest.NODE_SIZES.length, f.trials().size());
    final double least = f.trials().stream().mapToDouble(Forest.Trial::maeHours).min()
        .orElseThrow();
    assertEquals(least, f.score().maeHours(), 1e-9);
    assertTrue(f.trials().stream().anyMatch(t -> t.mtry() == f.mtry()
        && t.nodeSize() == f.nodeSize() && t.maeHours() == least));
  }

  @Test
  @DisplayName("of grid points whose errors tie exactly, the first in the grid's order is kept: "
      + "the smaller share, then the smaller leaf")
  void tiesGoToTheFirstPoint() {
    final double third = 1.0 / 3;
    final List<Forest.Trial> tied = List.of(new Forest.Trial(1.0 / 6, 1, 5, 7.0),
        new Forest.Trial(1.0 / 6, 1, 25, 7.0), new Forest.Trial(third, 2, 5, 7.0));
    assertEquals(0, Forest.chosen(tied));
    final List<Forest.Trial> later = List.of(new Forest.Trial(1.0 / 6, 1, 5, 9.0),
        new Forest.Trial(1.0 / 6, 1, 25, 7.0), new Forest.Trial(third, 2, 5, 7.0),
        new Forest.Trial(third, 2, 25, 8.0));
    assertEquals(1, Forest.chosen(later));

    // the sweep keeps the point chosen() picks from its own trials, and records its share
    final Fitting.Sides sides = Fitting.sides(corpus(), Fitting.Mode.INNER, Population.FIXED);
    final Forest.Fitted f = Forest.sweep(Cell.byLabel(CELL), sides, 1, 1);
    final Forest.Trial kept = f.trials().get(Forest.chosen(f.trials()));
    assertEquals(List.of(kept.share(), (double) kept.mtry(), (double) kept.nodeSize()),
        List.of(f.share(), (double) f.mtry(), (double) f.nodeSize()));
  }

  @Test
  @DisplayName("permutation importance: the column the duration follows raises the error most "
      + "when shuffled, and the same model and rows give the same importance")
  void permutationImportance() {
    final Random random = new Random(3);
    final int n = 300;
    final double[][] x = new double[n][2];
    final double[] y = new double[n];
    final double[] hours = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = random.nextDouble() * 2 - 1;
      x[i][1] = random.nextDouble() * 2 - 1;
      hours[i] = 50 * x[i][0] * x[i][0] + random.nextDouble();
      y[i] = Math.log1p(hours[i]);
    }
    final List<String> names = List.of("a", "b");
    final RandomForestModel model = RandomForestModel.fit(x, y, names, 50, 1, 20, 5, 7L);
    final double[] fitted = Forest.hours(model.predict(x));
    final List<Forest.Importance> one =
        Forest.permutationImportance(model, names, x, fitted, hours);
    // the rows handed in are never shuffled in place
    assertArrayEquals(fitted, Forest.hours(model.predict(x)), 0.0);
    assertEquals(names, one.stream().map(Forest.Importance::column).toList());
    assertEquals(Forest.PERMUTATION_REPEATS, one.get(0).increases().length);
    assertTrue(one.get(0).meanIncreaseHours() > 5 * Math.abs(one.get(1).meanIncreaseHours()),
        one.toString());
    double sum = 0;
    for (final double v : one.get(0).increases()) {
      sum += v;
    }
    assertEquals(sum / Forest.PERMUTATION_REPEATS, one.get(0).meanIncreaseHours(), 1e-12);
    final List<Forest.Importance> two =
        Forest.permutationImportance(model, names, x, fitted, hours);
    for (int c = 0; c < names.size(); c++) {
      assertArrayEquals(one.get(c).increases(), two.get(c).increases(), 0.0);
    }
  }

  @Test
  @DisplayName("the same rows give the same forest, and it follows a shape the line cannot")
  void deterministicAndNonLinear() {
    final Random random = new Random(3);
    final int n = 400;
    final double[][] x = new double[n][2];
    final double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i][0] = random.nextDouble() * 2 - 1;
      x[i][1] = random.nextDouble() * 2 - 1;
      y[i] = x[i][0] * x[i][0] + 0.05 * random.nextGaussian();
    }
    final List<String> names = List.of("a", "b");
    final RandomForestModel one = RandomForestModel.fit(x, y, names, 50, 1, 20, 5, 7L);
    final RandomForestModel two = RandomForestModel.fit(x, y, names, 50, 1, 20, 5, 7L);
    final double[] p1 = one.predict(x);
    assertArrayEquals(p1, two.predict(x), 0.0);

    final ElasticNetModel line = ElasticNetModel.fit(x, y, names, 0.5, 1e-6);
    double forestError = 0;
    double lineError = 0;
    for (int i = 0; i < n; i++) {
      forestError += Math.abs(p1[i] - y[i]);
      lineError += Math.abs(line.predict(x[i]) - y[i]);
    }
    assertTrue(forestError < lineError / 2, forestError + " vs " + lineError);
    assertTrue(one.importance()[0] > one.importance()[1], "the shape lives in the first column");
  }
}
