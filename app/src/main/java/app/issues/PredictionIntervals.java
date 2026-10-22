package app.issues;

import app.issues.model.ProjectBootstrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The study's prediction intervals (ticket 24, items 53 and 54): how
 * far a model's estimate of one issue can be trusted, beside the mean absolute error, which says
 * only how much it misses on average. Descriptive: nothing here chooses a model, k or column.
 *
 * <p><b>Interval.</b> Split conformal, symmetric on the {@code log1p} scale: {@code
 * expm1(log1p(prediction) +- factor)}, the prediction divided and multiplied by one factor, its
 * lower end raised to {@link #FLOOR}, one business day, the shortest duration of the population;
 * an upper end below it is raised too, so the interval is never empty, which can add a little
 * coverage at one business day. Levels 80% and 95%, side by side, neither the main one ({@link
 * #LEVELS}).
 *
 * <p><b>Factor.</b> One per model and level (item 54), of the absolute residuals {@code
 * |log1p(observed) - log1p(predicted)|} of all the model's calibration issues: the definition is
 * {@link #FACTOR}, here only ({@link #factor}). It is not {@link TailCut#quantile}'s
 * interpolation: the finite-sample guarantee of split conformal holds for the order statistic.
 *
 * <p><b>Reading by band.</b> Beside the overall figures, each is read per band of the observed
 * duration, the error bands' own ({@link BusinessDays#bands}): at the quartiles of the observed
 * business days of the cut fitting rows of the scope, never of validation or test labels. A
 * reading after the fact: the band never chooses the factor, the observed duration being unknown
 * when the interval is given.
 *
 * <p><b>Calibration</b> ({@link #inner}). On the usual-flow inner validation issues, split in two
 * halves by whole projects ({@link #halves}): half A calibrates, half B checks the coverage, on
 * the usual flow (the main reading) and on every validation issue of B (the complementary one).
 * The factors for every later use are then recomputed on A and B together. A project with no
 * usual-flow validation issue is B's.
 *
 * <p><b>Outer test</b> ({@link #outer}, item 53 viii). The factors calibrated on the whole
 * usual-flow inner validation by the inner models are applied to the outer models' predictions,
 * an approximation ({@link #OUTER}).
 */
public final class PredictionIntervals {

  /** The levels, side by side. */
  public static final List<Double> LEVELS = List.of(0.80, 0.95);

  /** The lowest lower end, in business days: the population's shortest duration. */
  public static final double FLOOR = 1.0;

  /** The seed of the halves' own {@link Random}. */
  public static final long HALVES_SEED = 1738L;

  /** The bands of the observed duration each figure is also read by: the error bands. */
  public static final int BANDS = 4;

  /** The factor's definition, in words, the one place it is stated. */
  public static final String FACTOR = "the factor at level L of a model is the k-th smallest "
      + "absolute residual |log1p(observed) - log1p(predicted)| of its n calibration issues, all "
      + "of them, k = ceil((n + 1) L): split conformal's finite-sample correction, the quantile "
      + "at ceil((n + 1) L) / n read as an order statistic, without interpolation; infinite when "
      + "k exceeds n";

  /** The intervals, in words, for the report and the run record. */
  public static final String RULE = "split-conformal prediction intervals, symmetric on the "
      + "log1p scale: expm1(log1p(prediction) +- factor), the lower end raised to 1 business day "
      + "(an upper end below it too, which can add a little coverage at 1 day); levels 80% and "
      + "95%; one factor per model and level; calibrated on the usual-flow inner validation "
      + "issues, split by whole projects (in name order, shuffled with Random(1738), to half A "
      + "until A holds at least half of those issues, the rest to half B): A calibrates, B checks "
      + "the coverage, with its 95% interval resampling whole projects, overall and per band of "
      + "the observed duration (the error bands, a reading after the fact that never chooses "
      + "the factor); the factors for later use recomputed on A and B together";

  /** Population-specific text; the legacy rule retains its original one-day floor. */
  public static String rule(final double floor) {
    if (floor == FLOOR) return RULE;
    if (floor != 0) throw new IllegalArgumentException("invalid interval floor");
    return RULE.replace("raised to 1 business day", "raised to 0 business day")
        .replace("coverage at 1 day", "coverage at the lower bound");
  }

  /** What the outer test's intervals are, in words. */
  public static final String OUTER = "the outer models' intervals take the factors the inner "
      + "models calibrated on the whole usual-flow inner validation, an approximation: the outer "
      + "models are refitted on the whole outer training side, so no held-out outer issue is left "
      + "to calibrate on; the outer coverage shows whether it holds; the bands of the observed "
      + "duration are those of the outer training rows fitted";

  /**
   * The calibration halves.
   *
   * @param a the projects of half A; every other project is half B's
   * @param aIssues the calibration issues of A
   * @param bIssues those of B
   */
  public record Halves(Set<String> a, int aIssues, int bIssues) {}

  /**
   * One model's calibration.
   *
   * @param n its calibration issues
   * @param factors per level of {@link #LEVELS}, the factor
   * @param low the smallest of its predictions on the calibration issues, {@code high} the largest
   */
  public record Calibration(int n, double[] factors, double low, double high, double floor) {
    public Calibration(final int n, final double[] factors, final double low, final double high) {
      this(n, factors, low, high, FLOOR);
    }

    /** The interval at the level of index {@code level} around {@code prediction}. */
    public double[] interval(final int level, final double prediction) {
      return PredictionIntervals.interval(prediction, factors[level], floor);
    }
  }

  /**
   * The figures of a group of checked issues.
   *
   * @param coverage the share of them inside their interval, with its 95% interval resampling
   *     whole projects; null when the group is empty
   * @param medianWidth the median width of their intervals, in business days
   * @param relativeWidth that over the global median's median width on the same issues
   * @param mae the model's mean absolute error on them, in business days
   */
  public record Group(int n, ProjectBootstrap.Share coverage, double medianWidth,
      double relativeWidth, double mae) {}

  /**
   * One model at one level on one reading's checked issues: overall and per band of the observed
   * duration.
   *
   * @param bands one per band of {@link BusinessDays#bands}, the first the shortest durations
   */
  public record Read(TailCut.Reading reading, String model, double level, Group overall,
      List<Group> bands, Map<String, Group> strata) {
    public Read(final TailCut.Reading reading, final String model, final double level,
        final Group overall, final List<Group> bands) {
      this(reading, model, level, overall, bands, Map.of());
    }
  }

  /**
   * Issues and every model's predictions of them.
   *
   * @param predicted per model, in the report's order, one prediction per issue
   * @param bandTops the limits of the bands of the observed duration: the quartiles of the
   *     observed business days of the cut fitting rows of the scope ({@link
   *     SimilarityScoring.Report#bandTops})
   */
  public record Sample(List<String> projects, double[] observed,
      Map<String, double[]> predicted, double[] bandTops, double floor) {
    public Sample(final List<String> projects, final double[] observed,
        final Map<String, double[]> predicted, final double[] bandTops) {
      this(projects, observed, predicted, bandTops, FLOOR);
    }

    /** A scoring's issues, predictions and band limits. */
    public static Sample of(final SimilarityScoring.Report r) {
      return of(r, FLOOR);
    }

    public static Sample of(final SimilarityScoring.Report r, final double floor) {
      final Map<String, double[]> predicted = new LinkedHashMap<>();
      r.models().forEach(m -> predicted.put(m.name(), m.predicted()));
      return new Sample(r.scoredUnits().stream().map(IssueUnit::project).toList(), r.observed(),
          predicted, r.bandTops(), floor);
    }
  }

  /**
   * The inner calibration and its check.
   *
   * @param checked per model, calibrated on half A: what the check on half B reads
   * @param calibrated per model, recomputed on A and B together: what every later use reads
   * @param reads per reading (the usual flow, then every issue), model and level, the check on B
   */
  public record Inner(Halves halves, Map<String, Calibration> checked,
      Map<String, Calibration> calibrated, List<Read> reads) {}

  private PredictionIntervals() {}

  /**
   * The halves of the calibration issues, by whole projects: the projects in name order, shuffled
   * with a {@link Random} of {@link #HALVES_SEED} of their own, are given in that order to half A
   * until A holds at least half of the issues; the rest are B's.
   *
   * @param projectOfIssue the project of each calibration issue
   */
  public static Halves halves(final List<String> projectOfIssue) {
    if (projectOfIssue.isEmpty()) {
      throw new IllegalArgumentException("no calibration issue to split");
    }
    final Map<String, Integer> issues = new TreeMap<>();
    projectOfIssue.forEach(p -> issues.merge(p, 1, Integer::sum));
    final List<String> order = new ArrayList<>(issues.keySet());
    Collections.shuffle(order, new Random(HALVES_SEED));
    final Set<String> a = new TreeSet<>();
    int inA = 0;
    for (final String p : order) {
      if (2L * inA >= projectOfIssue.size()) {
        break;
      }
      a.add(p);
      inA += issues.get(p);
    }
    return new Halves(Collections.unmodifiableSet(a), inA, projectOfIssue.size() - inA);
  }

  /**
   * The factor at {@code level} of the absolute residuals given: {@link #FACTOR}. The {@code
   * 1e-9} keeps a product such as {@code 20 * 0.95} from rounding up past a whole number.
   */
  static double factor(final double[] absoluteResiduals, final double level) {
    final int n = absoluteResiduals.length;
    final int k = (int) Math.ceil((n + 1) * level - 1e-9);
    if (k > n) {
      return Double.POSITIVE_INFINITY;
    }
    final double[] sorted = absoluteResiduals.clone();
    Arrays.sort(sorted);
    return sorted[k - 1];
  }

  /**
   * The interval around {@code prediction} at {@code factor}: {@code expm1(log1p(prediction) +-
   * factor)}, both ends at least {@link #FLOOR}.
   */
  public static double[] interval(final double prediction, final double factor) {
    return interval(prediction, factor, FLOOR);
  }

  public static double[] interval(final double prediction, final double factor, final double floor) {
    if (floor != 0 && floor != FLOOR) throw new IllegalArgumentException("invalid interval floor");
    final double centre = Math.log1p(prediction);
    final double low = Math.max(floor, Math.expm1(centre - factor));
    final double high = Math.max(floor, Math.expm1(centre + factor));
    return new double[] {low, high};
  }

  /**
   * The band, 0 to 3, of {@code observed} at {@code tops}, as {@link BusinessDays#bands} cuts
   * them: up to the first, above it up to the second, above that up to the third, above the third.
   */
  static int band(final double[] tops, final double observed) {
    for (int b = 0; b < tops.length; b++) {
      if (observed <= tops[b]) {
        return b;
      }
    }
    return tops.length;
  }

  /** A model's calibration on the issues given, its own predictions of them and their truth. */
  public static Calibration calibrate(final double[] predicted, final double[] observed) {
    return calibrate(predicted, observed, FLOOR);
  }

  public static Calibration calibrate(final double[] predicted, final double[] observed,
      final double floor) {
    if (predicted.length != observed.length || predicted.length == 0) {
      throw new IllegalArgumentException("one prediction per calibration issue, and at least one");
    }
    final double[] residuals = new double[predicted.length];
    for (int i = 0; i < predicted.length; i++) {
      residuals[i] = Math.abs(Math.log1p(observed[i]) - Math.log1p(predicted[i]));
    }
    final double[] factors = new double[LEVELS.size()];
    for (int l = 0; l < LEVELS.size(); l++) {
      factors[l] = factor(residuals, LEVELS.get(l));
    }
    return new Calibration(predicted.length, factors, Arrays.stream(predicted).min()
        .getAsDouble(), Arrays.stream(predicted).max().getAsDouble(), floor);
  }

  /**
   * The inner calibration: the halves of {@code usual}'s issues, every model calibrated on half
   * A, its check on half B of {@code usual} (the main reading) and of {@code all} (the
   * complementary one), and every model calibrated again on all of {@code usual}.
   *
   * @param usual the usual-flow inner validation issues, the calibration issues
   * @param all every inner validation issue, the same models
   */
  public static Inner inner(final Sample usual, final Sample all, final int threads) {
    final Halves halves = halves(usual.projects());
    final List<Integer> a = rows(usual, halves, true);
    final Map<String, Calibration> checked = new LinkedHashMap<>();
    usual.predicted().forEach((model, predicted) ->
        checked.put(model, calibrate(pick(predicted, a), pick(usual.observed(), a), usual.floor())));
    final List<Read> reads = new ArrayList<>();
    reads.addAll(read(TailCut.Reading.USUAL_FLOW, checked, usual, rows(usual, halves, false),
        threads));
    reads.addAll(read(TailCut.Reading.ALL, checked, all, rows(all, halves, false), threads));
    return new Inner(halves, Collections.unmodifiableMap(checked), calibrated(usual),
        List.copyOf(reads));
  }

  /**
   * Every model calibrated on every issue of {@code usual}, both halves together: the factors for
   * every later use.
   *
   * @param usual the usual-flow inner validation issues, the inner models' predictions of them
   */
  public static Map<String, Calibration> calibrated(final Sample usual) {
    final Map<String, Calibration> out = new LinkedHashMap<>();
    usual.predicted().forEach((model, predicted) ->
        out.put(model, calibrate(predicted, usual.observed(), usual.floor())));
    return Collections.unmodifiableMap(out);
  }

  /**
   * The outer test's intervals (item 53 viii): every outer model's predictions with the factors
   * its inner twin was {@code calibrated} with, on every issue of each reading, the bands those
   * of the samples, the outer scope's.
   *
   * @param calibrated per model, the inner calibration on the whole usual-flow inner validation
   *     ({@link Inner#calibrated}); one for every model of the samples
   */
  public static List<Read> outer(final Map<String, Calibration> calibrated, final Sample usual,
      final Sample all, final int threads) {
    final List<Read> reads = new ArrayList<>();
    reads.addAll(read(TailCut.Reading.USUAL_FLOW, calibrated, usual, every(usual), threads));
    reads.addAll(read(TailCut.Reading.ALL, calibrated, all, every(all), threads));
    return List.copyOf(reads);
  }

  /** Per model and level, the figures on the issues {@code rows} of {@code s}. */
  private static List<Read> read(final TailCut.Reading reading,
      final Map<String, Calibration> calibrations, final Sample s, final List<Integer> rows,
      final int threads) {
    final String global = SimilarityScoring.GLOBAL;
    if (!s.predicted().containsKey(global) || !calibrations.keySet()
        .containsAll(s.predicted().keySet())) {
      throw new IllegalArgumentException("a calibration for every model, the global median's "
          + "included, is needed; there is one for " + calibrations.keySet());
    }
    // the bands of the observed duration: an after-the-fact grouping of the checked issues
    final List<List<Integer>> byBand = new ArrayList<>();
    for (int b = 0; b < BANDS; b++) {
      byBand.add(new ArrayList<>());
    }
    rows.forEach(i -> byBand.get(band(s.bandTops(), s.observed()[i])).add(i));
    final List<Read> out = new ArrayList<>();
    for (final Map.Entry<String, double[]> m : s.predicted().entrySet()) {
      final Calibration c = calibrations.get(m.getKey());
      final double[] predicted = m.getValue();
      for (int l = 0; l < LEVELS.size(); l++) {
        final double[] covered = new double[predicted.length];
        final double[] width = new double[predicted.length];
        final double[] globalWidth = new double[predicted.length];
        for (final int i : rows) {
          final double[] in = c.interval(l, predicted[i]);
          covered[i] = in[0] <= s.observed()[i] && s.observed()[i] <= in[1] ? 1 : 0;
          width[i] = in[1] - in[0];
          final double[] g = calibrations.get(global).interval(l, s.predicted().get(global)[i]);
          globalWidth[i] = g[1] - g[0];
        }
        final List<Group> bands = new ArrayList<>();
        for (final List<Integer> b : byBand) {
          bands.add(group(b, s, predicted, covered, width, globalWidth, threads));
        }
        out.add(new Read(reading, m.getKey(), LEVELS.get(l),
            group(rows, s, predicted, covered, width, globalWidth, threads), List.copyOf(bands),
            s.floor() == 0 ? Map.of(
                "zero", group(rows.stream().filter(i -> s.observed()[i] == 0).toList(), s,
                    predicted, covered, width, globalWidth, threads),
                "nonzero", group(rows.stream().filter(i -> s.observed()[i] > 0).toList(), s,
                    predicted, covered, width, globalWidth, threads)) : Map.of()));
      }
    }
    return out;
  }

  private static Group group(final List<Integer> rows, final Sample s, final double[] predicted,
      final double[] covered, final double[] width, final double[] globalWidth,
      final int threads) {
    if (rows.isEmpty()) {
      return new Group(0, null, Double.NaN, Double.NaN, Double.NaN);
    }
    double error = 0;
    for (final int i : rows) {
      error += Math.abs(predicted[i] - s.observed()[i]);
    }
    final double median = median(pick(width, rows));
    return new Group(rows.size(), ProjectBootstrap.share(rows.stream().map(s.projects()::get)
        .toList(), pick(covered, rows), threads), median,
        median(pick(globalWidth, rows)) == 0 ? Double.NaN : median / median(pick(globalWidth, rows)), error / rows.size());
  }

  /**
   * The median: the middle value, or the mean of the two middle ones, as {@link
   * TailCut#quantile} at one half gives it, but an infinite width kept infinite.
   */
  static double median(final double[] values) {
    final double[] sorted = values.clone();
    Arrays.sort(sorted);
    final int middle = sorted.length / 2;
    if (sorted.length % 2 == 1 || sorted[middle - 1] == sorted[middle]) {
      return sorted[middle];
    }
    return sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2;
  }

  /** The issues of {@code s} in half A, or in half B. */
  private static List<Integer> rows(final Sample s, final Halves halves, final boolean inA) {
    final List<Integer> out = new ArrayList<>();
    for (int i = 0; i < s.projects().size(); i++) {
      if (halves.a().contains(s.projects().get(i)) == inA) {
        out.add(i);
      }
    }
    return out;
  }

  private static List<Integer> every(final Sample s) {
    final List<Integer> out = new ArrayList<>();
    for (int i = 0; i < s.observed().length; i++) {
      out.add(i);
    }
    return out;
  }

  private static double[] pick(final double[] values, final List<Integer> rows) {
    final double[] out = new double[rows.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = values[rows.get(k)];
    }
    return out;
  }
}
