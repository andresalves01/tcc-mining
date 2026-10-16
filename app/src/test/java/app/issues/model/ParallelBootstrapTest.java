package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The project-resampled intervals with their draws evaluated on several threads, against the
 * sequential loop they replaced, copied here verbatim as the oracle: every bound bit for bit.
 */
class ParallelBootstrapTest {

  /** Ninety-odd projects of very uneven sizes, rows interleaved, errors heavy-tailed. */
  private static final int ROWS = 3_000;

  private final List<String> projects = new ArrayList<>();
  private final double[] observed = new double[ROWS];
  private final double[] model = new double[ROWS];
  private final double[] reference = new double[ROWS];

  ParallelBootstrapTest() {
    final Random random = new Random(5);
    for (int i = 0; i < ROWS; i++) {
      final int p = (int) Math.floor(93 * Math.pow(random.nextDouble(), 2.5));
      projects.add("project-" + p);
      observed[i] = Math.exp(6 * random.nextDouble());
      model[i] = observed[i] * Math.exp(random.nextGaussian());
      reference[i] = observed[i] * Math.exp(1.3 * random.nextGaussian());
    }
  }

  private static long bits(final double x) {
    return Double.doubleToRawLongBits(x);
  }

  @Test
  @DisplayName("the gain's interval on one, three and twelve threads is the old sequential "
      + "loop's, bit for bit")
  void gain() {
    final ProjectBootstrap.Interval old = oldGain(projects, model, reference, observed);
    for (final int threads : List.of(1, 3, 12)) {
      final ProjectBootstrap.Interval now =
          ProjectBootstrap.gain(projects, model, reference, observed, threads);
      assertEquals(bits(old.gain()), bits(now.gain()), threads + " threads");
      assertEquals(bits(old.low()), bits(now.low()), threads + " threads");
      assertEquals(bits(old.high()), bits(now.high()), threads + " threads");
    }
  }

  @Test
  @DisplayName("the paired gap's interval on one, three and twelve threads is the old sequential "
      + "loop's, bit for bit")
  void compare() {
    final double[] a = new double[ROWS];
    final double[] b = new double[ROWS];
    for (int i = 0; i < ROWS; i++) {
      a[i] = Math.abs(model[i] - observed[i]);
      b[i] = Math.abs(reference[i] - observed[i]);
    }
    final ProjectBootstrap.Gap old = oldCompare(projects, a, b);
    for (final int threads : List.of(1, 3, 12)) {
      final ProjectBootstrap.Gap now = ProjectBootstrap.compare(projects, "a", a, "b", b, threads);
      assertEquals(bits(old.gap()), bits(now.gap()), threads + " threads");
      assertEquals(bits(old.low()), bits(now.low()), threads + " threads");
      assertEquals(bits(old.high()), bits(now.high()), threads + " threads");
    }
  }

  // --- the sequential code as it stood before the draws were evaluated in parallel ---

  private static ProjectBootstrap.Gap oldCompare(final List<String> projectOfRow,
      final double[] errorsA, final double[] errorsB) {
    final List<List<Integer>> projects = clusters(projectOfRow);
    final double point = gapOf(allRows(errorsA.length), errorsA, errorsB);
    final double[] draws = new double[ProjectBootstrap.DRAWS];
    final Random random = new Random(ProjectBootstrap.SEED);
    for (int d = 0; d < ProjectBootstrap.DRAWS; d++) {
      final List<Integer> sample = new ArrayList<>(errorsA.length);
      for (int p = 0; p < projects.size(); p++) {
        sample.addAll(projects.get(random.nextInt(projects.size())));
      }
      draws[d] = gapOf(sample, errorsA, errorsB);
    }
    Arrays.sort(draws);
    return new ProjectBootstrap.Gap("a", "b", point, draws[(int) (0.025 * ProjectBootstrap.DRAWS)],
        draws[(int) (0.975 * ProjectBootstrap.DRAWS) - 1]);
  }

  private static ProjectBootstrap.Interval oldGain(final List<String> projectOfRow,
      final double[] model, final double[] reference, final double[] observed) {
    final List<List<Integer>> projects = clusters(projectOfRow);
    final double point = gainOf(allRows(observed.length), model, reference, observed);
    final double[] draws = new double[ProjectBootstrap.DRAWS];
    final Random random = new Random(ProjectBootstrap.SEED);
    for (int d = 0; d < ProjectBootstrap.DRAWS; d++) {
      final List<Integer> sample = new ArrayList<>(observed.length);
      for (int p = 0; p < projects.size(); p++) {
        sample.addAll(projects.get(random.nextInt(projects.size())));
      }
      draws[d] = gainOf(sample, model, reference, observed);
    }
    Arrays.sort(draws);
    return new ProjectBootstrap.Interval(point, draws[(int) (0.025 * ProjectBootstrap.DRAWS)],
        draws[(int) (0.975 * ProjectBootstrap.DRAWS) - 1]);
  }

  private static List<List<Integer>> clusters(final List<String> projectOfRow) {
    final Map<String, List<Integer>> rows = new LinkedHashMap<>();
    for (int i = 0; i < projectOfRow.size(); i++) {
      rows.computeIfAbsent(projectOfRow.get(i), k -> new ArrayList<>()).add(i);
    }
    return new ArrayList<>(rows.values());
  }

  private static double gapOf(final List<Integer> sample, final double[] errorsA,
      final double[] errorsB) {
    double sum = 0;
    for (final int i : sample) {
      sum += errorsA[i] - errorsB[i];
    }
    return sum / sample.size();
  }

  private static List<Integer> allRows(final int n) {
    final List<Integer> all = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      all.add(i);
    }
    return all;
  }

  private static double gainOf(final List<Integer> sample, final double[] model,
      final double[] reference, final double[] observed) {
    double m = 0;
    double r = 0;
    for (final int i : sample) {
      m += Math.abs(model[i] - observed[i]);
      r += Math.abs(reference[i] - observed[i]);
    }
    return r == 0 ? 0 : 100.0 * (r - m) / r;
  }
}
