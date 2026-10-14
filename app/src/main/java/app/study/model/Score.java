package app.study.model;

import app.model.Metrics;
import java.util.List;

/**
 * What a result is allowed to report.
 *
 * <p>Deliberately narrow. Section 9.2 names the primary table's contents and then says what must
 * not join it: no RMSE, no R², no factor-of-two. That is not a stylistic preference -- the study
 * already has a paper whose headline is an R², and mixing the two rulers in one table invites
 * reading a worse MAE as a better result because some other column moved.
 *
 * <p>So this type carries the mean absolute error in hours, the same number in days because that is
 * how a reader thinks about a unit, the gap against the baseline in both absolute and
 * relative terms, and Spearman as the secondary question of whether the ordering is right.
 *
 * @param maeHours the formal metric
 * @param gainHours baseline minus this, so a positive number is an improvement
 * @param gainPercent the same as a share of the baseline
 * @param gainOverConstantPercent the gain over predicting one number for every row -- the global
 *     median of the fit side. Reported beside the mandatory gain because on this data the mandatory
 *     baseline is <b>worse</b> than that constant, so a gain measured only against it flatters every
 *     model in the table. The specification names the project median as the reference; it does not
 *     forbid saying what the reference is worth
 * @param spearman correlation between predicted and observed duration. Secondary, and never
 *     compared with the developer ordering of the previous paper, which measures something else
 * @param pairwise the share of pairs of units the model orders correctly: of every two rows
 *     whose true durations differ, how often it says the slower one is slower. Added on 27/09/2026,
 *     before the reserved partition was opened, because the rank correlation beside it is not a
 *     number a reader can act on and this one is. Half is the coin; it is the same quantity the
 *     previous paper reported for ordering people, where the answer was 49,6%
 */
public record Score(String label, int n, double maeHours, double maeDays,
    double gainHours, double gainPercent, double gainOverConstantPercent, double spearman,
    double pairwise) {

  /**
   * @param predictedHours one prediction per row, in hours, on the original scale
   * @param observedHours the truth, same order
   * @param baselineMaeHours what the mandatory reference scored on the same rows
   */
  public static Score of(final String label, final double[] predictedHours,
      final double[] observedHours, final double baselineMaeHours, final double constantMaeHours) {

    if (predictedHours.length != observedHours.length) {
      throw new IllegalArgumentException("predictions and truths must line up row for row");
    }
    double sum = 0;
    for (int i = 0; i < predictedHours.length; i++) {
      sum += Math.abs(predictedHours[i] - observedHours[i]);
    }
    final double maeHours = sum / predictedHours.length;
    return new Score(label, predictedHours.length, maeHours, maeHours / 24.0,
        baselineMaeHours - maeHours,
        baselineMaeHours == 0 ? 0 : 100.0 * (baselineMaeHours - maeHours) / baselineMaeHours,
        constantMaeHours == 0 ? 0 : 100.0 * (constantMaeHours - maeHours) / constantMaeHours,
        // Reused rather than reimplemented: the same ranking correlation the previous paper used,
        // so that a difference between the two studies is never an artefact of two tie rules.
        Metrics.spearman(predictedHours, observedHours),
        pairwiseAccuracy(predictedHours, observedHours));
  }

  /**
   * Of every pair of rows whose true durations differ, the share the prediction orders correctly.
   *
   * <p>Pairs tied in the truth are skipped rather than counted as wrong: there is no right answer
   * to give for two units that took the same time. A pair tied in the <b>prediction</b> is
   * counted as half, because the model gave no ordering and guessing would be a coin -- scoring it
   * as correct would reward a model that predicts one constant with a perfect score.
   */
  public static double pairwiseAccuracy(final double[] predicted, final double[] observed) {
    // Todos os pares de 1.865 linhas sao 1,7 milhao, e este metodo e chamado uma vez por tentativa
    // de parametro. O laco e escrito com comparacoes diretas, sem signum e sem ramos extras, porque
    // a diferenca entre 10 e 25 nanossegundos por par decide se a rodada leva um minuto ou vinte.
    long comparable = 0;
    long correct = 0;
    long tiedGuess = 0;
    for (int i = 0; i < observed.length; i++) {
      final double oi = observed[i];
      final double pi = predicted[i];
      for (int j = i + 1; j < observed.length; j++) {
        final double oj = observed[j];
        if (oi == oj) {
          continue;
        }
        comparable++;
        final double pj = predicted[j];
        if (pi == pj) {
          tiedGuess++;
        } else if ((oi > oj) == (pi > pj)) {
          correct++;
        }
      }
    }
    return comparable == 0 ? Double.NaN : (correct + 0.5 * tiedGuess) / comparable;
  }

  /** A reference scores itself, so its row of the table comes out of the same code path. */
  public static Score reference(final String label, final double[] predictedHours,
      final double[] observedHours, final double constantMaeHours) {

    double sum = 0;
    for (int i = 0; i < predictedHours.length; i++) {
      sum += Math.abs(predictedHours[i] - observedHours[i]);
    }
    return of(label, predictedHours, observedHours, sum / predictedHours.length, constantMaeHours);
  }

  /**
   * What the outcome itself looks like, printed above the table.
   *
   * <p>Without it a mean absolute error is unreadable. On this population the median duration is
   * 56 hours and the mean is 229 -- four times larger -- so an error of two hundred hours is not
   * "about right on average", it is four times the typical unit. Any table of MAE that does
   * not carry the distribution beside it invites being read as better than it is.
   */
  public record Outcome(int n, double meanHours, double medianHours, double p25Hours,
      double p75Hours, double p90Hours, double maxHours) {

    public static Outcome of(final double[] hours) {
      final double[] sorted = hours.clone();
      java.util.Arrays.sort(sorted);
      double sum = 0;
      for (final double value : sorted) {
        sum += value;
      }
      final int n = sorted.length;
      return new Outcome(n, sum / n, quantile(sorted, 0.50), quantile(sorted, 0.25),
          quantile(sorted, 0.75), quantile(sorted, 0.90), sorted[n - 1]);
    }

    private static double quantile(final double[] sorted, final double share) {
      return sorted[Math.min(sorted.length - 1, (int) (share * sorted.length))];
    }
  }

  /** The header the report prints above a block of these. */
  public static List<String> header() {
    return List.of("familia / perfil", "n", "MAE h", "MAE dias", "ganho h", "ganho %",
        "ganho vs constante %", "Spearman");
  }
}
