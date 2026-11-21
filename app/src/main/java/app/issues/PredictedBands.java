package app.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * The error by band of the predicted business days, a description of frozen predictions (ticket
 * 07 of the review): the bands are the ones of {@link BusinessDays#bandLabels}, cut at the
 * quartiles of the observed duration of the cut fitting rows as the test's report records them,
 * never recomputed on the test, but each issue goes to the band of its PREDICTION. Beside them
 * stand the same bands by the observed duration, the reading the report already gives, the long
 * band (predicted above Q3 against observed above Q3) and the risk band ({@link RiskBand}: the
 * quarter of the highest predictions against observed above Q3).
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

  /**
   * The risk band (ticket 13 of the review, comment 10's "risco estimado"): the issues whose
   * prediction is above the third quartile of the model's own predictions in the reading, the
   * quarter it ranks highest, against the issues observed above the long band's limit. The mark
   * reads the predictions alone, never a label, so it says whether the model ranks the long issues
   * on top even when it never predicts one above the limit.
   *
   * @param threshold the third quartile of the reading's predictions ({@link TailCut#quartiles}),
   *     {@code NaN} for an empty reading
   * @param counts marked against observed long: {@code both} marked and long, {@code
   *     predictedOnly} marked only, and so on, with precision and recall
   * @param baseRate the share of the reading observed long, the precision a mark of the same size
   *     drawn at random would have on average; {@code NaN} for an empty reading
   */
  public record RiskBand(double threshold, LongBand counts, double baseRate) {}

  /**
   * One reading of one model: the bands by prediction, by observation, the long band and the risk
   * band.
   */
  public record Reading(List<Band> byPrediction, List<Band> byObservation, LongBand longBand,
      RiskBand riskBand) {}

  private PredictedBands() {}

  /** The reading of {@code predicted} against {@code observed}, bands cut at the three tops. */
  public static Reading of(final double[] predicted, final double[] observed,
      final double[] tops) {
    final String[] labels = BusinessDays.bandLabels(tops);
    return new Reading(bands(predicted, observed, predicted, tops, labels),
        bands(predicted, observed, observed, tops, labels), longBand(predicted, observed, tops),
        riskBand(predicted, observed, tops));
  }

  /**
   * The usual flow: only the issues observed at or under {@code fence}, the fence the test's
   * report records (the outer training rows' Q3 + 3 IQR); the band limits are the same.
   */
  public static Reading usualFlow(final double[] predicted, final double[] observed,
      final double[] tops, final double fence) {
    final int[] kept = IntStream.range(0, observed.length)
        .filter(i -> observed[i] <= fence).toArray();
    return of(Arrays.stream(kept).mapToDouble(i -> predicted[i]).toArray(),
        Arrays.stream(kept).mapToDouble(i -> observed[i]).toArray(), tops);
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
    return crossed(predicted, observed, tops[2], tops[2]);
  }

  private static RiskBand riskBand(final double[] predicted, final double[] observed,
      final double[] tops) {
    if (predicted.length == 0) {
      return new RiskBand(Double.NaN, crossed(predicted, observed, Double.NaN, tops[2]),
          Double.NaN);
    }
    final double threshold = TailCut.quartiles(predicted)[2];
    final long observedLong = Arrays.stream(observed).filter(o -> o > tops[2]).count();
    return new RiskBand(threshold, crossed(predicted, observed, threshold, tops[2]),
        (double) observedLong / observed.length);
  }

  /** Predicted above {@code markAbove} against observed above {@code longAbove}. */
  private static LongBand crossed(final double[] predicted, final double[] observed,
      final double markAbove, final double longAbove) {
    int both = 0;
    int predictedOnly = 0;
    int observedOnly = 0;
    int neither = 0;
    for (int i = 0; i < predicted.length; i++) {
      final boolean p = predicted[i] > markAbove;
      final boolean o = observed[i] > longAbove;
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

  /**
   * The test stage's line of the error bands' limits, as it prints it (two decimals, {@link
   * BusinessDays#bandLimits}); the reader below is built from this very format, so producer and
   * reader cannot drift. The inner report prints a line of the same shape that ends otherwise.
   */
  public static final String BANDS_LINE = "  error bands: %s; here %s business days, of the "
      + "outer training rows fitted%n";

  /**
   * The test stage's line of the readings, with the usual-flow fence to three decimals: the usual
   * flow, the main reading, first, as every design but the revised one prints it (the temporal
   * test of 02/11/2026 among them).
   */
  public static final String READINGS_LINE = "  readings: %s, %,d issues (fence %.3f bd on the "
      + "outer training set); %s, %,d issues%n";

  /**
   * The same line under the revised temporal design, whose main reading is all issues: all issues
   * first, then the usual flow with its fence.
   */
  public static final String READINGS_LINE_REVISED = "  readings: %s, %,d issues; %s, %,d issues "
      + "(fence %.3f bd on the outer training set)%n";

  /** The readings line the test stage prints under {@code rule}. */
  public static String readingsLine(final SplitRule rule) {
    return READINGS_LINE_REVISED;
  }

  // a decimal in either separator: the producer's printf does not fix the locale
  private static final String NUMBER = "[0-9]+(?:[.,][0-9]+)?";

  private static final Pattern TOPS = pattern(BANDS_LINE, ".*?",
      "(" + NUMBER + "), (" + NUMBER + ") and (" + NUMBER + ")");

  // a count in any locale's grouping
  private static final String COUNT = "[0-9][0-9.,\\s\u00a0\u202f']*";

  // the usual flow under any role: "usual flow (main reading)" before the review, "usual flow
  // (complementary reading)" in the revised design
  private static final String USUAL = Pattern.quote("usual flow (") + ".*?";

  // either line, the usual flow first (group 1) or second (group 2)
  private static final Pattern FENCE = Pattern.compile("(?:"
      + pattern(READINGS_LINE, USUAL, COUNT, "(" + NUMBER + ")", ".*?", COUNT).pattern() + ")|(?:"
      + pattern(READINGS_LINE_REVISED, ".*?", COUNT, USUAL, COUNT, "(" + NUMBER + ")").pattern()
      + ")");

  /**
   * The regex of a printf format: its literals quoted, each specifier (but {@code %n}) replaced
   * by the next of {@code groups}.
   */
  private static Pattern pattern(final String format, final String... groups) {
    final Matcher m = Pattern.compile("%[^a-zA-Z%]*[a-zA-Z]").matcher(format);
    final StringBuilder regex = new StringBuilder();
    int from = 0;
    int next = 0;
    while (m.find()) {
      regex.append(Pattern.quote(format.substring(from, m.start())));
      regex.append(m.group().endsWith("n") ? "" : groups[next++]);
      from = m.end();
    }
    regex.append(Pattern.quote(format.substring(from)));
    return Pattern.compile(regex.toString());
  }

  /** The one match of {@code pattern} in the log; none or several are refused. */
  private static Matcher only(final List<String> log, final Pattern pattern, final String what) {
    Matcher found = null;
    for (final String line : log) {
      final Matcher m = pattern.matcher(line);
      if (m.find()) {
        if (found != null) {
          throw new IllegalArgumentException("more than one " + what + " line in the log");
        }
        found = m;
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("no " + what + " line in the log");
    }
    return found;
  }

  /** A number written with a point or a comma as the decimal separator. */
  public static double number(final String text) {
    return Double.parseDouble(text.trim().replace(',', '.'));
  }

  /**
   * The error bands' limits as the test stage's log writes them ({@link BusinessDays#days}:
   * whole, or rounded to two decimals), so they are exact only where the quartiles are. Only the
   * test's own line counts (the inner report's has another ending); two such lines are refused.
   */
  public static double[] bandTops(final List<String> log) {
    final Matcher m = only(log, TOPS, "test stage 'error bands ... business days, of the outer "
        + "training rows fitted'");
    return new double[] {number(m.group(1)), number(m.group(2)), number(m.group(3))};
  }

  /**
   * The usual-flow fence, in business days, as the test stage's log writes it (three decimals),
   * in either readings line: the usual flow first, as the main reading, or second, as the revised
   * design's complementary one.
   */
  public static double usualFence(final List<String> log) {
    final Matcher m = only(log, FENCE, "test stage 'readings: ... usual flow (...) ... (fence X "
        + "bd on the outer training set)'");
    return number(m.group(1) != null ? m.group(1) : m.group(2));
  }

  /** Three finite, non-decreasing band tops and a finite, positive fence; else refused. */
  public static void validate(final double[] tops, final double fence) {
    if (tops.length != 3) {
      throw new IllegalArgumentException("the band tops are " + tops.length + " numbers, not "
          + "three");
    }
    for (int i = 0; i < 3; i++) {
      if (!Double.isFinite(tops[i]) || i > 0 && tops[i] < tops[i - 1]) {
        throw new IllegalArgumentException("the band tops must be finite and non-decreasing: "
            + Arrays.toString(tops));
      }
    }
    if (!Double.isFinite(fence) || fence <= 0) {
      throw new IllegalArgumentException("the usual-flow fence must be finite and positive: "
          + fence);
    }
  }
}
