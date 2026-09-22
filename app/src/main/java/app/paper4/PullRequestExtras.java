package app.paper4;

import java.util.Map;

/**
 * Os preditores que so existem para pull requests, ja calculados e ja nomeados pelo campo que
 * {@link app.model.Columns} le.
 *
 * <p>Um mapa de nome para valor, e nao um record com um campo por preditor, por uma razao
 * deliberada: acrescentar um preditor nesta familia passa a custar uma linha aqui e uma linha em
 * {@code Columns}, sem tocar na porta, no armazenamento nem na tarefa. O preco e que um erro de
 * digitacao no nome do campo vira uma coluna constante em vez de um erro de compilacao -- por isso
 * {@link #FIELDS} existe e {@code ColumnsProfileTest} confere que toda coluna do perfil de pull
 * request declarada aqui tem nome correspondente.
 *
 * <p><b>Dois grupos, e a diferenca importa.</b> Os campos {@code pr_*} sao conhecidos quando o
 * pull request abre. Os campos {@code exame_*} so existem depois que alguem olhou: eles sao
 * passado em relacao a fronteira do alvo AUTOR e portanto legitimos ali, mas nao existem no
 * instante em que se atribuiria a tarefa a alguem. Nenhum deles serve para formar equipe, e o
 * artigo tem que dizer isso na mesma frase em que reporta o quanto eles preveem.
 */
public record PullRequestExtras(Map<String, Double> values) {

  /** Conhecido na abertura: quantos arquivos o pull request toca. */
  public static final String FILES = "pr_files";

  /**
   * Conhecido na abertura: quantos dos arquivos deste pull request a pessoa ja tocou em pull
   * requests mesclados anteriores dela no mesmo projeto. Mede sobreposicao, nao volume -- e a unica
   * coluna do desenvolvedor que nao e uma contagem acumulada disfarcada.
   */
  public static final String FAMILIARITY = "pr_familiarity";

  /** Conhecido na abertura: a mesma sobreposicao como fracao dos arquivos do pull request. */
  public static final String FAMILIARITY_SHARE = "pr_familiarity_share";

  /**
   * Conhecido na abertura: a espera mediana ate o primeiro exame nos pull requests do projeto
   * mesclados nos 90 dias anteriores. A versao legitima de "tempo de revisao como preditor" -- e
   * passado, e do projeto e nao deste pull request, e nao e parcela do desfecho desta linha.
   */
  public static final String PROJECT_WAIT = "pr_project_wait";

  /**
   * Conhecido na abertura: quem abriu e MEMBER do projeto. Referencia = CONTRIBUTOR, que e a
   * maioria (3.252 de 4.957 rotulos de treino).
   *
   * <p>Nao e habilidade, e posicao formal, e entra exatamente por isso: sem ela, uma mediana
   * historica baixa nao distingue "esta pessoa e rapida" de "o PR desta pessoa nao e segurado por
   * ninguem". Medido em treino, ln(1+total): NONE 1,921, CONTRIBUTOR 1,518, MEMBER 1,305.
   */
  public static final String ASSOC_MEMBER = "pr_assoc_member";

  /** Conhecido na abertura: quem abriu nao tem vinculo nenhum com o projeto (NONE). */
  public static final String ASSOC_NONE = "pr_assoc_none";

  /** So na fronteira do exame: a primeira revisao pediu mudancas. Referencia = aprovada ou nenhuma. */
  public static final String EXAM_CHANGES_REQUESTED = "exame_changes_requested";

  /** So na fronteira do exame: a primeira revisao comentou sem aprovar nem pedir mudanca. */
  public static final String EXAM_COMMENTED = "exame_commented";

  /** So na fronteira do exame: a fronteira foi um comentario, nao uma revisao formal. */
  public static final String EXAM_BY_COMMENT = "exame_por_comentario";

  /** So na fronteira do exame: volume acumulado de quem examinou, lido do mesmo ledger. */
  public static final String REVIEWER_VOLUME = "exame_volume_revisor";

  /** So na fronteira do exame: a espera que de fato houve, em dias. */
  public static final String REALISED_WAIT = "exame_espera_realizada";

  public static final java.util.List<String> FIELDS = java.util.List.of(
      FILES, FAMILIARITY, FAMILIARITY_SHARE, PROJECT_WAIT, ASSOC_MEMBER, ASSOC_NONE,
      EXAM_CHANGES_REQUESTED, EXAM_COMMENTED, EXAM_BY_COMMENT, REVIEWER_VOLUME, REALISED_WAIT);

  public PullRequestExtras {
    values = Map.copyOf(values);
  }
}
