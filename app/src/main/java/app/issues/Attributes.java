package app.issues;

import static app.study.Construct.Kind.BINARY;
import static app.study.Construct.Kind.NUMERIC;
import static app.study.Construct.Transform.LOG1P;
import static app.study.Construct.Transform.NONE;
import static app.issues.Family.ALZUBAIDI;
import static app.issues.Family.KIKAS;
import static app.issues.Family.OWN;
import static app.issues.Scenario.Block.CONTENT;
import static app.issues.Scenario.Block.EXPOSURE;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The registry: every column a model can read, as {@code MATRIZ-VIABILIDADE-ISSUES.csv} declares it
 * after the author's decisions of 29/09/2026.
 *
 * <p>Out by decision: Z11 (the second title measure), A08 (type in the own family), K01 (text
 * score), A18 (repository metrics). Out by the corpus: K09 (subscribers). Out because they carry no
 * signal at creation: the C1 versions of Kikas's dynamic counts and the initial links. A15w (the
 * whole commit) is registered for the sensitivity and enters no cell of the grid.
 *
 * <p>Counts and volumes enter as {@code log1p}; ratios, fog, means of small integers and indicators
 * raw. A column that can be absent carries an absence indicator, so absence is never read as zero.
 */
public final class Attributes {

  private static final List<Attribute> ALL = build();

  /** Registered for the sensitivity with the whole commit; in no cell of the grid. */
  public static final String SENSITIVITY_A15W = "A15w_change_loc_whole";

  private Attributes() {}

  private static Attribute c1(final String id, final String name, final Family family,
      final app.study.Construct.Kind kind, final app.study.Construct.Transform t,
      final boolean absent) {
    return new Attribute(id, name, family, null, kind, t, absent);
  }

  private static Attribute c2(final String id, final String name, final Family family,
      final Scenario.Block block, final app.study.Construct.Transform t, final boolean absent) {
    return new Attribute(id, name, family, block, NUMERIC, t, absent);
  }

  private static List<Attribute> build() {
    final List<Attribute> a = new ArrayList<>();
    // ---------------------------------------------------------------- Al-Zubaidi (C2 = C1)
    int k = 1;
    for (final String type : Extractor.AZ_TYPES) {
      a.add(c1("Z0" + k++, "Z_type_" + type, ALZUBAIDI, BINARY, NONE, false));
    }
    a.add(c1("Z08", "Z08_priority", ALZUBAIDI, NUMERIC, NONE, true));
    a.add(c1("Z09", "Z09_reputation", ALZUBAIDI, NUMERIC, NONE, true));
    a.add(c1("Z10", "TXT_title_words", ALZUBAIDI, NUMERIC, LOG1P, false));
    a.add(c1("Z12", "TXT_desc_words", ALZUBAIDI, NUMERIC, LOG1P, true));
    a.add(c1("Z13", "TXT_desc_fog", ALZUBAIDI, NUMERIC, NONE, true));

    // ---------------------------------------------------------------- Kikas parcial, C1
    a.add(c1("K02", "K02_body_len", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K06", "LBL_count_c1", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K11", "K11_creator_issues_90d", KIKAS, NUMERIC, LOG1P, true));
    a.add(c1("K12", "K12_creator_issues_closed_90d", KIKAS, NUMERIC, LOG1P, true));
    a.add(c1("K13", "K13_creator_commits_90d", KIKAS, NUMERIC, LOG1P, true));
    a.add(c1("K13x", "K13x_reporter_no_commit_ever", KIKAS, BINARY, NONE, false));
    a.add(c1("K14", "K14_project_issues_90d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K15", "K15_project_issues_closed_90d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K16", "K16_project_commits_90d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K17", "K17_project_issues_14d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K18", "K18_project_created_closed_14d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K19", "K19_project_commits_14d", KIKAS, NUMERIC, LOG1P, false));
    a.add(c1("K20", "K20c1_participant_commits_14d", KIKAS, NUMERIC, LOG1P, true));
    a.add(c1("K21", "K21c1_participant_committers_14d", KIKAS, NUMERIC, NONE, true));
    // ---------------------------------------------------------------- Kikas parcial, C2 (exposure)
    a.add(c2("K03", "K03_comments", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K04", "K04_actors", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K05", "K05_assignments", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K06", "K06_labels_c2", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K07", "K07_mentions", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K08", "K08_commit_refs", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K10", "K10_mean_comment_words", KIKAS, EXPOSURE, LOG1P, true));
    a.add(c2("K17", "K17c2_project_issues_14d_obs", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K18", "K18c2_project_created_closed_14d_obs", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K19", "K19c2_project_commits_14d_obs", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K20", "K20_participant_commits", KIKAS, EXPOSURE, LOG1P, false));
    a.add(c2("K21", "K21_participant_committers", KIKAS, EXPOSURE, LOG1P, false));

    // ---------------------------------------------------------------- own, C1
    a.add(c1("A01", "A01_desc_length", OWN, NUMERIC, LOG1P, true));
    a.add(c1("A03", "A03_team_size", OWN, NUMERIC, LOG1P, false));
    a.add(c1("A04", "A04_mu_l", OWN, NUMERIC, LOG1P, true));
    a.add(c1("A05", "A05_s_l", OWN, NUMERIC, LOG1P, true));
    a.add(c1("A06", "A06_mu_m", OWN, NUMERIC, LOG1P, true));
    a.add(c1("A07", "A07_s_m", OWN, NUMERIC, LOG1P, true));
    a.add(c1("A06c", "A06c_team_mccc_coverage", OWN, NUMERIC, NONE, true));
    a.add(c1("D01", "D01_backlog_open", OWN, NUMERIC, LOG1P, false));
    a.add(c1("D03", "D03_project_first_interaction_median_h", OWN, NUMERIC, LOG1P, true));
    a.add(c1("D05", "D05_reporter_project_median_h", OWN, NUMERIC, LOG1P, true));
    // ---------------------------------------------------------------- own, C2 content
    a.add(c2("A09", "A09_exec_loc_tot", OWN, CONTENT, LOG1P, true));
    a.add(c2("A10", "A10_exec_mccc_tot", OWN, CONTENT, LOG1P, true));
    a.add(c2("A10c", "A10c_exec_mccc_coverage", OWN, CONTENT, NONE, true));
    a.add(c2("A11", "A11_exec_commits", OWN, CONTENT, LOG1P, true));
    a.add(c2("A12", "A12_exec_tenure_days", OWN, CONTENT, LOG1P, true));
    a.add(c2("A13", "A13_exec_open_load", OWN, CONTENT, LOG1P, true));
    a.add(c2("A14", "A14_team_open_load", OWN, CONTENT, LOG1P, false));
    a.add(c2("A15", "A15_change_loc", OWN, CONTENT, LOG1P, true));
    a.add(c2("A16", "A16_change_mean_mccc", OWN, CONTENT, NONE, true));
    a.add(c2("A17", "A17_change_mean_nl", OWN, CONTENT, NONE, true));
    a.add(c2("D07", "D07_files_changed", OWN, CONTENT, LOG1P, true));
    a.add(c2("D08", "D08_file_familiarity", OWN, CONTENT, NONE, true));
    // ---------------------------------------------------------------- own, C2 exposure
    a.add(c2("A02c2", "A02c2_links_before_res", OWN, EXPOSURE, LOG1P, false));
    a.add(c2("D06", "D06_first_interaction_h", OWN, EXPOSURE, LOG1P, true));
    return List.copyOf(a);
  }

  public static List<Attribute> all() {
    return ALL;
  }

  /**
   * The executor's columns, which the secondary study reads at t0 for the person known then: they
   * join C1 there (P10), while the rest of the content block stays after the fact.
   */
  public static final Set<String> EXECUTOR_AT_START = Set.of("A09_exec_loc_tot",
      "A10_exec_mccc_tot", "A10c_exec_mccc_coverage", "A11_exec_commits", "A12_exec_tenure_days",
      "A13_exec_open_load");

  /** P20: the time to the first interaction, which at the first In Progress has mostly passed. */
  public static final String OUT_AT_START = "D06_first_interaction_h";

  /**
   * The columns of one cell of the secondary study: C1 plus the executor at t0, and the blocks the
   * scenario admits (P19), the content block without the executor's columns, the exposure block
   * without D06 (P20).
   */
  public static List<Attribute> ofInProgress(final Family family, final Scenario scenario) {
    return ALL.stream().filter(a -> {
      if (a.family() != family || a.name().equals(OUT_AT_START)) {
        return false;
      }
      if (a.atCreation() || EXECUTOR_AT_START.contains(a.name())) {
        return true;
      }
      return scenario.admits(a.block());
    }).toList();
  }

  public static Attribute byName(final String name) {
    return ALL.stream().filter(a -> a.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no attribute " + name));
  }

  /** Every column the cache must carry: values, absence indicators, and the sensitivity A15w. */
  public static List<String> stored() {
    final Set<String> out = new LinkedHashSet<>();
    for (final Attribute a : ALL) {
      out.add(a.name());
      if (a.absenceIndicator()) {
        out.add(a.absenceName());
      }
    }
    out.add(SENSITIVITY_A15W);
    out.add(SENSITIVITY_A15W + "_ausente");
    return List.copyOf(out);
  }
}
