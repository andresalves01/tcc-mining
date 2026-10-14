package app.issues;

import java.util.Arrays;

/**
 * What the cluster similarity method reads of a history's vectors: k centres fitted on them, and
 * the nearest of those centres to any vector by Euclidean distance.
 *
 * <p>A fit with fewer {@link #distinct} vectors than k is "not fitted": it has no centres, and the
 * implementation neither throws nor shrinks k nor turns to another algorithm. The same vectors, k
 * and seed give the same centres, bit for bit, and {@link #fit} may be called from several
 * threads at once, each call on one thread from start to end. The production implementation runs
 * Smile's K-means; another can replace it without touching the stage that reads it.
 */
public interface Clusterer {

  /** The cluster a vector is assigned to and its Euclidean distance to that cluster's centre. */
  record Assignment(int cluster, double distance) {}

  /**
   * One fit. The arrays are the fit's own, read and never changed.
   *
   * @param k the number of clusters asked for, kept when not fitted
   * @param centres the k centres, none when not fitted
   * @param distortion the mean squared Euclidean distance from each vector to its centre, as the
   *     implementation measures it, NaN when not fitted
   */
  record Fit(int k, double[][] centres, double distortion) {

    /** The fit of a history with fewer distinct vectors than {@code k}. */
    public static Fit notFitted(final int k) {
      return new Fit(k, new double[0][], Double.NaN);
    }

    public boolean fitted() {
      return centres.length > 0;
    }

    /**
     * The nearest centre to {@code v} by Euclidean distance; on equal distances the lowest
     * index. The squared differences are summed in component order, so a rerun gives the same
     * bits.
     *
     * @throws IllegalStateException when not fitted
     */
    public Assignment assign(final double[] v) {
      if (!fitted()) {
        throw new IllegalStateException("not fitted: fewer than " + k + " distinct vectors");
      }
      int best = 0;
      double nearest = Double.POSITIVE_INFINITY;
      for (int c = 0; c < centres.length; c++) {
        final double[] centre = centres[c];
        double sum = 0;
        for (int d = 0; d < v.length; d++) {
          final double diff = v[d] - centre[d];
          sum += diff * diff;
        }
        if (sum < nearest) {
          nearest = sum;
          best = c;
        }
      }
      return new Assignment(best, Math.sqrt(nearest));
    }
  }

  /** {@code k} clusters of {@code vectors}, from {@code seed}; not fitted when too few. */
  Fit fit(double[][] vectors, int k, long seed);

  /** The algorithm and its settings, for the run record. */
  String describe();

  /**
   * How many different vectors there are: two vectors are the same when every component is
   * equal as a number, so a negative zero is a zero. Two vectors read from floats, as the
   * embeddings are, that differ are at a positive Euclidean distance, the only way a k-means++
   * start can tell them apart.
   */
  static int distinct(final double[][] vectors) {
    final double[][] sorted = new double[vectors.length][];
    for (int i = 0; i < vectors.length; i++) {
      sorted[i] = new double[vectors[i].length];
      for (int d = 0; d < sorted[i].length; d++) {
        sorted[i][d] = vectors[i][d] + 0.0; // -0.0 + 0.0 is 0.0
      }
    }
    Arrays.sort(sorted, Arrays::compare);
    int out = 0;
    for (int i = 0; i < sorted.length; i++) {
      if (i == 0 || !Arrays.equals(sorted[i], sorted[i - 1])) {
        out++;
      }
    }
    return out;
  }
}
