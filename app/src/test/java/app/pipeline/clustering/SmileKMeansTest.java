package app.pipeline.clustering;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Clusterer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Smile's K-means behind the clustering interface. */
class SmileKMeansTest {

  private final Clusterer clusterer = new SmileKMeans();

  /** {@code n} random unit vectors of {@code dimension} components. */
  private static double[][] vectors(final int n, final int dimension, final long seed) {
    final Random rng = new Random(seed);
    final double[][] out = new double[n][dimension];
    for (final double[] v : out) {
      double norm = 0;
      for (int d = 0; d < dimension; d++) {
        v[d] = rng.nextGaussian();
        norm += v[d] * v[d];
      }
      for (int d = 0; d < dimension; d++) {
        v[d] /= Math.sqrt(norm);
      }
    }
    return out;
  }

  private static int[] assigned(final Clusterer.Fit fit, final double[][] data) {
    final int[] out = new int[data.length];
    for (int i = 0; i < data.length; i++) {
      out[i] = fit.assign(data[i]).cluster();
    }
    return out;
  }

  @Test
  @DisplayName("the same vectors, k and seed give the same centres and the same assignment, bit "
      + "for bit, whichever thread fits them and whatever ran before")
  void sameSeedSameAssignment() throws Exception {
    final double[][] data = vectors(400, 8, 1);
    final Clusterer.Fit first = clusterer.fit(data, 8, 42);
    assertTrue(first.fitted());
    assertEquals(8, first.centres().length);
    // a fit with another seed in between moves Smile's random generator on
    assertTrue(clusterer.fit(data, 8, 7).fitted());
    final Clusterer.Fit again = clusterer.fit(data, 8, 42);
    assertArrayEquals(first.centres(), again.centres());
    assertEquals(first.distortion(), again.distortion());
    assertArrayEquals(assigned(first, data), assigned(again, data));

    final ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      final List<Future<Clusterer.Fit>> fits = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        final long seed = t % 2 == 0 ? 42 : t;
        fits.add(pool.submit(() -> clusterer.fit(data, 8, seed)));
      }
      for (int t = 0; t < 8; t += 2) {
        assertArrayEquals(first.centres(), fits.get(t).get().centres(), "thread " + t);
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("each vector goes to its nearest centre by Euclidean distance, which is reported; "
      + "three far-apart groups give three clusters")
  void nearestCentre() {
    final double[][] data = new double[30][];
    for (int i = 0; i < 30; i++) {
      final double e = (i % 10) * 1e-3;
      data[i] = switch (i / 10) {
        case 0 -> new double[] {1 + e, 0};
        case 1 -> new double[] {0, 1 + e};
        default -> new double[] {-1 - e, 0};
      };
    }
    final Clusterer.Fit fit = clusterer.fit(data, 3, 42);
    final int[] a = assigned(fit, data);
    for (int g = 0; g < 3; g++) {
      for (int i = g * 10; i < g * 10 + 10; i++) {
        assertEquals(a[g * 10], a[i], "point " + i);
      }
    }
    assertNotEquals(a[0], a[10]);
    assertNotEquals(a[10], a[20]);
    assertNotEquals(a[0], a[20]);
    final Clusterer.Assignment far = fit.assign(new double[] {3, 0});
    assertEquals(a[0], far.cluster());
    final double[] centre = fit.centres()[far.cluster()];
    assertEquals(Math.hypot(3 - centre[0], centre[1]), far.distance(), 1e-12);
  }

  @Test
  @DisplayName("fewer distinct vectors than k reports not fitted, k never shrunk; equal vectors, "
      + "a negative zero equal to zero, count once")
  void tooFewDistinct() {
    final double[][] data = new double[12][];
    for (int i = 0; i < 12; i++) {
      data[i] = switch (i % 3) {
        case 0 -> new double[] {1, 0};
        case 1 -> new double[] {0, 1};
        default -> new double[] {i == 2 ? -0.0 : 0.0, -1};
      };
    }
    assertEquals(3, Clusterer.distinct(data));
    final Clusterer.Fit fit = clusterer.fit(data, 4, 42);
    assertFalse(fit.fitted());
    assertEquals(4, fit.k(), "k as asked");
    assertEquals(0, fit.centres().length);
    assertTrue(Double.isNaN(fit.distortion()));
    assertTrue(clusterer.fit(data, 3, 42).fitted(), "exactly k distinct vectors is enough");
    assertFalse(clusterer.fit(new double[0][], 8, 42).fitted(), "an empty history");
  }
}
