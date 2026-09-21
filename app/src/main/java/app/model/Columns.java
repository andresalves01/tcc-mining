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
   * @param field the key in {@code issue_features}, or empty for a column derived some other way
   * @param log whether the value enters as {@code ln(1 + x)}
   */
  public record Column(String name, String field, boolean log, Group group) {}

  /** The issue types the prototypes used as indicators, in that order. */
  public static final List<String> TYPES =
      List.of("Bug", "Improvement", "New Feature", "Sub-task", "Task");

  public static final List<Column> ALL = build();

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
    return ALL.stream().map(Column::name).toList();
  }

  /** The indices belonging to {@code groups}, for fitting a restricted specification. */
  public static int[] indicesOf(final Group... groups) {
    final List<Group> wanted = List.of(groups);
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < ALL.size(); i++) {
      if (wanted.contains(ALL.get(i).group())) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * The columns two tasks must be alike in before their developers are compared.
   *
   * <p>Size and description length: the two strongest task-side predictors there are. Leaving
   * either unmatched lets the model answer with the task when the question was about the person.
   */
  public static int[] comparableTask() {
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < ALL.size(); i++) {
      final String name = ALL.get(i).name();
      if (name.equals("ln(LOC tarefa)") || name.equals("ln(tam. descricao)")) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }
}
