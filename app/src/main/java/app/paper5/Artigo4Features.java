package app.paper5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The previous paper's own predictors, read rather than recomputed.
 *
 * <p>{@code smartshark_paper4.issue_features} already holds every one of these for all 9.018 units,
 * computed by the stage that produced that study. Joining by {@code _id} matches 9.018 of 9.018 with
 * no orphans, so this class reads a flat export of that collection and never re-mines anything. That
 * matters beyond cost: recomputing them here would produce a second definition of the same column,
 * and two definitions of one thing eventually disagree.
 *
 * <p>Two columns of that specification are <b>declared dead</b> and are not read: the complexity and
 * nesting of the change are zero on all 9.018 rows, so the standardiser would drop them by name
 * anyway. That is arithmetic, not a choice about what belongs in the model.
 *
 * <p>One column is <b>derived</b> here and not stored there: the typical size of a person's
 * delivery, which is accumulated volume over accumulated commits. It exists because the two legs
 * were not measuring two things -- fitted together they took opposite signs of similar magnitude,
 * which is the signature of a contrast rather than of two predictors.
 *
 * <p>Absence is kept apart from zero throughout, as everywhere else in this study. A developer the
 * ledger never saw has no accumulated volume -- not a volume of zero -- and a pull request opened
 * into a project with nobody active has no team statistics.
 */
public final class Artigo4Features {

  /** Read straight from the export, under the same names the specification uses. */
  private static final List<String> STORED = List.of(
      "loc_iss", "pr_files", "loc_tot_java", "extra_dev_commits", "extra_dev_tenure_days",
      "pr_familiarity", "pr_familiarity_share", "pr_assoc_member", "pr_assoc_none",
      "iss_dev", "iss_team", "mu_l", "s_l", "extra_team_size", "pr_project_wait",
      "extra_desc_length");

  /** Accumulated volume over accumulated commits. Absent when the person has no commits. */
  public static final String LINES_PER_COMMIT = "a4_loc_por_commit";

  /** Every column this class puts on a row, for a report that has to name them. */
  public static final List<String> COLUMNS =
      java.util.stream.Stream.concat(STORED.stream(), java.util.stream.Stream.of(LINES_PER_COMMIT))
          .toList();

  private final Map<String, Map<String, Double>> byUnit;
  private final Map<String, Set<String>> absentByUnit;

  private Artigo4Features(final Map<String, Map<String, Double>> byUnit,
      final Map<String, Set<String>> absentByUnit) {

    this.byUnit = byUnit;
    this.absentByUnit = absentByUnit;
  }

  /** How many units the export covers, which the report prints beside the coverage. */
  public int units() {
    return byUnit.size();
  }

  /**
   * Reads the export: one header line, then one line per unit, tab separated.
   *
   * <p>An <b>empty field is absence</b> and a zero is a zero. The two are different facts and the
   * export was written to keep them apart; collapsing them here would undo that.
   */
  public static Artigo4Features read(final Path file) throws IOException {
    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      throw new IOException("export vazio: " + file);
    }
    final String[] header = lines.get(0).split("\t", -1);
    final Map<String, Integer> at = new HashMap<>();
    for (int i = 0; i < header.length; i++) {
      at.put(header[i], i);
    }
    for (final String needed : STORED) {
      if (!at.containsKey(needed)) {
        throw new IOException("coluna ausente no export: " + needed);
      }
    }

    final Map<String, Map<String, Double>> byUnit = new HashMap<>();
    final Map<String, Set<String>> absentByUnit = new HashMap<>();
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final String[] parts = line.split("\t", -1);
      final String id = parts[0];
      final Map<String, Double> values = new HashMap<>();
      final Set<String> absent = new HashSet<>();

      for (final String column : STORED) {
        put(values, absent, column, parse(parts, at.get(column)));
      }

      // Volume acumulado sem commits acumulados nao e volume por commit zero: e indefinido.
      final Double volume = values.get("loc_tot_java");
      final Double commits = values.get("extra_dev_commits");
      put(values, absent, LINES_PER_COMMIT,
          volume == null || commits == null || commits <= 0 ? null : volume / commits);

      // O proprio Artigo 4 marca quem o ledger nunca viu. Zero de volume para essa pessoa e
      // ausencia de evidencia, nao evidencia de zero, e a coluna carrega o indicador que diz isso.
      if (flagFalse(parts, at.get("dev_known_to_ledger"))) {
        values.remove("loc_tot_java");
        absent.add("loc_tot_java");
        values.put("loc_tot_java_ausente", 1.0);
      }

      byUnit.put(id, Map.copyOf(values));
      absentByUnit.put(id, Set.copyOf(absent));
    }
    return new Artigo4Features(Map.copyOf(byUnit), Map.copyOf(absentByUnit));
  }

  private static boolean flagFalse(final String[] parts, final Integer index) {
    return index != null && index < parts.length && "0".equals(parts[index].trim());
  }

  private static Double parse(final String[] parts, final Integer index) {
    if (index == null || index >= parts.length) {
      return null;
    }
    final String raw = parts[index].trim();
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return Double.valueOf(raw);
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  private static void put(final Map<String, Double> values, final Set<String> absent,
      final String column, final Double value) {

    final boolean declared = Attributes.declares(column) && Attributes.byName(column)
        .absenceIndicator();
    if (value == null) {
      absent.add(column);
      if (declared) {
        values.put(column + "_ausente", 1.0);
      }
    } else {
      values.put(column, value);
      if (declared) {
        values.put(column + "_ausente", 0.0);
      }
    }
  }

  /**
   * The same row with this specification's columns added. Never mutates the original.
   *
   * <p>A unit the export does not cover comes back with every column absent rather than with zeros,
   * so a gap in the join can never be read as a developer with no history.
   */
  public Row augment(final Row row, final String unitId) {
    final Map<String, Double> values = new HashMap<>(row.values());
    final Set<String> absent = new HashSet<>(row.absent());
    final Map<String, Double> mine = byUnit.get(unitId);
    if (mine == null) {
      for (final String column : COLUMNS) {
        absent.add(column);
        if (Attributes.declares(column) && Attributes.byName(column).absenceIndicator()) {
          values.put(column + "_ausente", 1.0);
        }
      }
      return new Row(row.unitId(), values, absent);
    }
    values.putAll(mine);
    absent.addAll(absentByUnit.getOrDefault(unitId, Set.of()));
    return new Row(row.unitId(), values, absent);
  }

  /** How many rows of a partition lack each column, which the report prints as coverage. */
  public List<int[]> absenceOver(final List<String> unitIds) {
    final List<int[]> counts = new ArrayList<>();
    for (final String column : COLUMNS) {
      int missing = 0;
      for (final String id : unitIds) {
        final Set<String> absent = absentByUnit.get(id);
        if (absent == null || absent.contains(column)) {
          missing++;
        }
      }
      counts.add(new int[] {missing});
    }
    return counts;
  }
}
