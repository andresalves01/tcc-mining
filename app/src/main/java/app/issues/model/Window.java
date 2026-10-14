package app.issues.model;

import app.pullrequests.model.Score;
import java.util.Arrays;

/**
 * How a model does on the rows whose true duration falls inside a fixed range of hours.
 *
 * <p>Exists because of André's instruction of 27/09/2026: the thesis pipeline filtered issues to
 * between one hour and one year, and this study does not filter at all. The range is read instead,
 * from the results: which percentiles of the true durations the two limits fall on, and what the
 * error and the ordering look like inside it. That keeps every row in the fit and still answers
 * "how does the model do on the issues the old filter would have kept".
 *
 * @param lowPercentile the share of scored rows shorter than the lower limit
 * @param highPercentile the share of scored rows shorter than or equal to the upper limit
 */
public record Window(double lowHours, double highHours, int n, double lowPercentile,
    double highPercentile, double mae, double medianError, double pairwise) {

  public static final double ONE_HOUR = 1.0;
  public static final double ONE_YEAR = 365.0 * 24.0;

  public static Window of(final double[] predicted, final double[] observed, final double low,
      final double high) {
    int below = 0;
    int upTo = 0;
    int n = 0;
    for (final double o : observed) {
      if (o < low) {
        below++;
      }
      if (o <= high) {
        upTo++;
      }
      if (o >= low && o <= high) {
        n++;
      }
    }
    final double[] p = new double[n];
    final double[] o = new double[n];
    final double[] errors = new double[n];
    int k = 0;
    for (int i = 0; i < observed.length; i++) {
      if (observed[i] >= low && observed[i] <= high) {
        p[k] = predicted[i];
        o[k] = observed[i];
        errors[k] = Math.abs(predicted[i] - observed[i]);
        k++;
      }
    }
    double sum = 0;
    for (final double e : errors) {
      sum += e;
    }
    final double[] sorted = errors.clone();
    Arrays.sort(sorted);
    return new Window(low, high, n, 100.0 * below / observed.length,
        100.0 * upTo / observed.length, n == 0 ? Double.NaN : sum / n,
        n == 0 ? Double.NaN : sorted[n / 2], Score.pairwiseAccuracy(p, o));
  }
}
