package app.labels;

/**
 * Qual duracao de um rotulo o ajuste trata como desfecho.
 *
 * <p>Substitui o seletor anterior, que escolhia o intervalo na hora de rotular. Escolher aqui e
 * melhor por uma razao concreta: os tres alvos passam a partilhar a mesma populacao, a mesma data
 * de abertura e, por consequencia, a <b>mesma divisao temporal</b> -- que e o que torna legitimo
 * somar as previsoes dos dois componentes e chamar a soma de previsao do total. Escolhendo no
 * rotulo, cada braco recalculava o proprio corte sobre a propria populacao e as previsoes nao
 * eram comensuraveis.
 *
 * <p>Os tres intervalos sao os mesmos de antes: {@link #TOTAL} era {@code OPEN_TO_MERGE},
 * {@link #ESPERA} era {@code OPEN_TO_REVIEW} e {@link #AUTOR} era {@code REVIEW_TO_MERGE}.
 */
public enum TtcTarget {

  /** Abertura ate o merge. A vida inteira, e o desfecho que o artigo reporta. */
  TOTAL,

  /**
   * Abertura ate o primeiro exame externo. Predominantemente espera por revisor: neste corpus o
   * codigo ja existe quando o pull request abre -- 72,0% dos pull requests datavies tem todos os
   * commits anteriores a abertura, a mediana e de um commit por pull request, e rascunho aparece
   * em 0,8% deles. Serve de controle negativo: a historia de quem escreveu nao deveria prever
   * quanto tempo um revisor leva para aparecer.
   */
  ESPERA,

  /** Abertura ate o merge, com os fins de semana descontados. Ver {@link WorkingTime}. */
  TOTAL_UTIL,

  /** Abertura ate o primeiro exame, com os fins de semana descontados. */
  ESPERA_UTIL,

  /** Primeiro exame ate o merge, com os fins de semana descontados. */
  AUTOR_UTIL,

  /**
   * Primeiro exame externo ate o merge. Onde o trabalho do autor se concentra, sem ser so isso --
   * tambem contem espera pela re-revisao e por quem aperta o botao. 24,2% dos pull requests
   * datavies tem commits dos dois lados da abertura, entao nenhum dos dois segmentos isola o
   * trabalho de ninguem; sao predominancias, e o artigo deve dizer isso.
   */
  AUTOR
}
