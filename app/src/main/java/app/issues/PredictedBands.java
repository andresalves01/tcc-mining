package app.issues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The error by band of the predicted business days, a description of frozen predictions (ticket
 * 07 of the review): the bands are the ones of {@link BusinessDays#bandLabels}, cut at the
 * quartiles of the observed duration of the cut fitting rows as the test's report records them,
 * never recomputed on the test, but each issue goes to the band of its PREDICTION. Beside them
 * stand the same bands by the observed duration, the reading the report already gives.
 *
 * <p>{@code Breakdown.byEdges} is not reused here: it groups by its second vector (the observed
 * one), so handing it the predictions to group by would also swap what the error and the median
 * truth are taken from. Here one vector chooses the band and the error is always
 * {@code observed - predicted}, whole and unclipped.
 */
public final class PredictedBands {

  /**
   * One band's figures; {@code NaN} when the band is empty ({@code n = 0}).
   *
   * @param mae the mean absolute error
   * @param medianError the median absolute error
   * @param bias the mean of {@code observed - predicted}: positive means underestimation, the
   *     sign the repository's estimation uncertainty already uses
   */
  public record Band(String label, int n, double mae, double medianError, double bias) {}

  /**
   * The "long" band, above Q3, predicted against observed.
   *
   * @param both predicted long and observed long
   * @param predictedOnly predicted long, observed not
   * @param observedOnly observed long, predicted not
   * @param neither neither
   * @param precision {@code both / (both + predictedOnly)}, {@code NaN} when nothing is predicted
   *     long
   * @param recall {@code both / (both + observedOnly)}, {@code NaN} when nothing is observed long
   */
  public record LongBand(int both, int predictedOnly, int observedOnly, int neither,
      double precision, double recall) {}

  /** One reading of one model: the bands by prediction, by observation, and the long band. */
  public record Reading(List<Band> byPrediction, List<Band> byObservation, LongBand longBand) {}

  private PredictedBands() {}

  /** The reading of {@code predicted} against {@code observed}, bands cut at the three tops. */
  public static Reading of(final double[] predicted, final double[] observed,
      final double[] tops) {
    final String[] labels = BusinessDays.bandLabels(tops);
    return new Reading(bands(predicted, observed, predicted, tops, labels),
        bands(predicted, observed, observed, tops, labels), longBand(predicted, observed, tops));
  }

  /**
   * The usual flow: only the issues observed at or under {@code fence}, the fence the test's
   * report records (the outer training rows' Q3 + 3 IQR); the band limits are the same.
   */
  public static Reading usualFlow(final double[] predicted, final double[] observed,
      final double[] tops, final double fence) {
    final int[] kept = java.util.stream.IntStream.range(0, observed.length)
        .filter(i -> observed[i] <= fence).toArray();
    return of(java.util.Arrays.stream(kept).mapToDouble(i -> predicted[i]).toArray(),
        java.util.Arrays.stream(kept).mapToDouble(i -> observed[i]).toArray(), tops);
  }

  /** The bands of the rows grouped by {@code by}, half-open: above the previous top, up to this. */
  private static List<Band> bands(final double[] predicted, final double[] observed,
      final double[] by, final double[] tops, final String[] labels) {
    final List<Band> out = new ArrayList<>();
    for (int b = 0; b < labels.length; b++) {
      final double low = b == 0 ? Double.NEGATIVE_INFINITY : tops[b - 1];
      final double high = b == tops.length ? Double.POSITIVE_INFINITY : tops[b];
      final List<Double> absolute = new ArrayList<>();
      double signed = 0;
      for (int i = 0; i < by.length; i++) {
        if (by[i] > low && by[i] <= high) {
          absolute.add(Math.abs(observed[i] - predicted[i]));
          signed += observed[i] - predicted[i];
        }
      }
      final int n = absolute.size();
      out.add(new Band(labels[b], n, n == 0 ? Double.NaN : absolute.stream()
          .mapToDouble(Double::doubleValue).sum() / n, median(absolute),
          n == 0 ? Double.NaN : signed / n));
    }
    return List.copyOf(out);
  }

  private static LongBand longBand(final double[] predicted, final double[] observed,
      final double[] tops) {
    int both = 0;
    int predictedOnly = 0;
    int observedOnly = 0;
    int neither = 0;
    for (int i = 0; i < predicted.length; i++) {
      final boolean p = predicted[i] > tops[2];
      final boolean o = observed[i] > tops[2];
      if (p && o) {
        both++;
      } else if (p) {
        predictedOnly++;
      } else if (o) {
        observedOnly++;
      } else {
        neither++;
      }
    }
    return new LongBand(both, predictedOnly, observedOnly, neither,
        ratio(both, both + predictedOnly), ratio(both, both + observedOnly));
  }

  private static double ratio(final int part, final int whole) {
    return whole == 0 ? Double.NaN : (double) part / whole;
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

  /** The frozen test predictions: the observed days and each wanted model's estimates. */
  public record Predictions(double[] observed, Map<String, double[]> predicted) {}

  /** The column of the observed business days in the predictions' TSV. */
  public static final String OBSERVED = "observed_business_days";

  /**
   * The predictions TSV's {@code models} columns and the observed one, by name; a missing column
   * or a cell that is not a number is refused.
   */
  public static Predictions parse(final List<String> lines, final List<String> models) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("the predictions file is empty");
    }
    final List<String> header = List.of(lines.get(0).split("\t", -1));
    final List<String> wanted = new ArrayList<>(List.of(OBSERVED));
    wanted.addAll(models);
    final Map<String, Integer> at = new LinkedHashMap<>();
    for (final String name : wanted) {
      final int i = header.indexOf(name);
      if (i < 0) {
        throw new IllegalArgumentException("no column " + name + " in the predictions file");
      }
      at.put(name, i);
    }
    final int rows = lines.size() - 1;
    final Map<String, double[]> values = new LinkedHashMap<>();
    wanted.forEach(name -> values.put(name, new double[rows]));
    for (int r = 0; r < rows; r++) {
      final String[] cells = lines.get(r + 1).split("\t", -1);
      for (final String name : wanted) {
        try {
          values.get(name)[r] = Double.parseDouble(cells[at.get(name)]);
        } catch (final RuntimeException e) {
          throw new IllegalArgumentException("row " + (r + 1) + ", column " + name
              + " is not a number");
        }
      }
    }
    final Map<String, double[]> predicted = new LinkedHashMap<>();
    models.forEach(m -> predicted.put(m, values.get(m)));
    return new Predictions(values.get(OBSERVED), predicted);
  }

  private static final Pattern TOPS = Pattern.compile(
      "here ([0-9.]+), ([0-9.]+) and ([0-9.]+) business days");

  private static final Pattern FENCE = Pattern.compile(
      "usual flow \\(main reading\\).*?\\(fence ([0-9.]+) bd on the outer training set\\)");

  /**
   * The error bands' limits as the test's log writes them ({@link BusinessDays#days}: whole, or
   * rounded to two decimals), so they are exact only where the quartiles are.
   */
  public static double[] bandTops(final List<String> log) {
    for (final String line : log) {
      final Matcher m = TOPS.matcher(line);
      if (line.contains("error bands") && m.find()) {
        return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
            Double.parseDouble(m.group(3))};
      }
    }
    throw new IllegalArgumentException("no 'error bands ... here a, b and c business days' line");
  }

  /** The usual-flow fence, in business days, as the test's log writes it. */
  public static double usualFence(final List<String> log) {
    for (final String line : log) {
      final Matcher m = FENCE.matcher(line);
      if (m.find()) {
        return Double.parseDouble(m.group(1));
      }
    }
    throw new IllegalArgumentException("no 'usual flow (main reading) ... (fence X bd on the "
        + "outer training set)' line");
  }
}
