package app.issues.model;

import app.model.Metrics;
import app.model.Parallel;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Function;
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

  /**
   * Two specifications scored on the same rows, their gap in mean absolute error -- or, inside an
   * {@link Ordering}, in an ordering measure -- with its 95% interval from resampling whole
   * projects, both recomputed on the same draw.
   *
   * @param gap {@code MAE(a) - MAE(b)} on every row: negative when {@code a} errs less; in an
   *     {@link Ordering}, the measure of {@code a} minus that of {@code b}: positive when {@code a}
   *     orders better
   */
  public record Gap(String a, String b, double gap, double low, double high) {

    /**
     * The registered criterion (the pre-registration, section 8): an interval that contains zero is a
     * tie, whatever the order of the table.
     */
    public boolean tied() {
      return low <= 0 && high >= 0;
    }
  }

  /**
   * A mean over the rows -- of 0 and 1, a share, as a prediction interval's coverage (ticket 24)
   * -- with its 95% interval from the same {@link #DRAWS} draws of whole projects (seed {@link
   * #SEED}), a project drawn twice counted twice ({@link #share}).
   */
  public record Share(double value, double low, double high) {}

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
    return gap(a, b, clusters(projectOfRow), errorsA.length, threads,
        sample -> gapOf(sample, errorsA, errorsB));
  }

  /**
   * A paired difference between {@code a} and {@code b}: its value on every row, and its 95%
   * interval from the {@link #DRAWS} draws of whole projects every comparison here resamples.
   */
  private static Gap gap(final String a, final String b, final List<List<Integer>> projects,
      final int rows, final int threads, final ToDoubleFunction<List<Integer>> statistic) {
    final double point = statistic.applyAsDouble(allRows(rows));
    final double[] draws = draws(projects, rows, threads, statistic);
    Arrays.sort(draws);
    return new Gap(a, b, point, draws[(int) (0.025 * DRAWS)], draws[(int) (0.975 * DRAWS) - 1]);
  }

  /**
   * Two models' difference in ordering on the same rows, {@code a} minus {@code b}, each measure
   * as a {@link Gap}: Spearman ({@link Metrics#spearman}, predicted against observed, as {@link
   * Score#of} reads it) and pairwise ordering accuracy ({@link Score#pairwiseAccuracy}), their
   * definitions unchanged.
   *
   * <p>A declared measure (ticket 16), stated before the outer test was opened: each interval is
   * read from the very draws of whole projects the error comparisons resample ({@link #compare}:
   * {@link #DRAWS} draws, seed {@link #SEED}), both models' measure recomputed on each draw's
   * rows, a project drawn twice counted twice. A model compared with itself differs by exactly
   * zero on every draw.
   */
  public record Ordering(String a, String b, Gap spearman, Gap pairwise) {}

  /**
   * The ordering comparison of {@code a} and {@code b}, its draws evaluated on up to {@code
   * threads} threads, the same bits on any number.
   *
   * @param predictedA one prediction per row of {@code a}, {@code predictedB} of {@code b}, and
   *     {@code observed} the truth, same rows as {@code projectOfRow}
   */
  public static Ordering ordering(final List<String> projectOfRow, final String a,
      final double[] predictedA, final String b, final double[] predictedB,
      final double[] observed, final int threads) {
    if (predictedA.length != observed.length || predictedB.length != observed.length
        || observed.length != projectOfRow.size()) {
      throw new IllegalArgumentException("the two predictions must be of the same rows");
    }
    final List<List<Integer>> projects = clusters(projectOfRow);
    return new Ordering(a, b,
        gap(a, b, projects, observed.length, threads, sample -> {
          final double[] o = rows(observed, sample);
          return Metrics.spearman(rows(predictedA, sample), o)
              - Metrics.spearman(rows(predictedB, sample), o);
        }),
        gap(a, b, projects, observed.length, threads, sample -> {
          final double[] o = rows(observed, sample);
          return Score.pairwiseAccuracy(rows(predictedA, sample), o)
              - Score.pairwiseAccuracy(rows(predictedB, sample), o);
        }));
  }

  private static double[] rows(final double[] values, final List<Integer> sample) {
    final double[] out = new double[sample.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = values[sample.get(k)];
    }
    return out;
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
    final double[][] each = vectors(projects, rows, threads,
        sample -> new double[] {statistic.applyAsDouble(sample)});
    final double[] draws = new double[DRAWS];
    for (int d = 0; d < DRAWS; d++) {
      draws[d] = each[d][0];
    }
    return draws;
  }

  /**
   * Each draw's statistic, a vector, in the order of the draws, picked and evaluated as {@link
   * #draws} says: the projects of every draw picked first from one {@code Random(SEED)}, then
   * each draw evaluated alone on one of up to {@code threads} threads.
   */
  private static double[][] vectors(final List<List<Integer>> projects, final int rows,
      final int threads, final Function<List<Integer>, double[]> statistic) {
    final Random random = new Random(SEED);
    final int[][] picks = new int[DRAWS][projects.size()];
    for (int d = 0; d < DRAWS; d++) {
      for (int p = 0; p < projects.size(); p++) {
        picks[d][p] = random.nextInt(projects.size());
      }
    }
    final int chunks = Math.min(DRAWS, Math.max(1, threads) * 4);
    final List<Callable<double[][]>> jobs = new ArrayList<>(chunks);
    for (int c = 0; c < chunks; c++) {
      final int from = (int) ((long) c * DRAWS / chunks);
      final int to = (int) ((long) (c + 1) * DRAWS / chunks);
      jobs.add(() -> {
        final double[][] out = new double[to - from][];
        for (int d = from; d < to; d++) {
          final List<Integer> sample = new ArrayList<>(rows);
          for (final int pick : picks[d]) {
            sample.addAll(projects.get(pick));
          }
          out[d - from] = statistic.apply(sample);
        }
        return out;
      });
    }
    final double[][] draws = new double[DRAWS][];
    int at = 0;
    for (final double[][] part : Parallel.map(threads, jobs)) {
      System.arraycopy(part, 0, draws, at, part.length);
      at += part.length;
    }
    return draws;
  }

  /**
   * Per component of a statistic that gives {@code width} numbers -- the coefficients of a model
   * refitted on the drawn rows, as the relevance section reads them -- its 95% interval from the
   * same {@link #DRAWS} draws of whole projects (seed {@link #SEED}) every interval here reads, a
   * project drawn twice counted twice: per component the 2.5th and 97.5th percentiles of the
   * draws, read as {@link #compare} reads them. Evaluated on up to {@code threads} threads, the
   * same bits on any number.
   *
   * @param projectOfRow the project of each row the statistic may draw
   * @return per component, its low and high end
   */
  public static double[][] intervals(final List<String> projectOfRow, final int width,
      final Function<List<Integer>, double[]> statistic, final int threads) {
    final double[][] each = vectors(clusters(projectOfRow), projectOfRow.size(), threads,
        sample -> {
          final double[] v = statistic.apply(sample);
          if (v.length != width) {
            throw new IllegalArgumentException("a draw gave " + v.length + " numbers, not "
                + width);
          }
          return v;
        });
    final double[][] out = new double[width][];
    for (int c = 0; c < width; c++) {
      final double[] column = new double[DRAWS];
      for (int d = 0; d < DRAWS; d++) {
        column[d] = each[d][c];
      }
      Arrays.sort(column);
      out[c] = new double[] {column[(int) (0.025 * DRAWS)], column[(int) (0.975 * DRAWS) - 1]};
    }
    return out;
  }

  private static List<List<Integer>> clusters(final List<String> projectOfRow) {
    final Map<String, List<Integer>> rows = new LinkedHashMap<>();
    for (int i = 0; i < projectOfRow.size(); i++) {
      rows.computeIfAbsent(projectOfRow.get(i), k -> new ArrayList<>()).add(i);
    }
    return new ArrayList<>(rows.values());
  }

  /** A descriptive statistic's interval; undefined draws are counted, never treated as zero. */
  public record Bounds(double low, double high, int validDraws) {}

  /**
   * The existing project draws for statistics that can be undefined (for example a sample SD
   * with fewer than two positive estimates). Percentiles use only finite draws, with their count
   * exposed. Fewer than two projects cannot support an interval across projects.
   */
  public static List<Bounds> finiteIntervals(final List<String> projectOfRow, final int width,
      final Function<List<Integer>, double[]> statistic, final int threads) {
    final List<List<Integer>> projects = clusters(projectOfRow);
    if (projects.size() < 2) {
      return java.util.stream.IntStream.range(0, width)
          .mapToObj(i -> new Bounds(Double.NaN, Double.NaN, 0)).toList();
    }
    final double[][] draws = vectors(projects, projectOfRow.size(), threads, sample -> {
      final double[] values = statistic.apply(sample);
      if (values.length != width) {
        throw new IllegalArgumentException("wrong statistic width: " + values.length);
      }
      return values;
    });
    final List<Bounds> out = new ArrayList<>();
    for (int c = 0; c < width; c++) {
      final int column = c;
      final double[] finite = Arrays.stream(draws).mapToDouble(d -> d[column])
          .filter(Double::isFinite).sorted().toArray();
      out.add(finite.length == 0 ? new Bounds(Double.NaN, Double.NaN, 0)
          : new Bounds(finite[(int) (0.025 * finite.length)],
              finite[Math.max(0, (int) (0.975 * finite.length) - 1)], finite.length));
    }
    return List.copyOf(out);
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
   * The mean of {@code values} with its interval, the draws evaluated on up to {@code threads}
   * threads, the same bits on any number.
   *
   * @param values one per row, same rows as {@code projectOfRow}; at least one
   */
  public static Share share(final List<String> projectOfRow, final double[] values,
      final int threads) {
    if (values.length != projectOfRow.size() || values.length == 0) {
      throw new IllegalArgumentException("one value per row, and at least one row");
    }
    final ToDoubleFunction<List<Integer>> mean = sample -> {
      double sum = 0;
      for (final int i : sample) {
        sum += values[i];
      }
      return sum / sample.size();
    };
    final double[] draws = draws(clusters(projectOfRow), values.length, threads, mean);
    Arrays.sort(draws);
    return new Share(mean.applyAsDouble(allRows(values.length)), draws[(int) (0.025 * DRAWS)],
        draws[(int) (0.975 * DRAWS) - 1]);
  }

  private static List<Integer> allRows(final int n) {
    final List<Integer> all = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      all.add(i);
    }
    return all;
  }

}
