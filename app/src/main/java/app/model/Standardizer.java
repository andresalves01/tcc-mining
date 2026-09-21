package app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Centres each column and divides by its deviation, using the fit side's statistics only.
 *
 * <p>Fitted once and then applied, rather than recomputed per partition: a column standardised by
 * its own partition's mean would make the coefficients describe a different variable on each side,
 * and the selection set would silently score a model that was never fitted.
 *
 * <p>Constant columns are dropped rather than divided by zero. Which ones are constant is a
 * property of the fit side and is reported, because a predictor that never varies in training is a
 * predictor the study cannot speak about, whatever it does elsewhere.
 */
public final class Standardizer {

  private final List<String> columns;
  private final int[] kept;
  private final double[] mean;
  private final double[] deviation;
  private final List<String> dropped;

  private Standardizer(final List<String> columns, final int[] kept, final double[] mean,
      final double[] deviation, final List<String> dropped) {

    this.columns = List.copyOf(columns);
    this.kept = kept.clone();
    this.mean = mean.clone();
    this.deviation = deviation.clone();
    this.dropped = List.copyOf(dropped);
  }

  /** Population deviation, matching the one the team statistics use. */
  public static Standardizer fittedOn(final Observations fit) {
    final int width = fit.columns().size();
    final int n = fit.rows().size();
    final double[] mean = new double[width];
    for (final Observation row : fit.rows()) {
      for (int c = 0; c < width; c++) {
        mean[c] += row.values()[c];
      }
    }
    for (int c = 0; c < width; c++) {
      mean[c] /= n;
    }
    final double[] deviation = new double[width];
    for (final Observation row : fit.rows()) {
      for (int c = 0; c < width; c++) {
        final double d = row.values()[c] - mean[c];
        deviation[c] += d * d;
      }
    }
    final List<Integer> keep = new ArrayList<>();
    final List<String> dropped = new ArrayList<>();
    for (int c = 0; c < width; c++) {
      deviation[c] = Math.sqrt(deviation[c] / n);
      if (deviation[c] > 1e-12) {
        keep.add(c);
      } else {
        dropped.add(fit.columns().get(c));
      }
    }
    final int[] kept = keep.stream().mapToInt(Integer::intValue).toArray();
    final List<String> names = new ArrayList<>();
    final double[] keptMean = new double[kept.length];
    final double[] keptDeviation = new double[kept.length];
    for (int i = 0; i < kept.length; i++) {
      names.add(fit.columns().get(kept[i]));
      keptMean[i] = mean[kept[i]];
      keptDeviation[i] = deviation[kept[i]];
    }
    return new Standardizer(names, kept, keptMean, keptDeviation, dropped);
  }

  /** The surviving columns, in the order the standardised rows carry them. */
  public List<String> columns() {
    return columns;
  }

  /** Columns that never varied on the fit side, so nothing can be said about them. */
  public List<String> dropped() {
    return dropped;
  }

  public double[] apply(final Observation row) {
    final double[] out = new double[kept.length];
    for (int i = 0; i < kept.length; i++) {
      out[i] = (row.values()[kept[i]] - mean[i]) / deviation[i];
    }
    return out;
  }
}
