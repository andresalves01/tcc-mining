package app.model;

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
  public enum Group { TAREFA, DEV, CARGA, EQUIPE, CRIACAO }

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
      "ln(McCC_tot dev)", "ln(mu_M)", "ln(s_M)", "ln(Iss_dev carreira)");

  private static volatile Profile active = Profile.ISSUE;

  /** Chosen once, in {@code App}, before any adapter reads a row. */
  public static void use(final Profile profile) {
    active = profile;
  }

  public static Profile profile() {
    return active;
  }

  /** The columns this run fits, in order. */
  public static List<Column> all() {
    return active == Profile.PULL_REQUEST ? PULL_REQUEST_COLUMNS : ALL;
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

  private static final List<Column> PULL_REQUEST_COLUMNS = ALL.stream()
      .filter(column -> !ABSENT_ON_PULL_REQUESTS.contains(column.name()))
      .toList();

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
