package app.issues;

import app.issues.model.ProjectBootstrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Descriptive uncertainty of frozen predictions. Never fits, selects or changes an issue's
 * population. Relative error uses the estimate as denominator, not PRED's observed denominator.
 */
public final class EstimationUncertainty {

  public static final List<String> METRICS = List.of("mae_bd", "mean_signed_relative_error",
      "sd_signed_relative_error", "within_25pct", "within_50pct", "within_100pct");
  public static final List<Double> PERCENTILES = List.of(0.25, 0.5, 0.75, 0.9, 0.95);

  public static final String RULE = "signed relative error = (observed - estimate) / estimate; "
      + "positive means underestimation; SD is the sample SD of these pooled signed relative "
      + "errors; within +/-25%, +/-50% and +/-100% use inclusive limits and the estimate as "
      + "denominator, not conventional PRED; zero estimates remain in the population and MAE, "
      + "but their relative errors are undefined and counted separately; small positive estimates "
      + "are unchanged; all measures are issue-weighted";

  public static final String INTERVAL_RULE = "95% confidence intervals resample whole projects "
      + "within each reported subgroup, with " + ProjectBootstrap.DRAWS + " draws and seed "
      + ProjectBootstrap.SEED + "; undefined draws are omitted from interval percentiles and "
      + "their valid count is reported; fewer than two projects gives no confidence interval; "
      + "relative intervals require positive estimates from at least two projects; "
      + "sample SD requires at least two positive estimates; empty groups remain explicit";

  public static final String PERCENTILE_RULE = "percentiles of each model's estimates on the "
      + "scored issues, using type-7 interpolation; existing pooled inner calibration supplies "
      + "80%/95% prediction intervals around those estimates; these are illustrations, not "
      + "confidence intervals for percentiles or evidence of percentile-specific coverage; "
      + "intervals target usual-flow coverage even when illustrated on the complementary reading; "
      + "outer refits reuse inner factors approximately";

  /**
   * The percentiles' rule under the revised temporal design: the intervals are calibrated on
   * every inner validation issue ({@link PredictionIntervals#calibrationReading}), all issues
   * being its main reading; around the outer models' estimates they are the approximation shown
   * for comparison, not the official same-model intervals ({@link PredictionIntervals#SAME_MODEL}),
   * which the same-model calibration step reports.
   */
  public static final String REVISED_PERCENTILE_RULE = "percentiles of each model's estimates "
      + "on the scored issues, using type-7 interpolation; the pooled inner calibration on every "
      + "inner validation issue, the tail above the usual flow's k = 3 fence included, supplies "
      + "80%/95% prediction intervals around those estimates; these are illustrations, not "
      + "confidence intervals for percentiles or evidence of percentile-specific coverage; "
      + "intervals target the coverage on all issues, the main reading; around the outer "
      + "refits' estimates they illustrate the approximation, the inner factors reused on the "
      + "outer models, not the official same-model intervals, which the same-model calibration "
      + "step reports";

  /**
   * The percentiles' rule under {@code rule}: {@link #REVISED_PERCENTILE_RULE} under the revised
   * temporal design, {@link #PERCENTILE_RULE} under every other rule.
   */
  public static String percentileRule(final SplitRule rule) {
    return rule == SplitRule.REVISED ? REVISED_PERCENTILE_RULE : PERCENTILE_RULE;
  }

  public record Estimate(double value, double low, double high, int validDraws) {}

  public record Summary(String reading, String model, String grouping, String group,
      int n, int positiveEstimates, int zeroEstimates, int projects, int positiveEstimateProjects,
      List<Estimate> metrics) {}

  public record Percentile(String reading, String model, int n, double percentile,
      double estimate, double low80, double high80, double low95, double high95) {}

  public record Reading(List<Summary> summaries, List<Percentile> percentiles) {}

  /** Inputs already restricted to one scoring scope and reading. */
  public record Sample(String reading, List<String> projects, boolean[] descriptionAvailable,
      double[] observed, Map<String, double[]> predictions, double[] bandTops) {

    public static Sample of(final SimilarityScoring.Report r,
        final Map<String, Boolean> descriptionAvailable) {
      final boolean[] description = new boolean[r.scoredRows()];
      for (int i = 0; i < description.length; i++) {
        final String id = r.scoredUnits().get(i).issueId();
        if (!descriptionAvailable.containsKey(id)) {
          throw new IllegalArgumentException("no start-description availability for " + id);
        }
        description[i] = descriptionAvailable.get(id);
      }
      final Map<String, double[]> predictions = new java.util.LinkedHashMap<>();
      r.models().forEach(m -> predictions.put(m.name(), m.predicted()));
      return new Sample(r.reading().name().toLowerCase(java.util.Locale.ROOT),
          r.scoredUnits().stream().map(IssueUnit::project).toList(), description, r.observed(),
          predictions, r.bandTops());
    }
  }

  private EstimationUncertainty() {}

  /** Snapshot availability, not language detection or retrospective activity. */
  public static Map<String, Boolean> descriptions(final List<RowCache.Entry> entries) {
    final Map<String, Boolean> out = new HashMap<>();
    for (final RowCache.Entry e : entries) {
      final Double words = e.row().values().get("A01_desc_length");
      out.put(e.unit().issueId(), words != null && words > 0);
    }
    return out;
  }

  public static Reading read(final Sample s,
      final Map<String, PredictionIntervals.Calibration> calibration, final int threads) {
    final int n = s.observed().length;
    if (s.projects().size() != n || s.descriptionAvailable().length != n
        || s.bandTops().length != 3) {
      throw new IllegalArgumentException("uncertainty inputs must describe the same issues");
    }
    final List<Summary> summaries = new ArrayList<>();
    final List<Percentile> percentiles = new ArrayList<>();
    final String[] bands = BusinessDays.bandLabels(s.bandTops());
    for (final var model : s.predictions().entrySet()) {
      final double[] p = model.getValue();
      if (p.length != n) {
        throw new IllegalArgumentException("one estimate per observed duration");
      }
      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(p[i]) || p[i] < 0 || !Double.isFinite(s.observed()[i])
            || s.observed()[i] < 0) {
          throw new IllegalArgumentException("finite nonnegative estimates and nonnegative durations "
              + "required");
        }
      }
      final List<Integer> all = IntStream.range(0, n).boxed().toList();
      summaries.add(summary(s, model.getKey(), p, "overall", "all", all, threads));
      for (final boolean zero : List.of(true, false)) {
        summaries.add(summary(s, model.getKey(), p, "zero_duration", zero ? "zero" : "nonzero",
            all.stream().filter(i -> (s.observed()[i] == 0) == zero).toList(), threads));
      }
      for (int b = 0; b < bands.length; b++) {
        final int band = b;
        final List<Integer> indices = all.stream().filter(i ->
            (band == 0 || s.observed()[i] > s.bandTops()[band - 1])
                && (band == 3 || s.observed()[i] <= s.bandTops()[band])).toList();
        summaries.add(summary(s, model.getKey(), p, "observed_duration", bands[b], indices,
            threads));
      }
      for (final boolean available : List.of(true, false)) {
        summaries.add(summary(s, model.getKey(), p, "start_description",
            available ? "available" : "unavailable_or_blank",
            all.stream().filter(i -> s.descriptionAvailable()[i] == available).toList(), threads));
      }
      final PredictionIntervals.Calibration c = calibration.get(model.getKey());
      if (c == null) {
        throw new IllegalArgumentException("no inner calibration for " + model.getKey());
      }
      final double[] sorted = p.clone();
      Arrays.sort(sorted);
      for (final double q : PERCENTILES) {
        final double estimate = n == 0 ? Double.NaN : TailCut.quantile(sorted, q);
        final double[] pi80 = c.interval(0, estimate);
        final double[] pi95 = c.interval(1, estimate);
        percentiles.add(new Percentile(s.reading(), model.getKey(), n, q, estimate,
            pi80[0], pi80[1], pi95[0], pi95[1]));
      }
    }
    return new Reading(List.copyOf(summaries), List.copyOf(percentiles));
  }

  private static Summary summary(final Sample s, final String model, final double[] predictions,
      final String grouping, final String group, final List<Integer> indices, final int threads) {
    final double[] p = new double[indices.size()];
    final double[] o = new double[indices.size()];
    final List<String> projects = new ArrayList<>();
    int positive = 0;
    for (int k = 0; k < indices.size(); k++) {
      final int i = indices.get(k);
      p[k] = predictions[i];
      o[k] = s.observed()[i];
      projects.add(s.projects().get(i));
      if (p[k] > 0) {
        positive++;
      }
    }
    final double[] point = statistics(p, o, IntStream.range(0, p.length).boxed().toList());
    final int positiveProjects = (int) IntStream.range(0, p.length).filter(i -> p[i] > 0)
        .mapToObj(projects::get).distinct().count();
    final List<ProjectBootstrap.Bounds> bounds = ProjectBootstrap.finiteIntervals(projects,
        METRICS.size(), sample -> statistics(p, o, sample), threads);
    final List<Estimate> metrics = new ArrayList<>();
    for (int k = 0; k < METRICS.size(); k++) {
      final ProjectBootstrap.Bounds b = bounds.get(k);
      metrics.add(Double.isNaN(point[k]) || k > 0 && positiveProjects < 2
          ? new Estimate(point[k], Double.NaN, Double.NaN, 0)
          : new Estimate(point[k], b.low(), b.high(), b.validDraws()));
    }
    return new Summary(s.reading(), model, grouping, group, p.length, positive,
        p.length - positive, (int) projects.stream().distinct().count(), positiveProjects,
        List.copyOf(metrics));
  }

  /** Welford's sample variance; zero estimates contribute to MAE alone. */
  private static double[] statistics(final double[] p, final double[] o,
      final List<Integer> sample) {
    double absolute = 0;
    double mean = 0;
    double m2 = 0;
    int n = 0;
    final int[] within = new int[3];
    for (final int i : sample) {
      absolute += Math.abs(o[i] - p[i]);
      if (p[i] == 0) {
        continue;
      }
      final double e = (o[i] - p[i]) / p[i];
      if (!Double.isFinite(e)) {
        throw new IllegalArgumentException("relative error overflows for a positive estimate");
      }
      n++;
      final double delta = e - mean;
      mean += delta / n;
      m2 += delta * (e - mean);
      if (Math.abs(e) <= 0.25) {
        within[0]++;
      }
      if (Math.abs(e) <= 0.5) {
        within[1]++;
      }
      if (Math.abs(e) <= 1.0) {
        within[2]++;
      }
    }
    return new double[] {sample.isEmpty() ? Double.NaN : absolute / sample.size(),
        n == 0 ? Double.NaN : mean,
        n < 2 ? Double.NaN : Math.sqrt(Math.max(0, m2 / (n - 1))),
        n == 0 ? Double.NaN : (double) within[0] / n,
        n == 0 ? Double.NaN : (double) within[1] / n,
        n == 0 ? Double.NaN : (double) within[2] / n};
  }
}
