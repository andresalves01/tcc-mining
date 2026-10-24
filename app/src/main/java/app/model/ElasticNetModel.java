package app.model;

import java.util.ArrayList;
import java.util.List;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.regression.ElasticNet;
import smile.regression.LinearModel;

/**
 * The fitted regression, wrapped so the rest of the study never touches the library's shapes.
 *
 * <p>Parameters are named the way the literature names them — a mixing {@code alpha} and a strength
 * {@code lambda} — and converted here to the two penalties the library takes. Both are clamped
 * away from exactly zero, because the endpoints of the mixing range would otherwise ask an elastic
 * net to be a pure lasso or a pure ridge, which it declines to be.
 *
 * <p><b>This estimator does not return exact zeros.</b> It solves the lasso by preconditioned
 * conjugate gradient, so a coefficient that carries nothing settles near {@code 1e-5} rather than
 * at zero; {@code app.model.ElasticNetLibraryTest} pins that behaviour. Selection therefore has to
 * be read through {@link #selected}, against a threshold declared in advance, and never by testing
 * a coefficient against zero.
 */
public final class ElasticNetModel {

  /**
   * Below this share of the largest coefficient, a weight is reported as not selected.
   *
   * <p>Declared here, once, rather than chosen after seeing which columns land on which side of
   * it. Coefficients are fitted on standardised columns, so they are already comparable to each
   * other, and the estimator's floor sits five orders of magnitude below a real weight.
   */
  public static final double SELECTION_THRESHOLD = 1e-4;

  private final double[] coefficients;
  private final double intercept;
  private final double alpha;
  private final double lambda;

  private ElasticNetModel(final double[] coefficients, final double intercept,
      final double alpha, final double lambda) {

    this.coefficients = coefficients.clone();
    this.intercept = intercept;
    this.alpha = alpha;
    this.lambda = lambda;
  }

  /**
   * @param alpha the mixing parameter: 1 is all L1, 0 is all L2
   * @param lambda the penalty strength. <b>Not normalised by the number of rows</b> — the library
   *     applies it as given, so a grid that works on two thousand rows does nothing on twenty-five
   *     thousand. Derive it from the data
   */
  public static ElasticNetModel fit(final double[][] predictors, final double[] outcome,
      final List<String> columns, final double alpha, final double lambda) {

    final double[][] frame = new double[predictors.length][columns.size() + 1];
    for (int i = 0; i < predictors.length; i++) {
      System.arraycopy(predictors[i], 0, frame[i], 0, columns.size());
      frame[i][columns.size()] = outcome[i];
    }
    final List<String> names = new ArrayList<>(columns);
    names.add("__outcome");
    final LinearModel model = ElasticNet.fit(Formula.lhs("__outcome"),
        DataFrame.of(frame, names.toArray(String[]::new)),
        Math.max(alpha * lambda, 1e-8), Math.max((1 - alpha) * lambda, 1e-8));
    return new ElasticNetModel(model.coefficients(), model.intercept(), alpha, lambda);
  }

  public double predict(final double[] row) {
    double sum = intercept;
    for (int i = 0; i < coefficients.length; i++) {
      sum += coefficients[i] * row[i];
    }
    return sum;
  }

  public double[] coefficients() {
    return coefficients.clone();
  }

  public double alpha() {
    return alpha;
  }

  public double lambda() {
    return lambda;
  }

  /** Whether column {@code i} survived selection, against the threshold declared above. */
  public boolean selected(final int i) {
    double largest = 0;
    for (final double c : coefficients) {
      largest = Math.max(largest, Math.abs(c));
    }
    return largest > 0 && Math.abs(coefficients[i]) / largest >= SELECTION_THRESHOLD;
  }

  /** How many columns the estimator kept, by that threshold. */
  public int selectedCount() {
    int kept = 0;
    for (int i = 0; i < coefficients.length; i++) {
      if (selected(i)) {
        kept++;
      }
    }
    return kept;
  }
}
