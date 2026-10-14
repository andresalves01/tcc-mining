package app.study.model;

import java.util.ArrayList;
import app.model.Metrics;
import java.util.Arrays;
import java.util.List;

/**
 * Where the error lives, because a mean absolute error on this target hides more than it shows.
 *
 * <p>The durations run from minutes to months and the mean is several times the median, so one
 * number averages a model that is nearly right about most units with one that is enormously
 * wrong about a few. Reported alone it describes neither. Two cuts fix that without inventing
 * anything: the <b>distribution of the error itself</b>, and the error <b>within bands of the true
 * duration</b>.
 *
 * <p>The bands are cut on the truth and never on the prediction. Cutting on the prediction would let
 * the model choose its own easy cases, and the comparison against the references would stop being
 * between equals. Every model and every reference is scored on the same rows in the same band.
 *
 * <p>Trimming is reported beside the full figure, never instead of it. A trimmed error answers "how
 * wrong is this about ordinary units"; the full one answers "how wrong is it overall". The
 * second is the honest headline and the first is what makes it legible.
 */
public final class Breakdown {

  /** The bands, as quantiles of the true duration. Fixed here rather than chosen per run. */
  public static final double[] CUTS = {0.25, 0.50, 0.75, 0.90};

  /**
   * @param label which band, in words
   * @param n how many rows fell in it
   * @param medianTruth the middle true duration inside the band, so the error has a scale
   * @param mae the model's mean absolute error on those rows
   * @param medianError the middle absolute error, which the mean cannot show
   */
  public record Band(String label, int n, double medianTruth, double mae, double medianError) {}

  /**
   * @param p50 the median absolute error: what the model misses by on an ordinary row
   * @param trimmedMae the mean absolute error with the slowest tenth of units left out
   */
  public record Errors(double mae, double p50, double p75, double p90, double p99,
      double trimmedMae, int trimmedN) {}

  private Breakdown() {}

  /** The error's own distribution, which the mean of it cannot describe. */
  public static Errors errors(final double[] predicted, final double[] observed) {
    final double[] absolute = new double[predicted.length];
    for (int i = 0; i < predicted.length; i++) {
      absolute[i] = Math.abs(predicted[i] - observed[i]);
    }
    final double[] sorted = absolute.clone();
    Arrays.sort(sorted);

    // Trimmed by the TRUTH, not by the error: cutting the rows with the largest error would be the
    // model choosing where to be evaluated. The slowest tenth is cut, which is a property of the rows.
    final double[] truth = observed.clone();
    Arrays.sort(truth);
    final double limit = quantile(truth, 0.90);
    double trimmedSum = 0;
    int trimmedN = 0;
    for (int i = 0; i < observed.length; i++) {
      if (observed[i] <= limit) {
        trimmedSum += absolute[i];
        trimmedN++;
      }
    }
    return new Errors(mean(absolute), quantile(sorted, 0.50), quantile(sorted, 0.75),
        quantile(sorted, 0.90), quantile(sorted, 0.99),
        trimmedN == 0 ? Double.NaN : trimmedSum / trimmedN, trimmedN);
  }

  /** The same predictions, split by how long the unit actually took. */
  public static List<Band> byTruth(final double[] predicted, final double[] observed) {
    final double[] truth = observed.clone();
    Arrays.sort(truth);
    final double[] edges = new double[CUTS.length];
    for (int i = 0; i < CUTS.length; i++) {
      edges[i] = quantile(truth, CUTS[i]);
    }

    final String[] labels = {"fastest 25%", "25% a 50%", "50% a 75%", "75% a 90%",
        "slowest 10%"};
    final List<Band> bands = new ArrayList<>(labels.length);
    for (int b = 0; b < labels.length; b++) {
      final double low = b == 0 ? Double.NEGATIVE_INFINITY : edges[b - 1];
      final double high = b == labels.length - 1 ? Double.POSITIVE_INFINITY : edges[b];
      final List<Double> errors = new ArrayList<>();
      final List<Double> truths = new ArrayList<>();
      for (int i = 0; i < observed.length; i++) {
        if (observed[i] > low && observed[i] <= high) {
          errors.add(Math.abs(predicted[i] - observed[i]));
          truths.add(observed[i]);
        }
      }
      bands.add(new Band(labels[b], errors.size(), median(truths), mean(errors), median(errors)));
    }
    return List.copyOf(bands);
  }

  /**
   * The same measures, restricted to the body of the distribution.
   *
   * <p>Cut on the true duration between two quantiles, which drops the units that finished
   * almost instantly and the long tail that dominates the mean. It answers "how does this behave on
   * an ordinary unit", and it is only honest if the <b>references are cut on the same
   * rows</b> -- a model measured on an easier subset against a reference measured on everything
   * would be comparing two different questions.
   *
   * <p>Ordering inside a band is a harder problem than ordering overall, because the durations
   * inside it are closer together. A lower figure here is not the model getting worse.
   */
  public record Body(int n, double lowHours, double highHours, double medianTruth,
      double mae, double medianError, double spearman, double pairwise) {}

  public static Body body(final double[] predicted, final double[] observed,
      final double lowQuantile, final double highQuantile) {

    final double[] truth = observed.clone();
    Arrays.sort(truth);
    final double low = quantile(truth, lowQuantile);
    final double high = quantile(truth, highQuantile);

    final List<Double> keptPredicted = new ArrayList<>();
    final List<Double> keptObserved = new ArrayList<>();
    final List<Double> errors = new ArrayList<>();
    for (int i = 0; i < observed.length; i++) {
      if (observed[i] > low && observed[i] <= high) {
        keptPredicted.add(predicted[i]);
        keptObserved.add(observed[i]);
        errors.add(Math.abs(predicted[i] - observed[i]));
      }
    }
    final double[] p = keptPredicted.stream().mapToDouble(Double::doubleValue).toArray();
    final double[] o = keptObserved.stream().mapToDouble(Double::doubleValue).toArray();
    return new Body(p.length, low, high, median(keptObserved), mean(errors), median(errors),
        p.length < 2 ? Double.NaN : Metrics.spearman(p, o),
        p.length < 2 ? Double.NaN : Score.pairwiseAccuracy(p, o));
  }

  private static double quantile(final double[] sorted, final double q) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    final double position = (sorted.length - 1) * q;
    final int low = (int) Math.floor(position);
    final int high = Math.min(low + 1, sorted.length - 1);
    return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
  }

  private static double mean(final double[] values) {
    double sum = 0;
    for (final double value : values) {
      sum += value;
    }
    return values.length == 0 ? Double.NaN : sum / values.length;
  }

  private static double mean(final List<Double> values) {
    double sum = 0;
    for (final double value : values) {
      sum += value;
    }
    return values.isEmpty() ? Double.NaN : sum / values.size();
  }

  private static double median(final List<Double> values) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    final List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compare);
    final int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1 ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
  }
}
