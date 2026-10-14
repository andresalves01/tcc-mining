package app.study.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Whether the gap between two specifications is bigger than the noise in the rows they share.
 *
 * <p>The table sorts specifications by mean absolute error, and a reader will read a ranking into
 * it. On this population the gap between neighbours is one or two hours out of two hundred, over
 * 1.865 rows whose durations run from minutes to months. A difference that size can be an accident
 * of which units happened to fall on this side of the cut, and printing the order without
 * saying so invites a claim the data may not support.
 *
 * <p>The test is a <b>paired</b> resample, and the pairing is what makes it work: both
 * specifications predicted the same rows, so resampling rows and recomputing both errors on the
 * same draw removes everything the two have in common -- the outliers, the hard projects, the
 * population itself -- and leaves only the difference between them.
 *
 * <p>What it does <b>not</b> do is turn into a p-value or a claim of significance. It answers one
 * question in the study's own terms: across resamples of these rows, how often does the winner
 * change? A specification that wins nine times in ten is ahead; one that wins six times in ten is
 * tied with its neighbour, whatever the table's order says.
 */
public final class Bootstrap {

  /** Fixed so the answer is the same on every run, like every other random choice in this study. */
  public static final long SEED = 20260927L;

  public static final int DRAWS = 2_000;

  /**
   * @param winRate the share of resamples in which {@code a} had the smaller error
   * @param meanGap the average of {@code MAE(a) - MAE(b)} across resamples, in hours
   * @param lowGap the 2,5th percentile of that difference, and {@code highGap} the 97,5th. When the
   *     interval contains zero the two are not distinguishable on these rows
   */
  public record Comparison(String a, String b, double winRate, double meanGap,
      double lowGap, double highGap) {

    /** Neither side wins often enough to call it, on these rows. */
    public boolean indistinguishable() {
      return lowGap <= 0 && highGap >= 0;
    }
  }

  private Bootstrap() {}

  /**
   * @param errorsA absolute error per row for one specification, {@code errorsB} for the other,
   *     both on the same rows in the same order
   */
  public static Comparison compare(final String a, final double[] errorsA,
      final String b, final double[] errorsB) {

    if (errorsA.length != errorsB.length) {
      throw new IllegalArgumentException("both lists must be of the same rows");
    }
    final int n = errorsA.length;
    final Random random = new Random(SEED);
    final double[] gaps = new double[DRAWS];
    int wins = 0;
    for (int draw = 0; draw < DRAWS; draw++) {
      double sumA = 0;
      double sumB = 0;
      for (int i = 0; i < n; i++) {
        final int row = random.nextInt(n);
        sumA += errorsA[row];
        sumB += errorsB[row];
      }
      gaps[draw] = (sumA - sumB) / n;
      if (sumA < sumB) {
        wins++;
      }
    }
    final double[] sorted = gaps.clone();
    java.util.Arrays.sort(sorted);
    double total = 0;
    for (final double gap : gaps) {
      total += gap;
    }
    return new Comparison(a, b, (double) wins / DRAWS, total / DRAWS,
        sorted[(int) (0.025 * (DRAWS - 1))], sorted[(int) (0.975 * (DRAWS - 1))]);
  }

  /** Absolute error per row, which is what the comparison resamples. */
  public static double[] errors(final double[] predicted, final double[] observed) {
    final double[] errors = new double[predicted.length];
    for (int i = 0; i < predicted.length; i++) {
      errors[i] = Math.abs(predicted[i] - observed[i]);
    }
    return errors;
  }

  /** Every pair of a list, in order, so a report can print a triangle instead of a square. */
  public static List<Comparison> allPairs(final List<String> labels,
      final List<double[]> errorsPerLabel) {

    final List<Comparison> comparisons = new ArrayList<>();
    for (int i = 0; i < labels.size(); i++) {
      for (int j = i + 1; j < labels.size(); j++) {
        comparisons.add(compare(labels.get(i), errorsPerLabel.get(i),
            labels.get(j), errorsPerLabel.get(j)));
      }
    }
    return List.copyOf(comparisons);
  }
}
