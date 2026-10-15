package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.issues.Attribute;
import app.issues.Family;
import app.issues.IssueUnit;
import app.issues.Scenario;
import app.model.ElasticNetModel;
import app.study.Construct;
import app.study.Row;
import app.study.model.DesignMatrix;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Elastic Net sweep fitted on several threads against the same sweep on one: the chosen
 * mixture and penalty, every trial's error, every coefficient and every prediction must be the
 * same bits, not merely close. One thread is also checked against the sequential sweep it
 * replaced, copied here.
 */
public class ParallelSweepTest {

  private static final Instant T0 = Instant.parse("2015-01-01T00:00:00Z");

  /**
   * {@code fit} fitting rows and {@code scored} scored ones over {@code columns} numeric columns:
   * the log duration is a sparse linear function of the first few columns plus noise, the rest
   * pure noise, so the sweep has a real choice to make along the penalty.
   */
  static Fixture fixture(final long seed, final int fit, final int scored, final int columns) {
    final Random random = new Random(seed);
    final List<Attribute> attributes = new ArrayList<>();
    for (int c = 0; c < columns; c++) {
      attributes.add(new Attribute("x" + c, "x" + c, Family.OWN, null, Construct.Kind.NUMERIC,
          c % 3 == 0 ? Construct.Transform.LOG1P : Construct.Transform.NONE, false, true));
    }
    final List<IssueUnit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<IssueUnit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (int i = 0; i < fit + scored; i++) {
      final Map<String, Double> values = new HashMap<>();
      double log = 2;
      for (int c = 0; c < columns; c++) {
        final double v = c % 3 == 0 ? 50 * random.nextDouble() : random.nextGaussian();
        values.put("x" + c, v);
        if (c < 5) {
          log += (c % 2 == 0 ? 0.4 : -0.3) * (c % 3 == 0 ? Math.log1p(v) / 2 : v);
        }
      }
      log += 0.8 * random.nextGaussian();
      final double hours = Math.max(0.05, Math.expm1(Math.max(0.05, log)));
      final String id = "i" + i;
      final IssueUnit unit = new IssueUnit(id, "K-" + i, "p" + i % 7, "r", null, T0,
          T0.plusMillis((long) (hours * 3_600_000)), "Fixed", false);
      final Row row = new Row(id, values, Set.of());
      if (i < fit) {
        fitUnits.add(unit);
        fitRows.add(row);
      } else {
        scoreUnits.add(unit);
        scoreRows.add(row);
      }
    }
    final double[] observed = scoreUnits.stream().mapToDouble(IssueUnit::durationHours).toArray();
    return new Fixture(new Cell("synthetic", Family.OWN, Scenario.C1, attributes),
        new Fitting.Sides(fitUnits, fitRows, scoreUnits, scoreRows, observed));
  }

  record Fixture(Cell cell, Fitting.Sides sides) {}

  /** Every number two sweeps report, compared bit for bit. */
  public static void assertIdentical(final Fitting.Fitted a, final Fitting.Fitted b) {
    assertEquals(bits(a.alpha()), bits(b.alpha()), "alpha");
    assertEquals(bits(a.lambda()), bits(b.lambda()), "lambda");
    assertEquals(a.step(), b.step(), "step");
    assertEquals(a.columns(), b.columns());
    assertEquals(a.dropped(), b.dropped());
    assertEquals(a.selected(), b.selected());
    assertEquals(a.trials().size(), b.trials().size());
    for (int t = 0; t < a.trials().size(); t++) {
      final Fitting.Trial x = a.trials().get(t);
      final Fitting.Trial y = b.trials().get(t);
      assertEquals(bits(x.alpha()), bits(y.alpha()), "trial " + t);
      assertEquals(x.step(), y.step(), "trial " + t);
      assertEquals(bits(x.lambda()), bits(y.lambda()), "trial " + t);
      assertEquals(bits(x.maeHours()), bits(y.maeHours()), "trial " + t + " MAE");
    }
    assertEquals(a.contributions().size(), b.contributions().size());
    for (int c = 0; c < a.contributions().size(); c++) {
      final Fitting.Contribution x = a.contributions().get(c);
      final Fitting.Contribution y = b.contributions().get(c);
      assertEquals(x.column(), y.column());
      assertEquals(bits(x.coefficient()), bits(y.coefficient()), "coefficient " + x.column());
      assertEquals(bits(x.ownSpearman()), bits(y.ownSpearman()), x.column());
      assertEquals(x.selected(), y.selected(), x.column());
    }
    assertArrayEquals(bits(a.predictedHours()), bits(b.predictedHours()), "predictions");
    assertEquals(bits(a.score().maeHours()), bits(b.score().maeHours()), "MAE");
    assertEquals(bits(a.recalibration().a()), bits(b.recalibration().a()));
    assertEquals(bits(a.recalibration().b()), bits(b.recalibration().b()));
    assertArrayEquals(bits(a.recalibration().predictedHours()),
        bits(b.recalibration().predictedHours()), "recalibrated predictions");
  }

  /**
   * The sequential sweep as it stood before its points were fitted in parallel, copied here as the
   * oracle: its trials, its choice and the chosen model's coefficients must be what the sweep
   * reports, bit for bit.
   */
  static void assertOldSweep(final Fixture f, final Fitting.Fitted now) {
    final Cell cell = f.cell();
    final Fitting.Sides sides = f.sides();
    final DesignMatrix matrix = DesignMatrix.fittedOn(sides.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(sides.fitRows());
    final double[][] xs = matrix.applyAll(sides.scoreRows());
    final double[] y = Fitting.target(sides.fitUnits());
    ElasticNetModel best = null;
    double bestMae = Double.POSITIVE_INFINITY;
    int bestStep = -1;
    final List<Fitting.Trial> trials = new ArrayList<>();
    for (final double alpha : Fitting.ALPHAS) {
      final double[] grid = Fitting.lambdas(x, y, alpha);
      for (int step = 0; step < grid.length; step++) {
        final ElasticNetModel model =
            ElasticNetModel.fit(x, y, matrix.columns(), alpha, grid[step]);
        double sum = 0;
        for (int i = 0; i < xs.length; i++) {
          sum += Math.abs(DesignMatrix.hours(model.predict(xs[i])) - sides.observed()[i]);
        }
        trials.add(new Fitting.Trial(alpha, step, grid[step], sum / xs.length));
        if (sum / xs.length < bestMae) {
          bestMae = sum / xs.length;
          best = model;
          bestStep = step;
        }
      }
    }
    assertEquals(trials, now.trials(), "every trial, its error compared as a double record field");
    for (int t = 0; t < trials.size(); t++) {
      assertEquals(bits(trials.get(t).maeHours()), bits(now.trials().get(t).maeHours()));
    }
    assertEquals(bestStep, now.step());
    assertEquals(bits(best.alpha()), bits(now.alpha()));
    assertEquals(bits(best.lambda()), bits(now.lambda()));
    final double[] coefficients = best.coefficients();
    for (int c = 0; c < coefficients.length; c++) {
      assertEquals(bits(coefficients[c]), bits(now.contributions().get(c).coefficient()),
          now.contributions().get(c).column());
    }
  }

  static long bits(final double x) {
    return Double.doubleToRawLongBits(x);
  }

  static long[] bits(final double[] x) {
    final long[] out = new long[x.length];
    for (int i = 0; i < x.length; i++) {
      out[i] = bits(x[i]);
    }
    return out;
  }

  @Test
  @DisplayName("on a small matrix the sweep on four threads, and on more threads than points, "
      + "chooses and reports exactly what the sweep on one thread does")
  void small() {
    final Fixture f = fixture(11, 400, 150, 12);
    final Fitting.Fitted one = Fitting.sweep(f.cell(), f.sides(), 100, 120, 1);
    assertEquals(Fitting.ALPHAS.length * Fitting.LAMBDA_STEPS, one.trials().size());
    assertNotEquals(0, one.step(), "the fixture must make the sweep choose a live model");
    assertOldSweep(f, one);
    assertIdentical(one, Fitting.sweep(f.cell(), f.sides(), 100, 120, 4));
    assertIdentical(one, Fitting.sweep(f.cell(), f.sides(), 100, 120, 64));
  }

  @Test
  @DisplayName("on a matrix of the real shape, 8,000 fitting rows by 40 columns, where the native "
      + "matrix library may run its own threads, the parallel sweep is the sequential one")
  void realShape() {
    final Fixture f = fixture(29, 8_000, 2_000, 40);
    final Fitting.Fitted one = Fitting.sweep(f.cell(), f.sides(), 100, 120, 1);
    assertOldSweep(f, one);
    assertIdentical(one, Fitting.sweep(f.cell(), f.sides(), 100, 120,
        Runtime.getRuntime().availableProcessors()));
  }
}
