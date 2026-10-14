package app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The design matrix's columns: what each is called, where it comes from, and how it is transformed.
 *
 * <p>Volumes and counts enter as {@code ln(1 + x)} and the two complexity means enter raw. That is
 * not a new decision — it is what the mongosh prototypes did, and keeping it identical is what
 * makes the Java results comparable to the numbers those prototypes reported (in the history of
 * {@code analysis/}, removed on 2026-10-14). The
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
    TASK, DEV, LOAD, TEAM, CREATION
  }

  /** The columns this run fits, in order. */
  public static List<Column> all() {
    return ALL;
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

  private Columns() {}

  private static List<Column> build() {
    final List<Column> columns = new ArrayList<>();
    columns.add(new Column("ln(task LOC)", "loc_iss", true, Group.TASK));
    columns.add(new Column("McCC_i", "mccc_i", false, Group.TASK));
    columns.add(new Column("NL_i", "nl_i", false, Group.TASK));

    columns.add(new Column("ln(dev cum. LOC)", "loc_tot_java", true, Group.DEV));
    columns.add(new Column("ln(dev commits)", "extra_dev_commits", true, Group.DEV));
    columns.add(new Column("ln(tenure)", "extra_dev_tenure_days", true, Group.DEV));
    columns.add(new Column("ln(dev McCC_tot)", "mccc_tot", true, Group.DEV));

    columns.add(new Column("ln(Iss_dev)", "iss_dev", true, Group.LOAD));
    columns.add(new Column("ln(Iss_team)", "iss_team", true, Group.LOAD));
    columns.add(new Column("ln(Iss_dev career)", "extra_iss_dev_career", true, Group.LOAD));

    columns.add(new Column("ln(mu_L)", "mu_l", true, Group.TEAM));
    columns.add(new Column("ln(s_L)", "s_l", true, Group.TEAM));
    columns.add(new Column("ln(mu_M)", "mu_m", true, Group.TEAM));
    columns.add(new Column("ln(s_M)", "s_m", true, Group.TEAM));
    columns.add(new Column("ln(team size)", "extra_team_size", true, Group.TEAM));

    columns.add(new Column("ln(desc. length)", "extra_desc_length", true, Group.CREATION));
    columns.add(new Column("description missing", "", false, Group.CREATION));
    columns.add(new Column("ln(linked issues)", "extra_linked_issues", true, Group.CREATION));
    columns.add(new Column("links missing", "", false, Group.CREATION));
    for (final String type : TYPES) {
      columns.add(new Column("type=" + type, "", false, Group.CREATION));
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
    return indexOf("ln(task LOC)");
  }

  /** The description-length column: the strongest creation-time predictor there is. */
  public static int descriptionLength() {
    return indexOf("ln(desc. length)");
  }

  /** The issue-type indicators; two tasks share a type when all of them agree. */
  public static int[] typeIndices() {
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < all().size(); i++) {
      if (all().get(i).name().startsWith("type=")) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * The thirteen columns the mongosh prototype fitted, so the Java result can be put beside the
   * numbers the prototype reported (in the history of {@code analysis/}). It carries no load and no
   * team statistics.
   */
  public static final List<String> PROTOTYPE = List.of(
      "ln(task LOC)", "McCC_i", "NL_i", "ln(desc. length)", "ln(linked issues)",
      "ln(dev cum. LOC)", "ln(dev commits)", "ln(tenure)",
      "type=Bug", "type=Improvement", "type=New Feature", "type=Sub-task", "type=Task");

  private static int indexOf(final String name) {
    for (int i = 0; i < all().size(); i++) {
      if (all().get(i).name().equals(name)) {
        return i;
      }
    }
    throw new IllegalStateException("no column named " + name);
  }
}
