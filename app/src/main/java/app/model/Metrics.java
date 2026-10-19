package app.model;

import java.util.Arrays;

/** The rank correlation the scores report beside the error. */
public final class Metrics {

  private Metrics() {}

  /** Rank correlation, which answers ordering rather than magnitude. */
  public static double spearman(final double[] a, final double[] b) {
    final double[] ra = ranks(a);
    final double[] rb = ranks(b);
    double meanA = 0;
    double meanB = 0;
    for (int i = 0; i < ra.length; i++) {
      meanA += ra[i];
      meanB += rb[i];
    }
    meanA /= ra.length;
    meanB /= rb.length;
    double cov = 0;
    double va = 0;
    double vb = 0;
    for (int i = 0; i < ra.length; i++) {
      cov += (ra[i] - meanA) * (rb[i] - meanB);
      va += (ra[i] - meanA) * (ra[i] - meanA);
      vb += (rb[i] - meanB) * (rb[i] - meanB);
    }
    return cov / Math.sqrt(va * vb);
  }

  /** Average ranks, so ties do not invent an ordering the data does not carry. */
  private static double[] ranks(final double[] values) {
    final Integer[] order = new Integer[values.length];
    for (int i = 0; i < values.length; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (x, y) -> Double.compare(values[x], values[y]));
    final double[] out = new double[values.length];
    int i = 0;
    while (i < order.length) {
      int j = i;
      while (j + 1 < order.length && values[order[j + 1]] == values[order[i]]) {
        j++;
      }
      final double shared = (i + j) / 2.0 + 1;
      for (int k = i; k <= j; k++) {
        out[order[k]] = shared;
      }
      i = j + 1;
    }
    return out;
  }
}
