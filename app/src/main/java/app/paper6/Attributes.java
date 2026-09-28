package app.paper6;

import app.paper5.Attribute.Kind;
import app.paper5.Attribute.Transform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every construct of the transposition, declared once.
 *
 * <p>What changed from the pull-request study, construct by construct, is in
 * {@code MATRIZ-ATRIBUTOS-ISSUES.csv}, and {@code AttributesTest} checks the two agree. In short:
 *
 * <ul>
 *   <li>the person Yu and Chouchen describe is whoever opened the item, which for an issue is
 *       {@code issue.reporter_id};
 *   <li>"merged" becomes "resolved" -- the first change of status to {@code Resolved} or
 *       {@code Closed}, the same rule the issue labels were built with -- and "integrator" becomes
 *       whoever made that change;
 *   <li>the text is read <b>as it was at creation</b>, from the changelog, not as it ended;
 *   <li>the size of the change does not exist at creation. Additions, deletions and commits come
 *       back only in the ceiling profile, from the change that resolved the issue. Files touched,
 *       tests touched and area hotness do not come back at all: the issue label keeps no list of
 *       files.
 * </ul>
 */
public final class Attributes {

  private static final Set<Family> YU =
      Set.of(Family.YU_ISSUE, Family.COMBINADO_LITERATURA, Family.COMBINADO_TOTAL);
  private static final Set<Family> CHOUCHEN =
      Set.of(Family.CHOUCHEN_ISSUE, Family.COMBINADO_LITERATURA, Family.COMBINADO_TOTAL);
  private static final Set<Family> BOTH = Set.of(Family.YU_ISSUE, Family.CHOUCHEN_ISSUE,
      Family.COMBINADO_LITERATURA, Family.COMBINADO_TOTAL);
  private static final Set<Family> YU_ONLY = Set.of(Family.YU_ISSUE);
  private static final Set<Family> PROPRIA = Set.of(Family.ESPEC_PROPRIA, Family.COMBINADO_TOTAL);
  private static final Set<Family> TRIAGEM = Set.of(Family.TRIAGEM_JIRA, Family.COMBINADO_TOTAL);
  private static final Set<Family> TRIAGEM_E_PROPRIA =
      Set.of(Family.TRIAGEM_JIRA, Family.ESPEC_PROPRIA, Family.COMBINADO_TOTAL);

  /**
   * The description length, in both specifications that have it, and left out of the total union.
   *
   * <p>The union already reads the text through Yu's word count of title plus description, and in
   * the pull-request study the two measured rank correlation 0,953. Two columns for one construct
   * add nothing to a union whose point is to add approaches, not to arbitrate between two measures
   * of the same thing -- so the union keeps the literature's, as it did there.
   */
  private static final Set<Family> TAMANHO_DA_DESCRICAO =
      Set.of(Family.TRIAGEM_JIRA, Family.ESPEC_PROPRIA);

  private static final List<Attribute> ALL = build();

  private static final Map<String, Attribute> BY_NAME = ALL.stream()
      .collect(LinkedHashMap::new, (map, a) -> map.put(a.name(), a), Map::putAll);

  private Attributes() {}

  private static List<Attribute> build() {
    final List<Attribute> all = new ArrayList<>();

    // -- projeto e momento ---------------------------------------------------------------------
    all.add(new Attribute("Y01", "project_age_days", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false,
        "commit.committer_date; issue.created_at -- desde min(primeiro commit, primeira issue)"));
    all.add(new Attribute("Y02", "team_size_90d", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false,
        "event (status -> Resolved/Closed) -- REDEFINIDO: quem resolveu issue do projeto nos 90 "
            + "dias, no lugar de quem mesclou PR"));
    all.add(new Attribute("C01", "created_weekday_utc", Kind.CATEGORICAL, Transform.NONE, CHOUCHEN,
        TemporalClass.LIMPO, false, "issue.created_at"));
    all.add(new Attribute("C02", "created_hour_utc", Kind.CATEGORICAL, Transform.NONE, CHOUCHEN,
        TemporalClass.LIMPO, false, "issue.created_at"));
    all.add(new Attribute("Y15", "friday_effect", Kind.BINARY, Transform.NONE, YU_ONLY,
        TemporalClass.LIMPO, false,
        "issue.created_at -- fora das unioes: contido em created_weekday_utc"));

    // -- carga do projeto ---------------------------------------------------------------------
    all.add(new Attribute("Y12", "open_issues_project_at_open", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false,
        "issue; event -- issues do projeto criadas antes de t0 e nao resolvidas antes de t0"));
    all.add(new Attribute("C10", "recent_open_issues_project_30d", Kind.NUMERIC, Transform.LOG1P,
        CHOUCHEN, TemporalClass.LIMPO, false, "issue; event -- as mesmas, criadas nos 30 dias"));

    // -- o relator, por reporter_id -------------------------------------------------------------
    all.add(new Attribute("C07", "reporter_prior_issues", Kind.NUMERIC, Transform.LOG1P, CHOUCHEN,
        TemporalClass.LIMPO, false, "issue.reporter_id (todos os projetos)"));
    all.add(new Attribute("C08", "reporter_prior_project_issues", Kind.NUMERIC, Transform.LOG1P,
        CHOUCHEN, TemporalClass.LIMPO, false, "issue.reporter_id (mesmo projeto)"));
    all.add(new Attribute("Y08", "reporter_resolved_ratio", Kind.NUMERIC, Transform.NONE, BOTH,
        TemporalClass.LIMPO, true,
        "issue; event -- REDEFINIDO: fracao das issues anteriores do relator resolvidas antes de "
            + "t0, no lugar da fracao de PRs mesclados. Sem issue anterior: ausente"));
    all.add(new Attribute("Y09", "reporter_is_resolver", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.LIMPO, false,
        "event.author_id -- REDEFINIDO: o relator ja resolveu alguma issue deste projeto antes de "
            + "t0, no lugar de 'e integrador'"));
    all.add(new Attribute("Y10", "social_connection_90d", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, true,
        "issue_comment -- contatos entre o relator e quem resolveu issue do projeto nos 90 dias. "
            + "Ninguem resolveu nada na janela: ausente"));
    all.add(new Attribute("Y16", "project_age_x_team_size", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "derivado de Y01 e Y02"));
    all.add(new Attribute("Y17", "team_size_x_workload", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.LIMPO, false, "derivado de Y02 e Y12"));

    // -- texto, como estava na criacao ----------------------------------------------------------
    all.add(new Attribute("Y11", "title_description_word_count", Kind.NUMERIC, Transform.LOG1P,
        BOTH, TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "issue.title + issue.desc revertidos pelo changelog ate t0"));
    all.add(new Attribute("Y13", "issue_reference", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "texto em t0 -- #n ou chave Jira de OUTRA issue (a propria chave nao conta)"));
    all.add(new Attribute("Y14", "person_mention", Kind.BINARY, Transform.NONE, YU,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "texto em t0 -- @nome, ou [~nome], que e como o Jira marca mencao"));

    // -- triagem: o que so a issue tem ----------------------------------------------------------
    all.add(new Attribute("T01", "priority_at_open", Kind.CATEGORICAL, Transform.NONE, TRIAGEM,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "issue.priority revertido -- 1 Blocker, 2 Critical, 3 Major, 4 Minor, 5 Trivial, 0 sem"));
    all.add(new Attribute("T02", "issue_type_at_open", Kind.CATEGORICAL, Transform.NONE,
        TRIAGEM_E_PROPRIA, TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "issue.issue_type revertido -- 1 Bug, 2 Improvement, 3 New Feature, 4 Sub-task, 5 Task, "
            + "0 outro. E a mesma codificacao das cinco indicadoras da especificacao propria"));
    all.add(new Attribute("T03", "components_at_open", Kind.NUMERIC, Transform.LOG1P, TRIAGEM,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false, "issue.components revertido: contagem"));
    all.add(new Attribute("T04", "labels_at_open", Kind.NUMERIC, Transform.LOG1P, TRIAGEM,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false, "issue.labels revertido: contagem"));
    all.add(new Attribute("T05", "affects_versions_at_open", Kind.NUMERIC, Transform.LOG1P,
        TRIAGEM, TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "issue.affects_versions revertido: contagem"));
    all.add(new Attribute("T06", "fix_versions_at_open", Kind.NUMERIC, Transform.LOG1P, TRIAGEM,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false,
        "issue.fix_versions revertido: contagem -- a versao-alvo que ja estava marcada na criacao"));
    all.add(new Attribute("T07", "linked_issues_at_open", Kind.NUMERIC, Transform.LOG1P,
        TRIAGEM_E_PROPRIA, TemporalClass.RECONSTRUIDO_DO_CHANGELOG, true,
        "issue.issue_links revertido: contagem. Sem o campo no corpus: ausente -- e o 'ligacoes "
            + "ausentes' da especificacao propria"));
    all.add(new Attribute("T08", "has_assignee_at_open", Kind.BINARY, Transform.NONE, TRIAGEM,
        TemporalClass.RECONSTRUIDO_DO_CHANGELOG, false, "issue.assignee_id revertido"));
    all.add(new Attribute("T09", "description_length_at_open", Kind.NUMERIC, Transform.LOG1P,
        TAMANHO_DA_DESCRICAO, TemporalClass.RECONSTRUIDO_DO_CHANGELOG, true,
        "issue.desc revertido: caracteres. Sem descricao: ausente -- e o 'descricao ausente' da "
            + "especificacao propria. Fora da uniao total: Y11 ja le o texto la"));

    // -- a especificacao propria: lida de smartshark_tcc.issue_features -------------------------
    //    Equipe e quem fez commit no projeto nos 90 dias antes da criacao, sem acrescentar o autor
    //    da issue -- entao as estatisticas de equipe nao dependem de quem fez o trabalho.
    all.add(new Attribute("P01", "mu_l", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, true,
        "issue_features.mu_l -- media do volume acumulado da equipe. Equipe vazia (553): ausente, "
            + "e este indicador e o de equipe vazia para as quatro estatisticas"));
    all.add(new Attribute("P02", "s_l", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false, "issue_features.s_l"));
    all.add(new Attribute("P03", "mu_m", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false, "issue_features.mu_m"));
    all.add(new Attribute("P04", "s_m", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false, "issue_features.s_m"));
    all.add(new Attribute("P05", "extra_team_size", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.LIMPO, false, "issue_features.extra_team_size"));

    // -- a especificacao propria: a pessoa que fez o trabalho --------------------------------------
    all.add(new Attribute("P06", "loc_tot_java", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false,
        "issue_features.loc_tot_java -- volume acumulado do autor dos commits ligados"));
    all.add(new Attribute("P07", "extra_dev_commits", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false, "issue_features.extra_dev_commits"));
    all.add(new Attribute("P08", "extra_dev_tenure_days", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false, "issue_features.extra_dev_tenure_days"));
    all.add(new Attribute("P09", "mccc_tot", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false, "issue_features.mccc_tot"));
    all.add(new Attribute("P10", "iss_dev", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false,
        "issue_features.iss_dev -- issues abertas do autor no projeto"));
    all.add(new Attribute("P11", "extra_iss_dev_career", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false, "issue_features.extra_iss_dev_career"));
    all.add(new Attribute("P12", "iss_team", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.CONDICIONADO_A_ATRIBUICAO, false,
        "issue_features.iss_team -- issues abertas da equipe, atribuidas a cada membro pelos "
            + "commits que vieram depois: em t0 ninguem sabe de quem e o trabalho aberto"));

    // -- a mudanca que resolveu a issue: so no teto ---------------------------------------------
    all.add(new Attribute("P13", "loc_iss", Kind.NUMERIC, Transform.LOG1P, PROPRIA,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false, "issue_features.loc_iss"));
    all.add(new Attribute("P14", "mccc_i", Kind.NUMERIC, Transform.NONE, PROPRIA,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false,
        "issue_features.mccc_i -- media, entra crua como na especificacao"));
    all.add(new Attribute("P15", "nl_i", Kind.NUMERIC, Transform.NONE, PROPRIA,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false, "issue_features.nl_i"));
    all.add(new Attribute("Y03", "additions_linked", Kind.NUMERIC, Transform.LOG1P, BOTH,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false,
        "issue_label.java_lines_added -- linhas Java dos commits ligados; o PR tinha isto na "
            + "abertura, a issue so no fim"));
    all.add(new Attribute("Y04", "deletions_linked", Kind.NUMERIC, Transform.LOG1P, BOTH,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false, "issue_label.java_lines_deleted"));
    all.add(new Attribute("Y05", "commits_linked", Kind.NUMERIC, Transform.LOG1P, YU,
        TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO, false, "issue_label.commits"));

    return List.copyOf(all);
  }

  public static List<Attribute> all() {
    return ALL;
  }

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

  /** One cell of the grid: a family under a profile, in the registry's stable order. */
  public static List<Attribute> of(final Family family, final Profile profile) {
    return ALL.stream().filter(attribute -> attribute.inCell(family, profile)).toList();
  }

  /** Every name a stored row carries: each construct, and each declared absence indicator. */
  public static List<String> stored() {
    final List<String> names = new ArrayList<>();
    for (final Attribute attribute : ALL) {
      names.add(attribute.name());
      if (attribute.absenceIndicator()) {
        names.add(attribute.absenceName());
      }
    }
    return List.copyOf(names);
  }
}
