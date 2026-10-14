package app.pipeline;

import app.model.ElasticNetModel;
import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.Family;
import app.pullrequests.RowCache;
import app.pullrequests.model.Cell;
import app.pullrequests.model.DesignMatrix;
import app.pullrequests.model.Prepared;
import app.pullrequests.model.Score;
import java.util.List;

/**
 * The sweep of the correction of 28/09/2026: the same Elastic Net sweep the development ran, on the
 * inner selection, for the two families that carry the previous paper's columns.
 *
 * <p>The grid below is the one {@code PullRequestFitTask} declares -- three mixtures, twenty-four
 * penalties log-spaced from the largest relevant one down to a ten-thousandth of it, lowest absolute
 * error on the inner selection wins, ties to the earlier point. It is written again here rather than
 * reached into, and {@link #run} proves the copy faithful: with the registered columns it must choose
 * exactly the parameters the registration froze. Only then does it sweep with the corrected columns.
 */
public final class PullRequestCorrectedSweepTask {

  static final double[] ALPHAS = {0.10, 0.50, 0.95};

  /**
   * The pairs the corrected sweep chose on the inner selection, frozen by the addendum of
   * {@code REGISTRO-DA-CORRECAO.md} before the reserved rows were scored with the corrected columns.
   * The only parameters the corrected reserved scoring may use.
   */
  public static final List<PullRequestReservedTask.Frozen> FROZEN_CORRIGIDO = List.of(
      new PullRequestReservedTask.Frozen(Family.COMBINADO_MAIS_ARTIGO4, 0.95, 115.59160904271248),
      new PullRequestReservedTask.Frozen(Family.CONTRIBUICAO_PROPRIA, 0.95, 23.29561258824217));
  static final int LAMBDA_STEPS = 24;
  static final double LAMBDA_FLOOR_RATIO = 1e-4;

  /**
   * @param step which penalty of its mixture's grid was chosen; 0 is the grid's top edge and
   *     {@code LAMBDA_STEPS - 1} its bottom edge
   */
  public record Chosen(Family family, double alpha, double lambda, int step, Score inner) {

    public boolean atEdge() {
      return step == 0 || step == LAMBDA_STEPS - 1;
    }
  }

  public Chosen sweep(final List<RowCache.Entry> entries, final PriorStudyFeatures priorStudy,
      final Family family) {
    final Prepared p = Prepared.inner(entries, priorStudy);
    final Cell cell = Cell.of(family, PullRequestReservedTask.PROFILE);
    final DesignMatrix matrix = DesignMatrix.fittedOn(p.fitRows(), cell.attributes());
    final double[][] x = matrix.applyAll(p.fitRows());
    final double[][] xs = matrix.applyAll(p.scoreRows());
    final double[] y = p.y();

    final app.pullrequests.model.Baseline baseline =
        app.pullrequests.model.Baseline.learnedOn(p.fitUnits());
    final double[] constant = new double[p.observed().length];
    java.util.Arrays.fill(constant, baseline.constantHours());
    final double constantMae = PullRequestAuditTask.mae(constant, p.observed());
    final double baselineMae = PullRequestAuditTask.mae(baseline.projectOf(p.scoreUnits()),
        p.observed());

    final double top = largestPenalty(x, y);
    ElasticNetModel best = null;
    double bestMae = Double.POSITIVE_INFINITY;
    int bestStep = -1;
    for (final double alpha : ALPHAS) {
      for (int step = 0; step < LAMBDA_STEPS; step++) {
        final double lambda = top * Math.pow(LAMBDA_FLOOR_RATIO, (double) step / (LAMBDA_STEPS - 1));
        final ElasticNetModel model = ElasticNetModel.fit(x, y, matrix.columns(), alpha, lambda);
        final double mae = PullRequestAuditTask.mae(hours(model, xs), p.observed());
        if (mae < bestMae) {
          bestMae = mae;
          best = model;
          bestStep = step;
        }
      }
    }
    return new Chosen(family, best.alpha(), best.lambda(), bestStep,
        Score.of(cell.label(), hours(best, xs), p.observed(), baselineMae, constantMae));
  }

  private static double[] hours(final ElasticNetModel model, final double[][] xs) {
    final double[] out = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      out[i] = DesignMatrix.hours(model.predict(xs[i]));
    }
    return out;
  }

  /** The same anchor PullRequestFitTask uses: the largest |x'(y - mean)| over the columns. */
  static double largestPenalty(final double[][] x, final double[] y) {
    double mean = 0;
    for (final double v : y) {
      mean += v;
    }
    mean /= y.length;
    double largest = 0;
    for (int c = 0; c < x[0].length; c++) {
      double dot = 0;
      for (int r = 0; r < x.length; r++) {
        dot += x[r][c] * (y[r] - mean);
      }
      largest = Math.max(largest, Math.abs(dot));
    }
    return largest;
  }
}
