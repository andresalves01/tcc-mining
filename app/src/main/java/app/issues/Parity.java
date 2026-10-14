package app.issues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parity gate: the Java extraction against the Python specification, issue by issue and column
 * by column.
 *
 * <p>The Python column export carries the population membership and every column the column
 * script computed. Two columns are compared under a declared
 * difference: the Python A15 is the whole commit, so it is compared with A15w; and D06 is held at zero
 * here when an interaction is stamped before the creation.
 */
public final class Parity {

  /** Columns whose Python name differs from the Java one. */
  static final Map<String, String> RENAMED = Map.of("A15_change_loc", "A15w_change_loc_whole");

  /** The population flags an export may carry, by the name of their column. */
  static final Set<String> POPULATIONS = Set.of("fixed", "ampla", "herdada", "andamento");

  public record Spec(Map<String, Set<String>> populations, Map<String, Map<String, Double>> rows) {}

  /** Per column: compared values, mismatches, and a few examples. */
  public record Column(String name, int compared, int mismatches, List<String> examples) {}

  /** Membership per population: only in Java, only in Python. */
  public record Membership(String population, int java, int python, int onlyJava,
      int onlyPython) {}

  private Parity() {}

  public static Spec read(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    final String[] header = lines.get(0).split("\t", -1);
    // the population flags come first, after the id: the main export has three, the secondary
    // study's has its own
    final Map<String, Set<String>> populations = new LinkedHashMap<>();
    int first = 1;
    while (first < header.length && POPULATIONS.contains(header[first])) {
      populations.put(header[first], new java.util.HashSet<>());
      first++;
    }
    final Map<String, Map<String, Double>> rows = new HashMap<>(lines.size() * 2);
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] f = line.split("\t", -1);
      final String id = f[0];
      for (int i = 1; i < first; i++) {
        if ("1".equals(f[i])) {
          populations.get(header[i]).add(id);
        }
      }
      final Map<String, Double> row = new HashMap<>();
      for (int i = first; i < header.length; i++) {
        row.put(header[i], f[i].isEmpty() ? null : Double.parseDouble(f[i]));
      }
      rows.put(id, row);
    }
    return new Spec(populations, rows);
  }

  static boolean equal(final Double a, final Double b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.abs(a));
  }

  public static List<Column> columns(final Spec spec, final Map<String, Map<String, Double>> java) {
    final Map<String, int[]> tally = new LinkedHashMap<>();
    final Map<String, List<String>> examples = new HashMap<>();
    for (final Map.Entry<String, Map<String, Double>> e : spec.rows().entrySet()) {
      final Map<String, Double> j = java.get(e.getKey());
      if (j == null) {
        continue;
      }
      for (final Map.Entry<String, Double> c : e.getValue().entrySet()) {
        // an export that carries A15w itself (the secondary study's) names A15 as Java does
        final String name = e.getValue().containsKey("A15w_change_loc_whole") ? c.getKey()
            : RENAMED.getOrDefault(c.getKey(), c.getKey());
        if (!j.containsKey(name)) {
          continue;
        }
        Double python = c.getValue();
        if ("D06_first_interaction_h".equals(name) && python != null) {
          python = Math.max(0.0, python);
        }
        final int[] t = tally.computeIfAbsent(name, k -> new int[2]);
        t[0]++;
        if (!equal(python, j.get(name))) {
          t[1]++;
          final List<String> ex = examples.computeIfAbsent(name, k -> new ArrayList<>());
          if (ex.size() < 3) {
            ex.add(e.getKey() + ": python=" + python + " java=" + j.get(name));
          }
        }
      }
    }
    final List<Column> out = new ArrayList<>();
    tally.forEach((k, t) -> out.add(new Column(k, t[0], t[1],
        List.copyOf(examples.getOrDefault(k, List.of())))));
    return out;
  }

  public static Membership membership(final String name, final Set<String> python,
      final Set<String> java) {
    int onlyJava = 0;
    for (final String id : java) {
      onlyJava += python.contains(id) ? 0 : 1;
    }
    int onlyPython = 0;
    for (final String id : python) {
      onlyPython += java.contains(id) ? 0 : 1;
    }
    return new Membership(name, java.size(), python.size(), onlyJava, onlyPython);
  }
}
