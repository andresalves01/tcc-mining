package app.pipeline;

import app.paper6.Profile;
import java.util.List;

/**
 * What {@code REGISTRO-PREVIO.md} froze, as literals. Changing a line here invalidates the result.
 *
 * <p>{@code paper6-reservado} refuses to start while this is empty, so the reserved rows cannot be
 * reached before the parameters that will be used on them exist in writing -- and in a commit that
 * precedes the run.
 */
public final class Paper6Registration {

  /** The principal reading, fixed before any result: the widest one that is still ex ante. */
  public static final Profile PRINCIPAL = Profile.T0_RECONSTRUIDO;

  /**
   * Copied from {@code outputs/parametros-grade.tsv}, which {@code paper6-grade} wrote on
   * 28/09/2026 from the inner selection ({@code implementacao/61-}). {@code RegistrationTest} checks
   * the two agree to the last digit.
   */
  public static final List<Paper6EvaluationTask.Frozen> FROZEN = List.of(
      new Paper6EvaluationTask.Frozen("YU_ISSUE / STRICT_T0", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("YU_ISSUE / T0_RECONSTRUIDO", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("YU_ISSUE / DESFECHO_CONHECIDO", 0.5, 12440.806537097042),
      new Paper6EvaluationTask.Frozen("CHOUCHEN_ISSUE / STRICT_T0", 0.5, 12272.09638002121),
      new Paper6EvaluationTask.Frozen("CHOUCHEN_ISSUE / T0_RECONSTRUIDO", 0.1, 26561.48782377769),
      new Paper6EvaluationTask.Frozen("CHOUCHEN_ISSUE / DESFECHO_CONHECIDO", 0.5, 12272.09638002121),
      new Paper6EvaluationTask.Frozen("ESPEC_PROPRIA / STRICT_T0", 0.1, 14317.070362886076),
      new Paper6EvaluationTask.Frozen("ESPEC_PROPRIA / T0_RECONSTRUIDO", 0.1, 14317.070362886076),
      new Paper6EvaluationTask.Frozen("ESPEC_PROPRIA / ATRIBUICAO_DADA", 0.95, 6146.216788160107),
      new Paper6EvaluationTask.Frozen("ESPEC_PROPRIA / DESFECHO_CONHECIDO", 0.95, 6146.216788160107),
      new Paper6EvaluationTask.Frozen("COMBINADO_LITERATURA / STRICT_T0", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("COMBINADO_LITERATURA / T0_RECONSTRUIDO", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("COMBINADO_LITERATURA / DESFECHO_CONHECIDO", 0.5, 12440.806537097042),
      new Paper6EvaluationTask.Frozen("TRIAGEM_JIRA / T0_RECONSTRUIDO", 0.1, 11168.337291218888),
      new Paper6EvaluationTask.Frozen("COMBINADO_TOTAL / STRICT_T0", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("COMBINADO_TOTAL / T0_RECONSTRUIDO", 0.1, 26926.64082161534),
      new Paper6EvaluationTask.Frozen("COMBINADO_TOTAL / ATRIBUICAO_DADA", 0.1, 62204.0326854852),
      new Paper6EvaluationTask.Frozen("COMBINADO_TOTAL / DESFECHO_CONHECIDO", 0.95, 15126.250881229791),
      new Paper6EvaluationTask.Frozen("SO_ATRIBUICAO (so o bloco da pessoa que fez)", 0.95, 6146.216788160107),
      new Paper6EvaluationTask.Frozen("SO_DESFECHO (so a mudanca que resolveu)", 0.95, 2431.555316052719));

  private Paper6Registration() {}
}
