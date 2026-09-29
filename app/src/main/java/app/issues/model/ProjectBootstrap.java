package app.issues.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * An interval for a model's gain over a reference, resampling <b>whole projects</b>.
 *
 * <p>Issues of one project are not independent of each other: they share a team, a tracker's
 * habits and a release rhythm. With ninety-odd projects and tens of thousands of rows, an interval
 * that resampled rows would treat those rows as independent and come out far too narrow. Drawing
 * projects with replacement and keeping all their rows is the resampling that respects what the
 * data actually is.
 *
 * <p>Complements, and does not replace, the paired row bootstrap the pull-request study used for
 * model against model on the same rows; that one answers a different question.
 */
public final class ProjectBootstrap {

  public static final int DRAWS = 600;
  public static final long SEED = 20260927L;

  /** The gain in percent, {@code 100 * (reference - model) / reference}, with its 95% interval. */
  public record Interval(double gain, double low, double high) {

    /** True when the interval leaves out zero on the side of the model. */
    public boolean modelBetter() {
      return low > 0;
    }

    public boolean referenceBetter() {
      return high < 0;
    }
  }

  private ProjectBootstrap() {}

  public static Interval gain(final List<String> projectOfRow, final double[] model,
      final double[] reference, final double[] observed) {

    final Map<String, List<Integer>> rows = new LinkedHashMap<>();
    for (int i = 0; i < projectOfRow.size(); i++) {
      rows.computeIfAbsent(projectOfRow.get(i), k -> new ArrayList<>()).add(i);
    }
    final List<List<Integer>> projects = new ArrayList<>(rows.values());
    final double point = gainOf(allRows(observed.length), model, reference, observed);
    final double[] draws = new double[DRAWS];
    final Random random = new Random(SEED);
    for (int d = 0; d < DRAWS; d++) {
      final List<Integer> sample = new ArrayList<>(observed.length);
      for (int p = 0; p < projects.size(); p++) {
        sample.addAll(projects.get(random.nextInt(projects.size())));
      }
      draws[d] = gainOf(sample, model, reference, observed);
    }
    Arrays.sort(draws);
    return new Interval(point, draws[(int) (0.025 * DRAWS)], draws[(int) (0.975 * DRAWS) - 1]);
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
