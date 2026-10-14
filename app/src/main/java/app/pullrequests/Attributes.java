package app.pullrequests;

import app.pullrequests.Attribute.Kind;
import app.pullrequests.Attribute.Transform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every construct of the replication, declared once.
 *
 * <p>Twenty-three, not twenty-two: the combined family is twenty attributes and two interactions,
 * and {@code friday_effect} is the one member of Yu's family it drops, because
 * {@code created_weekday_utc} already contains it. Counting them any other way is what produced the
 * "17 constructs" the validation had to correct -- the extraction file has sixteen constructs and
 * one absence indicator, which is not the same thing.
 *
 * <p>A construct shared by the two papers appears once, under Yu's id, with both families recorded:
 * {@code additions_at_open} is Y03 and Chouchen's C03, {@code owner_merged_ratio} is Y08 and C09,
 * {@code title_description_word_count} is Y11 and C06. Declaring them twice is how two lists start
 * to disagree.
 *
 * <p>The counts this produces, which {@code AttributesTest} pins against the specification:
 *
 * <pre>
 *                            STRICT_T0  T0_TEXT_PROXY  LITERATURE_COMPATIBLE
 *   YU_PRE_COMPAT                   9        12                 17
 *   CHOUCHEN_CORE_10                6         7                 10
 *   CONTRIBUICAO_PROPRIA            3         3                  3
 *   COMBINED_PRE                   13        16                 22
 *   COMBINADO_MAIS_ARTIGO4         16        19                 25
 * </pre>
 *
 * <p>The row of threes is not a mistake: every construct of that family is clean in {@code t0}, so
 * no profile ever removes one. It is the only family whose reading does not change with the evidence
 * profile, which is why it is compared against the <b>strict</b> column of the others.
 *
 * <p>The seven of Chouchen under {@code T0_TEXT_PROXY} are the seven the complement already
 * accepted as approved, which makes that cell a check on the whole table rather than one more
 * number to trust.
 */
public final class Attributes {

  // Every member of the combined family is also a member of the extension, which is the combined
  // family plus three columns. Declared once here so the two can never drift apart.
  private static final Set<Family> YU = Set.of(Family.YU_PRE_COMPAT, Family.COMBINED_PRE,
      Family.COMBINADO_MAIS_ARTIGO4);
  private static final Set<Family> CHOUCHEN = Set.of(Family.CHOUCHEN_CORE_10,
      Family.COMBINED_PRE, Family.COMBINADO_MAIS_ARTIGO4);
  private static final Set<Family> BOTH = Set.of(Family.YU_PRE_COMPAT, Family.CHOUCHEN_CORE_10,
      Family.COMBINED_PRE, Family.COMBINADO_MAIS_ARTIGO4);
  private static final Set<Family> YU_ONLY = Set.of(Family.YU_PRE_COMPAT);
  // A especificacao do estudo anterior como ela e, e a uniao resolvida que a contem.
  private static final Set<Family> PROPRIA = Set.of(Family.CONTRIBUICAO_PROPRIA,
      Family.COMBINADO_MAIS_ARTIGO4);

  /**
   * As cinco colunas do estudo anterior que a literatura ja tem sob outro nome.
   *
   * <p>Ficam na familia propria, porque tira-las de la seria chamar de "a especificacao do estudo anterior"
   * uma coisa que nao e. E ficam FORA da uniao dos tres, porque la a mesma informacao ja entra pelo
   * nome da literatura, e duas colunas medindo a mesma coisa nao somam nada -- uma delas, o numero
   * de arquivos, ordena as 4.252 linhas de ajuste exatamente igual a sua equivalente.
   *
   * <p>Onde as duas definicoes diferem, a uniao fica com a da literatura: e ela que os dois artigos
   * replicados publicaram, e o ponto da uniao e somar as abordagens, nao arbitrar entre duas medidas
   * do mesmo construto. A escolha esta registrada aqui e em SUBDIVISAO-COLUNAS-ARTIGO4.md, feita
   * antes de rodar.
   */
  private static final Set<Family> PROPRIA_DUPLICADA = Set.of(Family.CONTRIBUICAO_PROPRIA);

  private static final List<Attribute> ALL = build();

  private static final Map<String, Attribute> BY_NAME = ALL.stream()
      .collect(LinkedHashMap::new, (map, a) -> map.put(a.name(), a), Map::putAll);

  private Attributes() {}

  private static List<Attribute> build() {
    final List<Attribute> all = new ArrayList<>();

    // -- projeto e momento: instantes e contagens que o corpus carimba, todos anteriores a t0 --
    all.add(new Attribute("Y01", "project_age_days", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false,
        "commit.committer_date; issue.created_at -- desde min(primeiro commit, primeira issue)"));
    all.add(new Attribute("Y02", "team_size_90d", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "pull_request_event (merged)"));
    all.add(new Attribute("C01", "created_weekday_utc", Kind.CATEGORICAL, Transform.NONE, CHOUCHEN,
        TemporalClass.LIMPO, false, "pull_request.created_at"));
    all.add(new Attribute("C02", "created_hour_utc", Kind.CATEGORICAL, Transform.NONE, CHOUCHEN,
        TemporalClass.LIMPO, false, "pull_request.created_at"));
    all.add(new Attribute("Y15", "friday_effect", Kind.BINARY, Transform.NONE, YU_ONLY,
        TemporalClass.LIMPO, false,
        "pull_request.created_at -- fora do combinado: contido em created_weekday_utc"));

    // -- carga do projeto --
    all.add(new Attribute("Y12", "open_prs_project_at_open", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "pull_request; pull_request_event (closed, merged)"));
    all.add(new Attribute("C10", "recent_open_prs_project_30d", Kind.NUMERIC, Transform.LOG1P,
        CHOUCHEN, TemporalClass.LIMPO, false, "pull_request; pull_request_event"));

    // -- o autor, sempre por creator_id, nunca pela identidade canonica do estudo anterior --
    all.add(new Attribute("C07", "owner_prior_prs", Kind.NUMERIC, Transform.LOG1P, CHOUCHEN,
        TemporalClass.LIMPO, false, "pull_request (todos os projetos)"));
    all.add(new Attribute("C08", "owner_prior_project_prs", Kind.NUMERIC, Transform.LOG1P, CHOUCHEN,
        TemporalClass.LIMPO, false, "pull_request; pull_request_system (mesmo projeto)"));
    // Razao limitada a [0, 1]: log1p nao se aplica, e o denominador zero e ausencia declarada em
    // 483 linhas da populacao sem filtro -- nunca divisao por um, nunca zero.
    all.add(new Attribute("Y08", "owner_merged_ratio", Kind.NUMERIC, Transform.NONE, BOTH,
        TemporalClass.LIMPO, true, "pull_request; pull_request_event (merged)"));
    all.add(new Attribute("Y09", "requester_is_integrator", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.LIMPO, false, "pull_request_event (merged).author_id vs creator_id"));

    // -- social: a spec 1 decidida em 1.5, sobre as duas fontes que tem autor e data completos --
    all.add(new Attribute("Y10", "social_connection_90d", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, true,
        "pull_request_comment; pull_request_review -- pull_request_review_comment fica fora"));

    // -- interacoes: produto das pernas CRUAS, como o extrator de referencia calculou --
    all.add(new Attribute("Y16", "project_age_x_team_size", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "derivado de Y01 e Y02"));
    all.add(new Attribute("Y17", "team_size_x_workload", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "derivado de Y02 e Y12"));

    // -- texto: plausivel em t0, nao demonstravel. Isolado no perfil intermediario --
    all.add(new Attribute("Y11", "title_description_word_count", Kind.NUMERIC, Transform.LOG1P,
        BOTH, TemporalClass.NAO_VERIFICAVEL_EM_T0, false,
        "pull_request.title + pull_request.description -- definicao de Yu; o homonimo de "
            + "Chouchen NAO inclui o titulo"));
    all.add(new Attribute("Y13", "issue_reference", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.NAO_VERIFICAVEL_EM_T0, false,
        "pull_request.title + description -- #n ou chave Jira, pela decisao da 1.5"));
    all.add(new Attribute("Y14", "person_mention", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.NAO_VERIFICAVEL_EM_T0, false, "pull_request.title + description -- @"));

    // -- os seis tecnicos: so no LITERATURE_COMPATIBLE, e nunca descritos como conhecidos na
    //    abertura. Ver a Investigacao H e a decisao de 27/09 sobre a reconstrucao do diff --
    all.add(new Attribute("Y03", "additions_at_open", Kind.NUMERIC, Transform.LOG1P, BOTH,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "pull_request_file.additions -- substituto de qualidade medida, rho 0,857"));
    all.add(new Attribute("Y04", "deletions_at_open", Kind.NUMERIC, Transform.LOG1P, BOTH,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "pull_request_file.deletions -- mesma grandeza das adicoes, rho 0,857"));
    all.add(new Attribute("C05", "num_files_at_open", Kind.NUMERIC, Transform.LOG1P, CHOUCHEN,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "pull_request_file.path -- substituto de qualidade medida, rho 0,848"));
    all.add(new Attribute("Y07", "test_inclusion_at_open", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "pull_request_file.path -- MEDIDO: concordancia 94,4%, kappa 0,887, e o vies e de uma "
            + "direcao so: 233 PRs ganharam teste na revisao, zero perderam"));
    all.add(new Attribute("Y05", "commits_at_open", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.RECONSTRUIVEL_APENAS_NA_SUBPOPULACAO, false,
        "pull_request_commit; commit.committer_date"));
    // Razao entre uma mediana de contagens e uma contagem: nao e contagem, entao sem log1p.
    // Denominador zero -- projeto sem commit na janela -- e ausencia com indicador.
    all.add(new Attribute("Y06", "area_hotness_90d", Kind.NUMERIC, Transform.NONE, YU,
        TemporalClass.ESTAVEL_HERDADO_DA_LISTA_DE_ARQUIVOS, true,
        "pull_request_file.path; file_action; commit -- janela de 90 dias, tres meses de Yu. O "
            + "unico construto que resta nesta classe: e mediana sobre o conjunto de arquivos, "
            + "logo IDENTICO nos 79,5% em que o conjunto e identico, e mediana sobre um conjunto "
            + "de Jaccard 0,884 no resto"));

    // ---- A CONTRIBUICAO PROPRIA: a especificacao do estudo anterior para pull requests --------------
    //
    // Dezoito colunas vivas, lidas de smartshark_paper4.issue_features, que ja as tem calculadas
    // para as 9.018 unidades -- o join por _id casa 9.018 de 9.018, zero orfas, e nada e
    // recomputado aqui. Nao sao "tres medianas do estudo anterior": aquela leitura foi um paliativo, seu
    // numero esta arquivado em implementacao/34-, e onze destas colunas nao tem analogo nenhum
    // na literatura replicada. Ver SUBDIVISAO-COLUNAS-ARTIGO4.md, que classifica cada uma e
    // separa os quatro motivos distintos de uma coluna sair.
    //
    // Duas ficaram DECLARADAS E MORTAS no estudo anterior e nao voltam: McCC_i e NL_i sao zero em todas as
    // 9.018 linhas, e o padronizador as retira nomeando-as. Nao e escolha, e aritmetica.

    // -- tarefa: tamanho da mudanca. Substituto de qualidade medida, como em Yu e Chouchen --
    all.add(new Attribute("A4-01", "loc_iss", Kind.NUMERIC, Transform.LOG1P, PROPRIA_DUPLICADA,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "issue_features.loc_iss -- linhas Java da mudanca; rho 0,760 com additions_at_open"));
    all.add(new Attribute("A4-02", "pr_files", Kind.NUMERIC, Transform.LOG1P, PROPRIA_DUPLICADA,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "issue_features.pr_files -- rho 1,000 com num_files_at_open: e a MESMA variavel"));

    // -- o autor: historia acumulada, que tem data e por isso se demonstra --
    all.add(new Attribute("A4-03", "loc_tot_java", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, true, "issue_features.loc_tot_java -- volume acumulado antes de t0"));
    all.add(new Attribute("A4-04", "a4_loc_por_commit", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, true,
        "derivada: loc_tot_java / extra_dev_commits -- o tamanho tipico da entrega da pessoa. "
            + "Existe porque as duas pernas formavam contraste, nao dois preditores"));
    all.add(new Attribute("A4-05", "extra_dev_commits", Kind.NUMERIC, Transform.LOG1P, PROPRIA_DUPLICADA,
        TemporalClass.LIMPO, false,
        "issue_features.extra_dev_commits -- rho 0,772 com owner_prior_prs"));
    all.add(new Attribute("A4-06", "extra_dev_tenure_days", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false, "issue_features.extra_dev_tenure_days"));

    // -- familiaridade: o unico construto do desenvolvedor que nao e contagem acumulada
    //    disfarcada. A classe temporal dela e o que a sonda 45 esta medindo --
    all.add(new Attribute("A4-07", "pr_familiarity", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "issue_features.pr_familiarity -- MEDIDO recalculando com a lista de abertura contra a "
            + "final: rho 0,935, valor identico em 88,2%. O melhor substituto da grade"));
    all.add(new Attribute("A4-08", "pr_familiarity_share", Kind.NUMERIC, Transform.NONE, PROPRIA,
        TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "issue_features.pr_familiarity_share -- MEDIDO: rho 0,965, o mais alto da grade. Razao, "
            + "entao sem log1p"));

    // -- posicao formal no repositorio: um campo de valor unico, sem historico de mudanca --
    all.add(new Attribute("A4-09", "pr_assoc_member", Kind.BINARY, Transform.NONE, PROPRIA,
        TemporalClass.NAO_VERIFICAVEL_EM_T0, false,
        "pull_request.author_association -- referencia CONTRIBUTOR. O vinculo muda com o tempo e o "
            + "corpus guarda um estado so, sem data: mesmo padrao dos textuais de Yu"));
    all.add(new Attribute("A4-10", "pr_assoc_none", Kind.BINARY, Transform.NONE, PROPRIA,
        TemporalClass.NAO_VERIFICAVEL_EM_T0, false, "pull_request.author_association = NONE"));

    // -- carga --
    all.add(new Attribute("A4-11", "iss_dev", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false,
        "issue_features.iss_dev -- carga aberta da pessoa no projeto; rho 0,394 com C08"));
    all.add(new Attribute("A4-12", "iss_team", Kind.NUMERIC, Transform.LOG1P, PROPRIA_DUPLICADA,
        TemporalClass.LIMPO, false,
        "issue_features.iss_team -- rho 0,847 com recent_open_prs_project_30d"));

    // -- equipe ativa --
    all.add(new Attribute("A4-13", "mu_l", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, true,
        "issue_features.mu_l -- media do volume acumulado da equipe; ausente nas 180 sem equipe"));
    all.add(new Attribute("A4-14", "s_l", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, true, "issue_features.s_l -- desvio do mesmo; mesma ausencia"));
    all.add(new Attribute("A4-15", "extra_team_size", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false,
        "issue_features.extra_team_size -- rho 0,315 com team_size_90d: definicoes diferentes"));
    all.add(new Attribute("A4-16", "pr_project_wait", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false,
        "issue_features.pr_project_wait -- espera mediana ate o primeiro exame nos PRs do projeto "
            + "mesclados nos 90 dias anteriores. Janela estritamente anterior, e do projeto"));

    // -- criacao --
    all.add(new Attribute("A4-17", "extra_desc_length", Kind.NUMERIC, Transform.LOG1P, PROPRIA_DUPLICADA,
        TemporalClass.NAO_VERIFICAVEL_EM_T0, true,
        "issue_features.extra_desc_length -- caracteres da descricao; rho 0,953 com Y11, que conta "
            + "palavras e inclui o titulo"));

    // -- a mediana do par, que e coluna do estudo anterior e nao acrescimo deste trabalho.
    //    Calculada em tempo de ajuste por History, nunca no cache: depende de qual metade ajusta.
    //    Escala difere do original -- aqui mediana de horas, la mediana de logaritmos de dias --
    //    e a diferenca esta registrada, porque ordena igual mas nao vale o mesmo numero.
    all.add(new Attribute("A4-18", "creator_project_median_hours", Kind.NUMERIC, Transform.LOG1P,
        PROPRIA, TemporalClass.LIMPO, true,
        "desfechos anteriores do par pessoa-projeto, so do lado do ajuste -- o TTC mediano do par"));

    // -- derivada, e de familia NENHUMA: existe so para a ablacao do tamanho --------------------
    //    Linhas ALTERADAS, que e a soma de adicionadas e removidas. Nenhum dos tres artigos a
    //    declara -- eles contam as duas pernas em separado --, entao ela nao entra em cenario
    //    algum. Entra como adversario: a pergunta "os conjuntos da literatura, sem o tamanho,
    //    ganham de um modelo que e so o tamanho?" precisa que o tamanho seja UMA coluna, e a
    //    grandeza que um leitor chama de tamanho de uma mudanca e o total mexido, nao metade dele.
    all.add(new Attribute("ABL", "lines_changed_at_open", Kind.NUMERIC, Transform.LOG1P,
        Set.of(), TemporalClass.ESTAVEL_MEDIDO_ABERTURA_MERGE, false,
        "derivada: additions_at_open + deletions_at_open -- so para a ablacao, fora dos cenarios"));

    return List.copyOf(all);
  }

  /** Every construct, in declaration order, which is the order every report prints. */
  public static List<Attribute> all() {
    return ALL;
  }

  /** Whether a name is declared at all, so a loader can ask before it assumes. */
  public static boolean declares(final String name) {
    return BY_NAME.containsKey(name);
  }

  public static Attribute byName(final String name) {
    final Attribute found = BY_NAME.get(name);
    if (found == null) {
      throw new IllegalArgumentException("no such attribute: " + name);
    }
    return found;
  }

  /**
   * The constructs one cell of the grid fits on: a family crossed with a profile.
   *
   * <p>Stable order, so two runs produce the same design matrix and the same coefficient listing.
   */
  public static List<Attribute> of(final Family family, final Profile profile) {
    return ALL.stream().filter(attribute -> attribute.inCell(family, profile)).toList();
  }

  /** The constructs a profile admits, across every family. What the extractor has to produce. */
  public static List<Attribute> of(final Profile profile) {
    return ALL.stream().filter(attribute -> profile.admits(attribute.temporalClass())).toList();
  }
}
