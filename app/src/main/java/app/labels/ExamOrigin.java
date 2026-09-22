package app.labels;

/**
 * Como o primeiro exame externo de uma tarefa foi reconhecido.
 *
 * <p>Medido sobre os 18.786 pull requests mesclados: revisao formal existe em 56,4% deles, e um
 * comentario de terceiro leva a cobertura a 78,1%. Contar so revisao formal nao e conservador, e
 * enviesado -- a fracao mediana da vida gasta antes da fronteira cai de 0,290 para 0,126 quando o
 * comentario entra, porque em 21,6% dos casos alguem comentou bem antes de qualquer revisao ser
 * submetida.
 *
 * <p>O filtro de autoria nao e detalhe: 12.716 pull requests tem algum comentario, mas so 10.570
 * tem comentario de quem nao e autor dos commits. O resto e o autor falando sozinho, que nao e
 * ninguem examinando coisa nenhuma.
 *
 * <p>Ausencia nao e um valor deste enum. Os 21,9% sem exame registrado carregam um exame vazio, e
 * as duas parcelas da duracao ficam ausentes para eles -- dizer que esperaram a vida inteira
 * afirmaria que ninguem olhou, e dizer que esperaram zero afirmaria o contrario.
 */
public enum ExamOrigin {

  /** Uma revisao formal submetida, de {@code pull_request_review}. */
  REVIEW,

  /** Um comentario de quem nao e autor dos commits, de {@code pull_request_comment}. */
  COMMENT
}
