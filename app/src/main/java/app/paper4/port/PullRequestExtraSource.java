package app.paper4.port;

import app.paper4.PullRequestExtras;
import java.util.Map;

/**
 * De onde saem os preditores especificos de pull request, chaveados pelo id do rotulo.
 *
 * <p>Separada de {@link app.features.port.IssueAttributeSource} porque aquela porta responde o que
 * a tarefa dizia de si quando foi aberta, e estes preditores incluem coisas que a tarefa nao diz --
 * o historico de arquivos da pessoa, a espera tipica do projeto, quem examinou. Forcar as duas
 * numa porta so faria o pipeline de issues carregar um contrato que ele nao tem como cumprir.
 */
public interface PullRequestExtraSource {

  /** Vazio para um id sem dados, nunca zeros: a ausencia e do chamador decidir. */
  Map<String, PullRequestExtras> of(java.util.Collection<String> pullRequestIds);

  String describe();

  /**
   * Auditoria temporal de 28/09/2026, somente leitura: para cada pull request, o que a espera
   * tipica do projeto e a familiaridade leram que so se soube depois da abertura.
   *
   * <p>Cada vetor, por id: [0] PRs do projeto usados na espera; [1] desses, examinados em t0 ou
   * depois; [2] desses, mesclados em t0 ou depois; [3] 1 se a espera muda usando so PRs examinados
   * e mesclados antes de t0; [4] PRs anteriores do autor usados na familiaridade; [5] desses,
   * mesclados em t0 ou depois; [6] 1 se a familiaridade muda usando so os mesclados antes de t0.
   */
  default Map<String, long[]> temporalAudit(final java.util.Collection<String> pullRequestIds) {
    throw new UnsupportedOperationException(describe() + " nao implementa a auditoria temporal");
  }
}
