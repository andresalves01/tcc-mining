package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The prediction intervals of ticket 24, on residuals known by hand and on synthetic issues. */
class PredictionIntervalsTest {

  private static final double EXACT = 1e-12;

  /** The model of a constant, then one that tracks the truth with lognormal noise. */
  private static final String MODEL = "model";

  /**
   * {@code n} issues over {@code projects} projects of uneven size: predictions 2 plus a
   * lognormal, the truth the prediction moved on the log1p scale by a normal draw whose spread
   * grows with the prediction, at least one business day, a floor seldom reached; exchangeable,
   * issue by issue.
   */
  private static PredictionIntervals.Sample sample(final int n, final int projects,
      final long seed) {
    final Random random = new Random(seed);
    final List<String> project = new ArrayList<>(n);
    final double[] observed = new double[n];
    final double[] predicted = new double[n];
    final double[] global = new double[n];
    for (int i = 0; i < n; i++) {
      project.add("p" + Math.min(projects - 1, (int) (projects * Math.pow(random.nextDouble(),
          1.5))));
      predicted[i] = 2 + Math.exp(1 + 0.8 * random.nextGaussian());
      final double spread = 0.2 + 0.1 * Math.log1p(predicted[i]);
      observed[i] = Math.max(1, Math.expm1(Math.log1p(predicted[i])
          + spread * random.nextGaussian()));
      global[i] = 3;
    }
    final Map<String, double[]> models = new LinkedHashMap<>();
    models.put(SimilarityScoring.GLOBAL, global);
    models.put(MODEL, predicted);
    return new PredictionIntervals.Sample(project, observed, models);
  }

  @Test
  @DisplayName("the factor is the ceil((n + 1) L)-th smallest absolute residual, infinite past "
      + "the n-th; the interval is expm1(log1p(prediction) +- factor), its ends at least 1")
  void factorIntervalAndFloor() {
    final double[] nine = {0.9, 0.1, 0.5, 0.3, 0.7, 0.2, 0.8, 0.4, 0.6};
    // ceil(10 * 0.8) = 8: the 8th of nine; ceil(10 * 0.95) = 10: none
    assertEquals(0.8, PredictionIntervals.factor(nine, 0.80));
    assertEquals(Double.POSITIVE_INFINITY, PredictionIntervals.factor(nine, 0.95));
    final double[] nineteen = new double[19];
    for (int i = 0; i < 19; i++) {
      nineteen[i] = (19 - i) / 10.0;
    }
    // ceil(20 * 0.95) = 19 exactly, not 20 by rounding: the largest
    assertEquals(1.9, PredictionIntervals.factor(nineteen, 0.95));
    assertEquals(1.6, PredictionIntervals.factor(nineteen, 0.80));

    // log1p(9) = ln 10: divided and multiplied by 2
    final double[] around9 = PredictionIntervals.interval(9, Math.log(2));
    assertEquals(4, around9[0], EXACT);
    assertEquals(19, around9[1], EXACT);
    // log1p(1) = ln 2: the lower end, expm1(ln 0.5) = -0.5, raised to 1 business day
    final double[] around1 = PredictionIntervals.interval(1, Math.log(4));
    assertEquals(1, around1[0]);
    assertEquals(7, around1[1], EXACT);
    // an upper end below the floor is raised too: never an empty interval
    final double[] tiny = PredictionIntervals.interval(0.2, 0.1);
    assertEquals(1, tiny[0]);
    assertEquals(1, tiny[1]);
    assertEquals(Double.POSITIVE_INFINITY, PredictionIntervals.interval(3, Double
        .POSITIVE_INFINITY)[1]);
  }

  @Test
  @DisplayName("on residuals known by hand, each prediction quartile has its own factor, and a "
      + "new prediction takes the factor of the quartile it falls in")
  void calibrationByHand() {
    // predictions 1 to 16: quartiles, type 7, at 4.75, 8.5 and 12.25; four issues each
    final double[] predicted = new double[16];
    final double[] observed = new double[16];
    for (int i = 0; i < 16; i++) {
      predicted[i] = i + 1;
      // residual on the log1p scale: 0.1 times the quartile plus 0.01 times the place in it
      final double residual = 0.1 * (i / 4 + 1) + 0.01 * (i % 4);
      observed[i] = Math.expm1(Math.log1p(predicted[i]) + (i % 2 == 0 ? residual : -residual));
    }
    final PredictionIntervals.Calibration c = PredictionIntervals.calibrate(predicted, observed);
    assertEquals("[4.75, 8.5, 12.25]", Arrays.toString(c.limits()));
    assertEquals("[4, 4, 4, 4]", Arrays.toString(c.counts()));
    assertEquals(1, c.low());
    assertEquals(16, c.high());
    for (int q = 0; q < 4; q++) {
      // ceil(5 * 0.8) = 4: the largest of four; ceil(5 * 0.95) = 5: none
      assertEquals(0.1 * (q + 1) + 0.03, c.factors()[0][q], 1e-9, "quartile " + q);
      assertEquals(Double.POSITIVE_INFINITY, c.factors()[1][q]);
    }
    assertEquals(0, c.quartile(4.75));
    assertEquals(1, c.quartile(4.76));
    assertEquals(3, c.quartile(100));
    assertEquals(0, c.quartile(-0.5));
    final double[] in = c.interval(0, 10);
    assertEquals(Math.expm1(Math.log1p(10) - 0.33), in[0], 1e-9);
    assertEquals(Math.expm1(Math.log1p(10) + 0.33), in[1], 1e-9);
  }

  @Test
  @DisplayName("the prediction quartiles hold about a quarter of the calibration issues each, "
      + "per model")
  void quartersOfTheCalibration() {
    final PredictionIntervals.Sample s = sample(1001, 20, 5);
    final PredictionIntervals.Calibration c =
        PredictionIntervals.calibrate(s.predicted().get(MODEL), s.observed());
    for (final int count : c.counts()) {
      assertTrue(Math.abs(count - 1001 / 4.0) <= 1, Arrays.toString(c.counts()));
    }
    // one number for every issue: every issue in the first quartile
    final PredictionIntervals.Calibration g = PredictionIntervals.calibrate(
        s.predicted().get(SimilarityScoring.GLOBAL), s.observed());
    assertEquals("[1001, 0, 0, 0]", Arrays.toString(g.counts()));
  }

  @Test
  @DisplayName("on exchangeable synthetic issues, the coverage at L on new issues is close to L, "
      + "overall and per prediction quartile")
  void coverageCloseToTheLevel() {
    final PredictionIntervals.Sample calibration = sample(4000, 30, 7);
    final PredictionIntervals.Sample fresh = sample(40000, 30, 8);
    final Map<String, PredictionIntervals.Calibration> c = new LinkedHashMap<>();
    calibration.predicted().forEach((m, p) ->
        c.put(m, PredictionIntervals.calibrate(p, calibration.observed())));
    final List<PredictionIntervals.Read> reads = PredictionIntervals.outer(c, fresh, fresh, 1);
    assertEquals(2 * 2 * 2, reads.size());
    for (final PredictionIntervals.Read r : reads) {
      if (r.model().equals(SimilarityScoring.GLOBAL)) {
        // a constant of 3 against truths held at 1: the floor covers more, never less
        assertTrue(r.overall().coverage().value() > r.level() - 0.015,
            r.level() + " " + r.overall().coverage());
      } else {
        assertTrue(Math.abs(r.overall().coverage().value() - r.level()) < 0.015,
            r.level() + " " + r.overall().coverage());
        for (final PredictionIntervals.Group q : r.quartiles()) {
          assertTrue(Math.abs(q.coverage().value() - r.level()) < 0.025,
              r.level() + " " + q.coverage());
        }
      }
      assertTrue(r.overall().coverage().low() <= r.overall().coverage().value()
          && r.overall().coverage().value() <= r.overall().coverage().high());
    }
  }

  @Test
  @DisplayName("the calibration halves are whole projects, the same on every run and in any "
      + "order of the issues, and A holds at least half of them, by its last project only")
  void halves() {
    final PredictionIntervals.Sample s = sample(3000, 40, 9);
    final PredictionIntervals.Halves h = PredictionIntervals.halves(s.projects());
    assertEquals(h, PredictionIntervals.halves(s.projects()));
    final List<String> shuffled = new ArrayList<>(s.projects());
    Collections.shuffle(shuffled, new Random(3));
    assertEquals(h, PredictionIntervals.halves(shuffled));
    final long inA = s.projects().stream().filter(h.a()::contains).count();
    assertEquals(inA, h.aIssues());
    assertEquals(3000 - inA, h.bIssues());
    assertTrue(2 * inA >= 3000, h.toString());
    // the order the rule gives the projects in: A is a prefix, and its last one was needed
    final List<String> order = new ArrayList<>(new TreeSet<>(s.projects()));
    Collections.shuffle(order, new Random(PredictionIntervals.HALVES_SEED));
    final List<String> prefix = order.subList(0, h.a().size());
    assertEquals(h.a(), Set.copyOf(prefix));
    final String last = prefix.get(prefix.size() - 1);
    assertTrue(2 * (inA - s.projects().stream().filter(last::equals).count()) < 3000);
    assertTrue(h.a().size() < 40);
  }

  @Test
  @DisplayName("the factors the check reads come from half A only: changing a half-B label, of "
      + "the usual flow or of the tail, never moves them; the factors for later use are those "
      + "of A and B together")
  void factorsOfTheCalibrationIssuesOnly() {
    final PredictionIntervals.Sample usual = sample(2000, 25, 11);
    final PredictionIntervals.Inner inner = PredictionIntervals.inner(usual, usual, 1);
    final Set<String> a = inner.halves().a();
    final double[] moved = usual.observed().clone();
    for (int i = 0; i < moved.length; i++) {
      if (!a.contains(usual.projects().get(i))) {
        moved[i] += 400;
      }
    }
    final PredictionIntervals.Sample b = new PredictionIntervals.Sample(usual.projects(), moved,
        usual.predicted());
    final PredictionIntervals.Inner again = PredictionIntervals.inner(usual, b, 1);
    final PredictionIntervals.Inner bothMoved = PredictionIntervals.inner(b, b, 1);
    for (final String m : usual.predicted().keySet()) {
      for (final PredictionIntervals.Inner other : List.of(again, bothMoved)) {
        assertEquals(Arrays.toString(inner.checked().get(m).limits()),
            Arrays.toString(other.checked().get(m).limits()), m);
        assertEquals(Arrays.deepToString(inner.checked().get(m).factors()),
            Arrays.deepToString(other.checked().get(m).factors()), m);
      }
      // A and B together: B's labels count there
      assertNotEquals(Arrays.deepToString(inner.calibrated().get(m).factors()),
          Arrays.deepToString(bothMoved.calibrated().get(m).factors()), m);
      final double[] p = usual.predicted().get(m);
      assertEquals(Arrays.deepToString(PredictionIntervals.calibrate(p, usual.observed())
          .factors()), Arrays.deepToString(inner.calibrated().get(m).factors()), m);
    }
    // the check reads half B only: its issues, every model at both levels, on both readings
    assertEquals(2 * 2 * 2, inner.reads().size());
    for (final PredictionIntervals.Read r : inner.reads()) {
      assertEquals(inner.halves().bIssues(), r.overall().n());
      assertEquals(r.overall().n(), r.quartiles().stream().mapToInt(q -> q.n()).sum());
    }
  }

  @Test
  @DisplayName("each group's figures: coverage, median width, width over the global median's on "
      + "the same issues, and the MAE; an empty quartile has none")
  void groupFigures() {
    final PredictionIntervals.Sample s = sample(800, 12, 13);
    final PredictionIntervals.Inner inner = PredictionIntervals.inner(s, s, 1);
    for (final PredictionIntervals.Read r : inner.reads()) {
      final int l = PredictionIntervals.LEVELS.indexOf(r.level());
      final PredictionIntervals.Calibration c = inner.checked().get(r.model());
      final PredictionIntervals.Calibration g = inner.checked().get(SimilarityScoring.GLOBAL);
      final double[] p = s.predicted().get(r.model());
      final List<Double> widths = new ArrayList<>();
      final List<Double> globalWidths = new ArrayList<>();
      double covered = 0;
      double error = 0;
      for (int i = 0; i < p.length; i++) {
        if (inner.halves().a().contains(s.projects().get(i))) {
          continue;
        }
        final double[] in = c.interval(l, p[i]);
        covered += in[0] <= s.observed()[i] && s.observed()[i] <= in[1] ? 1 : 0;
        widths.add(in[1] - in[0]);
        final double[] gi = g.interval(l, 3);
        globalWidths.add(gi[1] - gi[0]);
        error += Math.abs(p[i] - s.observed()[i]);
      }
      final PredictionIntervals.Group all = r.overall();
      assertEquals(covered / widths.size(), all.coverage().value(), EXACT);
      final double median = PredictionIntervals.median(
          widths.stream().mapToDouble(Double::doubleValue).toArray());
      assertEquals(median, all.medianWidth(), EXACT);
      assertEquals(median / globalWidths.get(0), all.relativeWidth(), EXACT);
      assertEquals(error / widths.size(), all.mae(), EXACT);
      if (r.model().equals(SimilarityScoring.GLOBAL)) {
        assertEquals(1, all.relativeWidth(), EXACT);
        for (final PredictionIntervals.Group q : r.quartiles().subList(1, 4)) {
          assertEquals(0, q.n());
          assertNull(q.coverage());
          assertTrue(Double.isNaN(q.medianWidth()) && Double.isNaN(q.mae()));
        }
      }
    }
    assertEquals(Double.POSITIVE_INFINITY,
        PredictionIntervals.median(new double[] {1, Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY, 2}));
    assertEquals(2.5, PredictionIntervals.median(new double[] {4, 1, 3, 2}));
  }
}
