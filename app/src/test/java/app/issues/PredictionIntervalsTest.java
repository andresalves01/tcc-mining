package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

  /** The truth of one synthetic issue around {@code predicted}, at least one business day. */
  private static double truth(final Random random, final double predicted) {
    final double spread = 0.2 + 0.1 * Math.log1p(predicted);
    return Math.max(1, Math.expm1(Math.log1p(predicted) + spread * random.nextGaussian()));
  }

  /**
   * {@code n} issues over {@code projects} projects of uneven size: predictions 2 plus a
   * lognormal, the truth the prediction moved on the log1p scale by a normal draw whose spread
   * grows with the prediction, at least one business day, a floor seldom reached; exchangeable,
   * issue by issue. The global median predicts 3 everywhere. The bands are the quartiles of the
   * truths of a fitting side of its own, drawn alike.
   */
  private static PredictionIntervals.Sample sample(final int n, final int projects,
      final long seed) {
    final Random random = new Random(seed);
    final List<String> project = new ArrayList<>(n);
    final double[] observed = new double[n];
    final double[] predicted = new double[n];
    final double[] global = new double[n];
    final double[] fitting = new double[n];
    for (int i = 0; i < n; i++) {
      project.add("p" + Math.min(projects - 1, (int) (projects * Math.pow(random.nextDouble(),
          1.5))));
      predicted[i] = 2 + Math.exp(1 + 0.8 * random.nextGaussian());
      observed[i] = truth(random, predicted[i]);
      global[i] = 3;
      fitting[i] = truth(random, 2 + Math.exp(1 + 0.8 * random.nextGaussian()));
    }
    final Map<String, double[]> models = new LinkedHashMap<>();
    models.put(SimilarityScoring.GLOBAL, global);
    models.put(MODEL, predicted);
    return new PredictionIntervals.Sample(project, observed, models,
        TailCut.quartiles(fitting), PredictionIntervals.FLOOR);
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
    final double[] around9 = PredictionIntervals.interval(9, Math.log(2), PredictionIntervals.FLOOR);
    assertEquals(4, around9[0], EXACT);
    assertEquals(19, around9[1], EXACT);
    // log1p(1) = ln 2: the lower end, expm1(ln 0.5) = -0.5, raised to 1 business day
    final double[] around1 = PredictionIntervals.interval(1, Math.log(4), PredictionIntervals.FLOOR);
    assertEquals(1, around1[0]);
    assertEquals(7, around1[1], EXACT);
    // an upper end below the floor is raised too: never an empty interval
    final double[] tiny = PredictionIntervals.interval(0.2, 0.1, PredictionIntervals.FLOOR);
    assertEquals(1, tiny[0]);
    assertEquals(1, tiny[1]);
    assertEquals(Double.POSITIVE_INFINITY, PredictionIntervals.interval(3, Double
        .POSITIVE_INFINITY, PredictionIntervals.FLOOR)[1]);
  }

  @Test
  @DisplayName("on residuals known by hand, one factor per model and level, of all its "
      + "calibration issues, whatever their predictions")
  void calibrationByHand() {
    final double[] predicted = new double[10];
    final double[] observed = new double[10];
    for (int i = 0; i < 10; i++) {
      predicted[i] = 1 + 3 * i;
      final double residual = (10 - i) / 10.0;
      observed[i] = Math.expm1(Math.log1p(predicted[i]) + (i % 2 == 0 ? residual : -residual));
    }
    final PredictionIntervals.Calibration c = PredictionIntervals.calibrate(predicted, observed, PredictionIntervals.FLOOR);
    assertEquals(10, c.n());
    assertEquals(1, c.low());
    assertEquals(28, c.high());
    // ceil(11 * 0.8) = 9: the 9th of ten; ceil(11 * 0.95) = 11: none
    assertEquals(0.9, c.factors()[0], 1e-9);
    assertEquals(Double.POSITIVE_INFINITY, c.factors()[1]);
    for (final double p : new double[] {2, 50, 400}) {
      final double[] in = c.interval(0, p);
      assertEquals(Math.expm1(Math.log1p(p) + 0.9), in[1], 1e-9);
    }
  }

  @Test
  @DisplayName("the global median, a constant, gets an ordinary finite factor, and finite widths "
      + "inner and outer, the outer constant other than the inner one")
  void constantGetsAFiniteFactor() {
    final PredictionIntervals.Sample s = sample(2000, 25, 17);
    final PredictionIntervals.Inner inner = PredictionIntervals.inner(s, s, 1);
    for (final PredictionIntervals.Calibration c : List.of(
        inner.checked().get(SimilarityScoring.GLOBAL),
        inner.calibrated().get(SimilarityScoring.GLOBAL))) {
      assertTrue(Double.isFinite(c.factors()[0]) && Double.isFinite(c.factors()[1]),
          Arrays.toString(c.factors()));
    }
    final PredictionIntervals.Sample fresh = sample(3000, 25, 18);
    final double[] five = new double[3000];
    Arrays.fill(five, 5);
    final Map<String, double[]> outerModels = new LinkedHashMap<>(fresh.predicted());
    outerModels.put(SimilarityScoring.GLOBAL, five);
    final PredictionIntervals.Sample outer = new PredictionIntervals.Sample(fresh.projects(),
        fresh.observed(), outerModels, fresh.bandTops(), PredictionIntervals.FLOOR);
    final List<PredictionIntervals.Read> reads = new ArrayList<>(inner.reads());
    reads.addAll(PredictionIntervals.outer(inner.calibrated(), outer, outer, 1));
    for (final PredictionIntervals.Read r : reads) {
      assertTrue(Double.isFinite(r.overall().medianWidth())
          && Double.isFinite(r.overall().relativeWidth()) && r.overall().relativeWidth() > 0,
          r.model() + " " + r.level() + " " + r.overall());
    }
  }

  @Test
  @DisplayName("on exchangeable synthetic issues, the coverage at L on new issues is close to L")
  void coverageCloseToTheLevel() {
    final PredictionIntervals.Sample calibration = sample(4000, 30, 7);
    final PredictionIntervals.Sample fresh = sample(40000, 30, 8);
    final List<PredictionIntervals.Read> reads = PredictionIntervals.outer(
        PredictionIntervals.calibrated(calibration), fresh, fresh, 1);
    assertEquals(2 * 2 * 2, reads.size());
    for (final PredictionIntervals.Read r : reads) {
      if (r.model().equals(SimilarityScoring.GLOBAL)) {
        // a constant of 3 against truths held at 1: the floor covers more, never less
        assertTrue(r.overall().coverage().value() > r.level() - 0.015,
            r.level() + " " + r.overall().coverage());
      } else {
        assertTrue(Math.abs(r.overall().coverage().value() - r.level()) < 0.015,
            r.level() + " " + r.overall().coverage());
      }
      assertTrue(r.overall().coverage().low() <= r.overall().coverage().value()
          && r.overall().coverage().value() <= r.overall().coverage().high());
    }
  }

  @Test
  @DisplayName("the coverage per band of the observed duration is read on half B at the band "
      + "limits, the error bands'; the bands group the issues after the fact and never move a "
      + "factor")
  void bandsOnHalfB() {
    final PredictionIntervals.Sample s = sample(2000, 25, 21);
    final PredictionIntervals.Inner inner = PredictionIntervals.inner(s, s, 1);
    final double[] tops = s.bandTops();
    for (final PredictionIntervals.Read r : inner.reads()) {
      final int l = PredictionIntervals.LEVELS.indexOf(r.level());
      final PredictionIntervals.Calibration c = inner.checked().get(r.model());
      final double[] p = s.predicted().get(r.model());
      for (int b = 0; b < PredictionIntervals.BANDS; b++) {
        final double low = b == 0 ? Double.NEGATIVE_INFINITY : tops[b - 1];
        final double high = b == 3 ? Double.POSITIVE_INFINITY : tops[b];
        int n = 0;
        double covered = 0;
        for (int i = 0; i < p.length; i++) {
          if (!inner.halves().a().contains(s.projects().get(i)) && s.observed()[i] > low
              && s.observed()[i] <= high) {
            final double[] in = c.interval(l, p[i]);
            covered += in[0] <= s.observed()[i] && s.observed()[i] <= in[1] ? 1 : 0;
            n++;
          }
        }
        final PredictionIntervals.Group g = r.bands().get(b);
        assertEquals(n, g.n(), r.model() + " band " + b);
        assertTrue(n > 0);
        assertEquals(covered / n, g.coverage().value(), EXACT);
      }
      assertEquals(r.overall().n(), r.bands().stream().mapToInt(g -> g.n()).sum());
    }
    // other band limits regroup the issues and move no factor, nor the overall figures
    final PredictionIntervals.Sample other = new PredictionIntervals.Sample(s.projects(),
        s.observed(), s.predicted(), new double[] {2, 4, 8}, PredictionIntervals.FLOOR);
    final PredictionIntervals.Inner regrouped = PredictionIntervals.inner(other, other, 1);
    for (final String m : s.predicted().keySet()) {
      assertEquals(Arrays.toString(inner.checked().get(m).factors()),
          Arrays.toString(regrouped.checked().get(m).factors()));
    }
    assertEquals(inner.reads().get(1).overall(), regrouped.reads().get(1).overall());
    assertNotEquals(inner.reads().get(1).bands(), regrouped.reads().get(1).bands());
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
        usual.predicted(), usual.bandTops(), PredictionIntervals.FLOOR);
    final PredictionIntervals.Inner again = PredictionIntervals.inner(usual, b, 1);
    final PredictionIntervals.Inner bothMoved = PredictionIntervals.inner(b, b, 1);
    for (final String m : usual.predicted().keySet()) {
      for (final PredictionIntervals.Inner other : List.of(again, bothMoved)) {
        assertEquals(Arrays.toString(inner.checked().get(m).factors()),
            Arrays.toString(other.checked().get(m).factors()), m);
      }
      // A and B together: B's labels count there
      assertNotEquals(Arrays.toString(inner.calibrated().get(m).factors()),
          Arrays.toString(bothMoved.calibrated().get(m).factors()), m);
      final double[] p = usual.predicted().get(m);
      assertEquals(Arrays.toString(PredictionIntervals.calibrate(p, usual.observed(), PredictionIntervals.FLOOR)
          .factors()), Arrays.toString(inner.calibrated().get(m).factors()), m);
    }
    // the check reads half B only: its issues, every model at both levels, on both readings
    assertEquals(2 * 2 * 2, inner.reads().size());
    for (final PredictionIntervals.Read r : inner.reads()) {
      assertEquals(inner.halves().bIssues(), r.overall().n());
    }
  }

  @Test
  @DisplayName("each group's figures: coverage, median width, width over the global median's on "
      + "the same issues, and the MAE")
  void groupFigures() {
    final PredictionIntervals.Sample s = sample(800, 12, 13);
    final PredictionIntervals.Inner inner = PredictionIntervals.inner(s, s, 1);
    for (final PredictionIntervals.Read r : inner.reads()) {
      final int l = PredictionIntervals.LEVELS.indexOf(r.level());
      final PredictionIntervals.Calibration c = inner.checked().get(r.model());
      final PredictionIntervals.Calibration g = inner.checked().get(SimilarityScoring.GLOBAL);
      final double[] p = s.predicted().get(r.model());
      final List<Double> widths = new ArrayList<>();
      double covered = 0;
      double error = 0;
      for (int i = 0; i < p.length; i++) {
        if (inner.halves().a().contains(s.projects().get(i))) {
          continue;
        }
        final double[] in = c.interval(l, p[i]);
        covered += in[0] <= s.observed()[i] && s.observed()[i] <= in[1] ? 1 : 0;
        widths.add(in[1] - in[0]);
        error += Math.abs(p[i] - s.observed()[i]);
      }
      final double[] gi = g.interval(l, 3);
      final PredictionIntervals.Group all = r.overall();
      assertEquals(covered / widths.size(), all.coverage().value(), EXACT);
      final double median = PredictionIntervals.median(
          widths.stream().mapToDouble(Double::doubleValue).toArray());
      assertEquals(median, all.medianWidth(), EXACT);
      assertEquals(median / (gi[1] - gi[0]), all.relativeWidth(), EXACT);
      assertEquals(error / widths.size(), all.mae(), EXACT);
      if (r.model().equals(SimilarityScoring.GLOBAL)) {
        assertEquals(1, all.relativeWidth(), EXACT);
      }
    }
    assertEquals(Double.POSITIVE_INFINITY,
        PredictionIntervals.median(new double[] {1, Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY, 2}));
    assertEquals(2.5, PredictionIntervals.median(new double[] {4, 1, 3, 2}));
  }
}
