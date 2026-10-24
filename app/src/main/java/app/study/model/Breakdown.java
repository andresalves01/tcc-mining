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

  /**
   * The same predictions, split by fixed bands of the true duration: band {@code b} holds the
   * rows above the previous top and at or under {@code tops[b]}, the first open below and the
   * last, the one beyond the final top, open above. The bands only group the rows; every error is
   * the whole one.
   *
   * @param tops the upper end of every band but the last, ascending
   * @param labels one per band, one more than the tops
   */
  public static List<Band> byEdges(final double[] predicted, final double[] observed,
      final double[] tops, final String[] labels) {
    if (labels.length != tops.length + 1) {
      throw new IllegalArgumentException(labels.length + " labels for " + (tops.length + 1)
          + " bands");
    }
    final List<Band> bands = new ArrayList<>(labels.length);
    for (int b = 0; b < labels.length; b++) {
      final double low = b == 0 ? Double.NEGATIVE_INFINITY : tops[b - 1];
      final double high = b == labels.length - 1 ? Double.POSITIVE_INFINITY : tops[b];
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
