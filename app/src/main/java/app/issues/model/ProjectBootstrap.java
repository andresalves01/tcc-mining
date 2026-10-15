package app.issues.model;

import app.model.Parallel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.ToDoubleFunction;

/**
 * An interval for a model's gain over a reference, resampling <b>whole projects</b>.
 *
 * <p>Issues of one project are not independent of each other: they share a team, a tracker's
 * habits and a release rhythm. With ninety-odd projects and tens of thousands of rows, an interval
 * that resampled rows would treat those rows as independent and come out far too narrow. Drawing
 * projects with replacement and keeping all their rows is the resampling that respects what the
 * data actually is.
 *
 * <p>Complements, and does not replace, the paired row bootstrap ({@code app.study.model.Bootstrap}) for
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

  /**
   * Two specifications scored on the same rows, their gap in mean absolute error with its 95%
   * interval from resampling whole projects, both errors recomputed on the same draw.
   *
   * @param gapHours {@code MAE(a) - MAE(b)} on every row: negative when {@code a} errs less
   */
  public record Gap(String a, String b, double gapHours, double low, double high) {

    /**
     * The registered criterion (the pre-registration, section 8): an interval that contains zero is a
     * tie, whatever the order of the table.
     */
    public boolean tied() {
      return low <= 0 && high >= 0;
    }
  }

  private ProjectBootstrap() {}

  /**
   * The paired comparison with projects as the unit of resampling: the tie criterion of the
   * reserved run. The row-paired one stays beside it as the narrower
   * reading. The draws are evaluated on the threads {@link Parallel#BOOTSTRAP_THREADS} asks for.
   *
   * @param errorsA absolute error per row of {@code a}, {@code errorsB} of {@code b}, same rows
   */
  public static Gap compare(final List<String> projectOfRow, final String a,
      final double[] errorsA, final String b, final double[] errorsB) {
    return compare(projectOfRow, a, errorsA, b, errorsB,
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * The comparison with its draws evaluated on up to {@code threads} threads, the same bits on any
   * number: the projects of every draw are picked in sequence before any is evaluated.
   */
  public static Gap compare(final List<String> projectOfRow, final String a,
      final double[] errorsA, final String b, final double[] errorsB, final int threads) {
    if (errorsA.length != errorsB.length || errorsA.length != projectOfRow.size()) {
      throw new IllegalArgumentException("the two errors must be of the same rows");
    }
    final List<List<Integer>> projects = clusters(projectOfRow);
    final double point = gapOf(allRows(errorsA.length), errorsA, errorsB);
    final double[] draws = draws(projects, errorsA.length, threads,
        sample -> gapOf(sample, errorsA, errorsB));
    Arrays.sort(draws);
    return new Gap(a, b, point, draws[(int) (0.025 * DRAWS)], draws[(int) (0.975 * DRAWS) - 1]);
  }

  /**
   * Each draw's statistic, in the order of the draws. The projects of every draw are picked first,
   * all on this thread from one {@code Random(SEED)} in the same sequence as ever -- draw by draw,
   * {@code projects.size()} picks each -- so the samples do not depend on the threads. Then each
   * draw is evaluated alone, on one of up to {@code threads} threads, over its rows in the order
   * they were drawn, and written to its own slot: every value is what the sequential loop gave.
   *
   * @param rows the number of rows, the capacity of a sample
   */
  private static double[] draws(final List<List<Integer>> projects, final int rows,
      final int threads, final ToDoubleFunction<List<Integer>> statistic) {
    final Random random = new Random(SEED);
    final int[][] picks = new int[DRAWS][projects.size()];
    for (int d = 0; d < DRAWS; d++) {
      for (int p = 0; p < projects.size(); p++) {
        picks[d][p] = random.nextInt(projects.size());
      }
    }
    final int chunks = Math.min(DRAWS, Math.max(1, threads) * 4);
    final List<Callable<double[]>> jobs = new ArrayList<>(chunks);
    for (int c = 0; c < chunks; c++) {
      final int from = (int) ((long) c * DRAWS / chunks);
      final int to = (int) ((long) (c + 1) * DRAWS / chunks);
      jobs.add(() -> {
        final double[] out = new double[to - from];
        for (int d = from; d < to; d++) {
          final List<Integer> sample = new ArrayList<>(rows);
          for (final int pick : picks[d]) {
            sample.addAll(projects.get(pick));
          }
          out[d - from] = statistic.applyAsDouble(sample);
        }
        return out;
      });
    }
    final double[] draws = new double[DRAWS];
    int at = 0;
    for (final double[] part : Parallel.map(threads, jobs)) {
      System.arraycopy(part, 0, draws, at, part.length);
      at += part.length;
    }
    return draws;
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

  /**
   * The model's gain over the reference with its 95% interval from resampling whole projects; the
   * draws are evaluated on the threads {@link Parallel#BOOTSTRAP_THREADS} asks for.
   */
  public static Interval gain(final List<String> projectOfRow, final double[] model,
      final double[] reference, final double[] observed) {
    return gain(projectOfRow, model, reference, observed,
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * The gain with its draws evaluated on up to {@code threads} threads, the same bits on any
   * number: the projects of every draw are picked in sequence before any is evaluated.
   */
  public static Interval gain(final List<String> projectOfRow, final double[] model,
      final double[] reference, final double[] observed, final int threads) {

    final List<List<Integer>> projects = clusters(projectOfRow);
    final double point = gainOf(allRows(observed.length), model, reference, observed);
    final double[] draws = draws(projects, observed.length, threads,
        sample -> gainOf(sample, model, reference, observed));
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
