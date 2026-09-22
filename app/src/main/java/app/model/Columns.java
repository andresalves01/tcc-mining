package app.model;

import app.labels.TtcTarget;
import app.paper4.PullRequestExtras;
import java.util.ArrayList;
import java.util.List;

/**
 * The design matrix's columns: what each is called, where it comes from, and how it is transformed.
 *
 * <p>Volumes and counts enter as {@code ln(1 + x)} and the two complexity means enter raw. That is
 * not a new decision — it is what the mongosh prototypes did, and keeping it identical is what
 * makes the Java results comparable to the numbers already reported in {@code analysis/}. The
 * standardisation the study specifies happens afterwards, on top of this.
 *
 * <p>Absence is carried as its own column rather than imputed silently. A description length the
 * corpus never recorded is not a description of length zero, and an indicator lets the fit decide
 * what absence is worth instead of the reader deciding for it.
 *
 * <p>The complexity columns are listed even while no per-commit table exists. They then arrive as
 * zero for every row, the standardiser finds them constant, and they are dropped <i>by name</i> in
 * the report — which is a louder statement of what is missing than leaving them out would be.
 */
public final class Columns {

  /** Which part of the model a column belongs to, for the ablation the study asks for. */
  public enum Group {
    TAREFA, DEV, CARGA, EQUIPE, CRIACAO,

    /**
     * Conhecido so na fronteira do exame: o estado da primeira revisao, quem examinou, a espera que
     * de fato houve. Legitimo como preditor do ciclo do autor, porque e passado em relacao ao
     * inicio daquele intervalo -- e inutil para formar equipe, porque nada disso existe no instante
     * em que se atribuiria a tarefa a alguem. Grupo proprio para a ablacao poder mostrar o modelo
     * com e sem, que e a unica forma honesta de reportar os dois fatos juntos.
     */
    EXAME
  }

  /**
   * Which column set a run uses. Set once at startup from configuration, never mid-run.
   *
   * <p>A global rather than a parameter because the column set is a property of the whole run, and
   * because {@link DeveloperOrdering} reaches columns by index into {@code Observation.values} --
   * threading a profile through that would mean carrying it into the pure comparison logic, which
   * reads no configuration today and should not start.
   *
   * <p>{@link #ISSUE} is the default and is exactly the list this class always had, so every issue
   * stage behaves identically to before profiles existed.
   */
  public enum Profile {

    /** The specification the thesis fitted. */
    ISSUE,

    /**
     * The same, minus four columns that earn nothing on pull-request labels and cost something.
     *
     * <p>{@code McCC_tot,j}, {@code mu_M} and {@code s_M} all come from the per-commit complexity
     * table, the most expensive stage in the pipeline -- seventeen minutes over 251,824 commits
     * against a 563-million-document collection. Measured alone on the post-review interval,
     * {@code McCC_tot} carries R^2 +0.002, and the thesis already measured it correlating 0.985
     * with accumulated lines. {@code mu_M} is the column that took -2.46 against {@code mu_L}'s
     * +2.62, two near-identical variables cancelling: the sweep chose alpha 0.95, which is nearly
     * pure lasso, so there is no ridge term left to spread weight between them the way keeping
     * both assumes.
     *
     * <p>{@code Iss_dev carreira} is dropped for a different reason: measured identical to
     * {@code Iss_dev} on every figure, to three decimals. Not expensive, simply the same variable
     * declared twice.
     *
     * <p>The nine columns the standardiser already drops as constant here -- task complexity, Jira
     * links, the type indicators -- stay declared. They are live predictors on issue labels, and
     * removing them from the specification to tidy this population's report would quietly change
     * the other study.
     */
    PULL_REQUEST
  }

  private static final List<String> ABSENT_ON_PULL_REQUESTS = List.of(
      "ln(McCC_tot dev)", "ln(mu_M)", "ln(s_M)", "ln(Iss_dev carreira)",
      "ln(commits dev)", "descricao ausente");

  /**
   * Volume acumulado dividido por commits acumulados: o tamanho tipico da mudanca que a pessoa
   * entrega. Derivada, nao lida de campo nenhum.
   *
   * <p>Existe porque as duas pernas de onde ela sai nao estavam medindo duas coisas. Ajustadas
   * juntas no intervalo pos-revisao, {@code ln(LOC acum. dev)} tomou +0,594 contra -1,003 de
   * {@code ln(commits dev)}: coeficientes de sinal oposto e magnitude parecida sao a assinatura de
   * um contraste, nao de dois preditores. Retirar so os commits levou o volume de +0,594 a -0,054 e
   * derrubou a ordenacao de desenvolvedores de 61,0% para 54,3%, o que confirma de onde vinha o
   * sinal. Declarar a razao diretamente diz a mesma coisa com um nome que se pode ler.
   */
  public static final String LINES_PER_COMMIT = "ln(LOC por commit dev)";

  /**
   * A duracao mediana passada daquele par (pessoa, projeto), como-de a abertura.
   *
   * <p>Derivada dos desfechos do lado do ajuste, nao de um campo de {@code issue_features} -- ver
   * {@link PairHistory}, que e quem garante que ela seja estritamente anterior e nunca toque a
   * particao que esta sendo pontuada. Ja entra em escala de logaritmo, porque e uma mediana de
   * logaritmos: aplicar {@code ln(1+x)} nela de novo seria logaritmo de logaritmo.
   */
  public static final String PAIR_MEDIAN = "TTC mediano do par (passado)";

  private static volatile Profile active = Profile.ISSUE;
  private static volatile TtcTarget target = TtcTarget.TOTAL;

  /** Chosen once, in {@code App}, before any adapter reads a row. */
  public static void use(final Profile profile) {
    active = profile;
  }

  /**
   * Which duration the run fits, because it decides whether the examination columns are observable.
   *
   * <p>Not a preference. {@link Group#EXAME} carries things that become true partway through the
   * pull request's life, so for {@link TtcTarget#TOTAL} and {@link TtcTarget#ESPERA} they are not
   * predictors at all -- they are the outcome, or part of it. Measured: fitted against
   * {@code ESPERA}, the realised wait took coefficient 1.49 while every other column went to zero
   * and the rank correlation hit 1.000, because {@code exame_espera_realizada} <i>is</i>
   * {@code ttc_espera}. Against {@code TOTAL} it took 0.762, for the milder version of the same
   * reason: the total is the two parts added up. Only the cycle after the examination has them
   * strictly in its past.
   */
  public static void target(final TtcTarget chosen) {
    target = chosen;
  }

  public static Profile profile() {
    return active;
  }

  /** The columns this run fits, in order. */
  public static List<Column> all() {
    if (active != Profile.PULL_REQUEST) {
      return ALL;
    }
    return target == TtcTarget.AUTOR || target == TtcTarget.AUTOR_UTIL
        ? PULL_REQUEST_COLUMNS : PULL_REQUEST_WITHOUT_EXAM;
  }

  /**
   * @param field the key in {@code issue_features}, or empty for a column derived some other way
   * @param log whether the value enters as {@code ln(1 + x)}
   */
  public record Column(String name, String field, boolean log, Group group) {}

  /** The issue types the prototypes used as indicators, in that order. */
  public static final List<String> TYPES =
      List.of("Bug", "Improvement", "New Feature", "Sub-task", "Task");

  public static final List<Column> ALL = build();

  private static final List<Column> PULL_REQUEST_COLUMNS = pullRequestColumns();

  /** The same list minus everything that only becomes known once someone has looked. */
  private static final List<Column> PULL_REQUEST_WITHOUT_EXAM = PULL_REQUEST_COLUMNS.stream()
      .filter(column -> column.group() != Group.EXAME)
      .toList();

  /**
   * O perfil de pull request e construido a partir de {@link #ALL}, nunca gravado nele: uma coluna
   * acrescentada em {@code build()} apareceria tambem no perfil ISSUE e mudaria o estudo do TCC sem
   * ninguem pedir.
   */
  private static List<Column> pullRequestColumns() {
    final List<Column> columns = new ArrayList<>();
    for (final Column column : ALL) {
      if (ABSENT_ON_PULL_REQUESTS.contains(column.name())) {
        continue;
      }
      columns.add(column);
      if (column.name().equals("ln(LOC acum. dev)")) {
        columns.add(new Column(LINES_PER_COMMIT, "", true, Group.DEV));
        columns.add(new Column("ln(familiaridade arq.)",
            PullRequestExtras.FAMILIARITY, true, Group.DEV));
        columns.add(new Column("fracao arq. conhecidos",
            PullRequestExtras.FAMILIARITY_SHARE, false, Group.DEV));
      }
      if (column.name().equals("ln(LOC tarefa)")) {
        columns.add(new Column("ln(arquivos tarefa)", PullRequestExtras.FILES, true, Group.TAREFA));
      }
      if (column.name().equals("ln(tempo de casa)")) {
        columns.add(new Column("posicao=MEMBER", PullRequestExtras.ASSOC_MEMBER, false, Group.DEV));
        columns.add(new Column("posicao=NONE", PullRequestExtras.ASSOC_NONE, false, Group.DEV));
        columns.add(new Column(PAIR_MEDIAN, "", false, Group.DEV));
      }
      if (column.name().equals("ln(tam. equipe)")) {
        columns.add(new Column("ln(espera tipica do projeto)",
            PullRequestExtras.PROJECT_WAIT, true, Group.EQUIPE));
      }
    }
    columns.add(new Column("1a revisao pediu mudanca",
        PullRequestExtras.EXAM_CHANGES_REQUESTED, false, Group.EXAME));
    columns.add(new Column("1a revisao so comentou",
        PullRequestExtras.EXAM_COMMENTED, false, Group.EXAME));
    columns.add(new Column("exame foi comentario, nao revisao",
        PullRequestExtras.EXAM_BY_COMMENT, false, Group.EXAME));
    columns.add(new Column("ln(LOC acum. de quem examinou)",
        PullRequestExtras.REVIEWER_VOLUME, true, Group.EXAME));
    // Os construtos do autor, espelhados em quem examinou. Ver PullRequestExtras: entram por
    // coerencia de especificacao, nao por desempenho, e podem piora-lo.
    columns.add(new Column("ln(familiaridade de quem examinou)",
        PullRequestExtras.REVIEWER_FAMILIARITY, true, Group.EXAME));
    columns.add(new Column("fracao arq. conhecidos por quem examinou",
        PullRequestExtras.REVIEWER_FAMILIARITY_SHARE, false, Group.EXAME));
    columns.add(new Column("ln(LOC por commit de quem examinou)",
        PullRequestExtras.REVIEWER_LINES_PER_COMMIT, true, Group.EXAME));
    columns.add(new Column("ln(tempo de casa de quem examinou)",
        PullRequestExtras.REVIEWER_TENURE, true, Group.EXAME));
    columns.add(new Column("quem examinou e MEMBER",
        PullRequestExtras.REVIEWER_ASSOC_MEMBER, false, Group.EXAME));
    columns.add(new Column("ln(espera realizada)",
        PullRequestExtras.REALISED_WAIT, true, Group.EXAME));
    return List.copyOf(columns);
  }

  private Columns() {}

  private static List<Column> build() {
    final List<Column> columns = new ArrayList<>();
    columns.add(new Column("ln(LOC tarefa)", "loc_iss", true, Group.TAREFA));
    columns.add(new Column("McCC_i", "mccc_i", false, Group.TAREFA));
    columns.add(new Column("NL_i", "nl_i", false, Group.TAREFA));

    columns.add(new Column("ln(LOC acum. dev)", "loc_tot_java", true, Group.DEV));
    columns.add(new Column("ln(commits dev)", "extra_dev_commits", true, Group.DEV));
    columns.add(new Column("ln(tempo de casa)", "extra_dev_tenure_days", true, Group.DEV));
    columns.add(new Column("ln(McCC_tot dev)", "mccc_tot", true, Group.DEV));

    columns.add(new Column("ln(Iss_dev)", "iss_dev", true, Group.CARGA));
    columns.add(new Column("ln(Iss_team)", "iss_team", true, Group.CARGA));
    columns.add(new Column("ln(Iss_dev carreira)", "extra_iss_dev_career", true, Group.CARGA));

    columns.add(new Column("ln(mu_L)", "mu_l", true, Group.EQUIPE));
    columns.add(new Column("ln(s_L)", "s_l", true, Group.EQUIPE));
    columns.add(new Column("ln(mu_M)", "mu_m", true, Group.EQUIPE));
    columns.add(new Column("ln(s_M)", "s_m", true, Group.EQUIPE));
    columns.add(new Column("ln(tam. equipe)", "extra_team_size", true, Group.EQUIPE));

    columns.add(new Column("ln(tam. descricao)", "extra_desc_length", true, Group.CRIACAO));
    columns.add(new Column("descricao ausente", "", false, Group.CRIACAO));
    columns.add(new Column("ln(issues ligadas)", "extra_linked_issues", true, Group.CRIACAO));
    columns.add(new Column("ligacoes ausentes", "", false, Group.CRIACAO));
    for (final String type : TYPES) {
      columns.add(new Column("tipo=" + type, "", false, Group.CRIACAO));
    }
    return List.copyOf(columns);
  }

  public static List<String> names() {
    return all().stream().map(Column::name).toList();
  }

  /** The indices belonging to {@code groups}, for fitting a restricted specification. */
  public static int[] indicesOf(final Group... groups) {
    final List<Group> wanted = List.of(groups);
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < all().size(); i++) {
      if (wanted.contains(all().get(i).group())) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }

  /** The task-size column, which pairs of developers are matched on. */
  public static int taskSize() {
    return indexOf("ln(LOC tarefa)");
  }

  /** The description-length column: the strongest creation-time predictor there is. */
  public static int descriptionLength() {
    return indexOf("ln(tam. descricao)");
  }

  /** The issue-type indicators; two tasks share a type when all of them agree. */
  public static int[] typeIndices() {
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < all().size(); i++) {
      if (all().get(i).name().startsWith("tipo=")) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * The thirteen columns the mongosh prototype fitted, so the Java result can be put beside the
   * numbers already reported in {@code analysis/}. It carries no load and no team statistics.
   */
  public static final List<String> PROTOTYPE = List.of(
      "ln(LOC tarefa)", "McCC_i", "NL_i", "ln(tam. descricao)", "ln(issues ligadas)",
      "ln(LOC acum. dev)", "ln(commits dev)", "ln(tempo de casa)",
      "tipo=Bug", "tipo=Improvement", "tipo=New Feature", "tipo=Sub-task", "tipo=Task");

  private static int indexOf(final String name) {
    for (int i = 0; i < all().size(); i++) {
      if (all().get(i).name().equals(name)) {
        return i;
      }
    }
    throw new IllegalStateException("no column named " + name);
  }
}
