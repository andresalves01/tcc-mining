package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.model.Metrics;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ordering intervals (ticket 16, a declared measure): the paired difference in Spearman and in
 * pairwise ordering accuracy between two models, on the very draws of whole projects the MAE
 * comparisons resample, with the definitions every score uses.
 */
class OrderingBootstrapTest {

  /** Thirty projects of uneven sizes, whole business days as the truth, ties in it. */
  private static final int ROWS = 360;

  private final List<String> projects = new ArrayList<>();
  private final double[] observed = new double[ROWS];
  private final double[] a = new double[ROWS];
  private final double[] b = new double[ROWS];

  OrderingBootstrapTest() {
    final Random random = new Random(7);
    for (int i = 0; i < ROWS; i++) {
      projects.add("p" + (int) Math.floor(30 * Math.pow(random.nextDouble(), 2)));
      observed[i] = 1 + Math.floor(Math.exp(3 * random.nextDouble()));
      a[i] = observed[i] * Math.exp(0.8 * random.nextGaussian());
      // b predicts in steps, so it ties often
      b[i] = Math.rint(observed[i] * Math.exp(1.2 * random.nextGaussian()));
    }
  }

  private static long bits(final double x) {
    return Double.doubleToRawLongBits(x);
  }

  @Test
  @DisplayName("a model compared with itself differs by exactly 0 [0, 0] in both measures")
  void itselfIsExactlyZero() {
    final ProjectBootstrap.Ordering o =
        ProjectBootstrap.ordering(projects, "a", a, "a", a, observed, 3);
    for (final ProjectBootstrap.Gap g : List.of(o.spearman(), o.pairwise())) {
      assertEquals(bits(0.0), bits(g.gap()), g.toString());
      assertEquals(bits(0.0), bits(g.low()), g.toString());
      assertEquals(bits(0.0), bits(g.high()), g.toString());
    }
  }

  @Test
  @DisplayName("the point is the difference of the two scores' own Spearman and pairwise ordering")
  void pointIsTheScoresDifference() {
    final ProjectBootstrap.Ordering o =
        ProjectBootstrap.ordering(projects, "a", a, "b", b, observed, 1);
    final Score sa = Score.of("a", a, observed, 1, 1);
    final Score sb = Score.of("b", b, observed, 1, 1);
    assertEquals(bits(sa.spearman() - sb.spearman()), bits(o.spearman().gap()));
    assertEquals(bits(sa.pairwise() - sb.pairwise()), bits(o.pairwise().gap()));
    assertEquals("a", o.a());
    assertEquals("b", o.b());
  }

  @Test
  @DisplayName("the intervals are the 2.5% and 97.5% draws of the differences recomputed, by "
      + "Metrics.spearman and Score.pairwiseAccuracy, on the projects the MAE comparison draws: "
      + "the same seed, the same draws; the same bits on one thread or several")
  void sameDrawsAsTheErrorComparison() {
    final List<List<Integer>> samples = samples(projects);
    final double[] errorsA = new double[ROWS];
    final double[] errorsB = new double[ROWS];
    for (int i = 0; i < ROWS; i++) {
      errorsA[i] = Math.abs(a[i] - observed[i]);
      errorsB[i] = Math.abs(b[i] - observed[i]);
    }
    // the samples below are the MAE comparison's: they reproduce its interval bit for bit
    final ProjectBootstrap.Gap mae = ProjectBootstrap.compare(projects, "a", errorsA, "b",
        errorsB, 1);
    final double[] gaps = interval(samples, s -> {
      double sum = 0;
      for (final int i : s) {
        sum += errorsA[i] - errorsB[i];
      }
      return sum / s.size();
    });
    assertEquals(bits(mae.low()), bits(gaps[0]));
    assertEquals(bits(mae.high()), bits(gaps[1]));

    final double[] spearman = interval(samples,
        s -> Metrics.spearman(pick(a, s), pick(observed, s))
            - Metrics.spearman(pick(b, s), pick(observed, s)));
    final double[] pairwise = interval(samples,
        s -> Score.pairwiseAccuracy(pick(a, s), pick(observed, s))
            - Score.pairwiseAccuracy(pick(b, s), pick(observed, s)));
    for (final int threads : List.of(1, 4)) {
      final ProjectBootstrap.Ordering o =
          ProjectBootstrap.ordering(projects, "a", a, "b", b, observed, threads);
      assertEquals(bits(spearman[0]), bits(o.spearman().low()), threads + " threads");
      assertEquals(bits(spearman[1]), bits(o.spearman().high()), threads + " threads");
      assertEquals(bits(pairwise[0]), bits(o.pairwise().low()), threads + " threads");
      assertEquals(bits(pairwise[1]), bits(o.pairwise().high()), threads + " threads");
    }
  }

  /** The rows of each draw, picked as the documented sequence: one Random(SEED), draw by draw. */
  private static List<List<Integer>> samples(final List<String> projectOfRow) {
    final Map<String, List<Integer>> rows = new LinkedHashMap<>();
    for (int i = 0; i < projectOfRow.size(); i++) {
      rows.computeIfAbsent(projectOfRow.get(i), k -> new ArrayList<>()).add(i);
    }
    final List<List<Integer>> clusters = new ArrayList<>(rows.values());
    final Random random = new Random(ProjectBootstrap.SEED);
    final List<List<Integer>> out = new ArrayList<>(ProjectBootstrap.DRAWS);
    for (int d = 0; d < ProjectBootstrap.DRAWS; d++) {
      final List<Integer> sample = new ArrayList<>();
      for (int p = 0; p < clusters.size(); p++) {
        sample.addAll(clusters.get(random.nextInt(clusters.size())));
      }
      out.add(sample);
    }
    return out;
  }

  private static double[] interval(final List<List<Integer>> samples,
      final ToDoubleFunction<List<Integer>> statistic) {
    final double[] draws = samples.stream().mapToDouble(statistic).toArray();
    Arrays.sort(draws);
    return new double[] {draws[(int) (0.025 * ProjectBootstrap.DRAWS)],
        draws[(int) (0.975 * ProjectBootstrap.DRAWS) - 1]};
  }

  private static double[] pick(final double[] values, final List<Integer> rows) {
    final double[] out = new double[rows.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = values[rows.get(k)];
    }
    return out;
  }
}
