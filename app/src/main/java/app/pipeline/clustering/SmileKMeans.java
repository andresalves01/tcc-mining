package app.pipeline.clustering;

import app.issues.Clusterer;
import java.util.concurrent.ForkJoinTask;
import smile.clustering.BBDTree;
import smile.clustering.CentroidClustering;
import smile.clustering.Clustering;
import smile.clustering.KMeans;
import smile.math.MathEx;

/**
 * Smile 4.3.0's K-means: k-means++ starts, then Lloyd iterations sped up by its BBD tree, run
 * {@link #RUNS} times one after another, the run of the lowest distortion kept (the first of
 * equal ones). The distortion is Smile's: the mean squared Euclidean distance from each vector to
 * the final centre of the cluster Smile's last pass put it in, which once a run has converged is
 * its nearest centre but for vectors that pass moved.
 *
 * <p><b>Seed.</b> Smile draws every random number of the k-means++ start from {@code MathEx}'s
 * generator, which is thread-local: each thread has its own, seeded by a fixed list the first time
 * the thread draws, then from a secure random source. {@code MathEx.setSeed} reseeds the calling
 * thread's generator only. So each fit reseeds it with its seed before its first run and makes all
 * ten runs on the calling thread: what a fit draws depends on its seed alone, never on what the
 * thread ran before or on how fits are spread over threads. Smile's parallel loops inside a run
 * only measure distances, each into its own slot, and draw nothing, so the centres come out the
 * same bit for bit. A fit called from a fork-join worker is refused, since such a thread may run
 * another fit's task while it waits, reseeding the generator in the middle of this one.
 *
 * <p><b>Settings.</b> Smile's own defaults for the iterations: at most {@link #MAX_ITERATIONS},
 * stopping once a pass lowers the distortion by no more than {@link #TOLERANCE}.
 */
public final class SmileKMeans implements Clusterer {

  /** How many k-means++ starts a fit tries. */
  public static final int RUNS = 10;

  /** Smile's default cap on the iterations of one run. */
  public static final int MAX_ITERATIONS = 100;

  /** Smile's default: a run stops once a pass lowers the distortion by no more than this. */
  public static final double TOLERANCE = 1e-4;

  @Override
  public Fit fit(final double[][] vectors, final int k, final long seed) {
    if (ForkJoinTask.inForkJoinPool()) {
      throw new IllegalStateException("a K-means fit must run on a thread of its own, not a "
          + "fork-join worker, or its random draws could interleave with another fit's");
    }
    if (k < 2) {
      throw new IllegalArgumentException("Smile's K-means needs at least 2 clusters, not " + k);
    }
    // Smile's k-means++ start loops forever on fewer distinct vectors than k
    if (Clusterer.distinct(vectors) < k) {
      return Fit.notFitted(k);
    }
    final BBDTree tree = new BBDTree(vectors);
    final Clustering.Options options =
        new Clustering.Options(k, MAX_ITERATIONS, TOLERANCE, null);
    MathEx.setSeed(seed);
    CentroidClustering<double[], double[]> best = null;
    for (int run = 0; run < RUNS; run++) {
      final CentroidClustering<double[], double[]> c = KMeans.fit(tree, vectors, options);
      if (best == null || c.distortion() < best.distortion()) {
        best = c;
      }
    }
    final double[][] centres = new double[k][];
    for (int c = 0; c < k; c++) {
      centres[c] = best.center(c).clone();
    }
    return new Fit(k, centres, best.distortion());
  }

  @Override
  public String describe() {
    return String.format("Smile 4.3.0 KMeans: k-means++ start, %d runs kept by lowest distortion, "
        + "at most %d iterations, tolerance %s", RUNS, MAX_ITERATIONS, TOLERANCE);
  }
}
