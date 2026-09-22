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
}
