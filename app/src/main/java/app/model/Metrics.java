package app.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * What a prediction is scored on.
 *
 * <p>Both scales are reported because they disagree and the disagreement is informative: the mean
 * absolute error in days is dominated by the long right tail, while the log-scale fit is dominated
 * by the mass of tasks resolved in hours. A model can improve one and lose the other.
 *
 * @param maeDays the study's declared primary metric
 * @param withinFactorTwo share of predictions within a factor of two of the truth, which is the
 *     honest reading of whether a duration was estimated rather than merely ordered
 */
public record Metrics(
    int n, double maeDays, double rmseDays, double medianErrorDays,
    double r2Log, double r2Days, double rankCorrelation, double withinFactorTwo) {

  /** @param prediction the predicted value on the log scale, which is what the model produces */
  public static Metrics of(final List<Observation> rows,
      final ToDoubleFunction<Observation> prediction) {

    final int n = rows.size();
    final double[] errorsDays = new double[n];
    final double[] predicted = new double[n];
    final double[] actual = new double[n];
    double meanLog = 0;
    double meanDays = 0;
    int withinTwo = 0;
    for (int i = 0; i < n; i++) {
      final Observation row = rows.get(i);
      predicted[i] = prediction.applyAsDouble(row);
      actual[i] = row.logTtc();
      errorsDays[i] = Math.abs(Math.exp(predicted[i]) - row.ttcDays());
      meanLog += actual[i];
      meanDays += row.ttcDays();
      if (Math.abs(actual[i] - predicted[i]) <= Math.log(2)) {
        withinTwo++;
      }
    }
    meanLog /= n;
    meanDays /= n;

    double absolute = 0;
    double squared = 0;
    double residualLog = 0;
    double totalLog = 0;
    double residualDays = 0;
    double totalDays = 0;
    for (int i = 0; i < n; i++) {
      absolute += errorsDays[i];
      squared += errorsDays[i] * errorsDays[i];
      final double rl = actual[i] - predicted[i];
      residualLog += rl * rl;
      totalLog += (actual[i] - meanLog) * (actual[i] - meanLog);
      residualDays += errorsDays[i] * errorsDays[i];
      final double td = rows.get(i).ttcDays() - meanDays;
      totalDays += td * td;
    }
    final double[] sorted = errorsDays.clone();
    Arrays.sort(sorted);
    return new Metrics(n, absolute / n, Math.sqrt(squared / n), sorted[n / 2],
        1 - residualLog / totalLog, 1 - residualDays / totalDays,
        spearman(actual, predicted), (double) withinTwo / n);
  }

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

  /** For the report: the named rows of a comparison table. */
  public static List<String> header() {
    return new ArrayList<>(List.of("n", "MAE dias", "RMSE dias", "erro mediano",
        "R2 log", "R2 dias", "ordena tarefas", "dentro de 2x"));
  }
}
