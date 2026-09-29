package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The registry says what {@code MATRIZ-VIABILIDADE-ISSUES.csv} says, in both directions. */
class AttributesTest {

  private static final Set<String> OUT = Set.of("inviável", "sem sinal no instante",
      "pendente de evidência");
  private static final Map<Family, String> FAMILY = Map.of(
      Family.PROPRIA, "Própria", Family.KIKAS, "Kikas", Family.ALZUBAIDI, "Al-Zubaidi");

  private static Path matrix() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      final Path candidate = dir.resolve("papers/issues/viabilidade/MATRIZ-VIABILIDADE-ISSUES.csv");
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }

  /** A minimal CSV reader: quoted fields may hold commas and doubled quotes. */
  static List<List<String>> read(final Path path) throws IOException {
    final String text = Files.readString(path, StandardCharsets.UTF_8);
    final List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    final StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        row.add(field.toString());
        field.setLength(0);
        if (!(row.size() == 1 && row.get(0).isEmpty())) {
          rows.add(row);
        }
        row = new ArrayList<>();
      } else {
        field.append(c);
      }
    }
    return rows;
  }

  @Test
  @DisplayName("every registered column is a live row of the matrix with the same family, scenario "
      + "and block, and every live row with a column name is registered")
  void theRegistryIsTheMatrix() throws IOException {
    final Path path = matrix();
    Assumptions.assumeTrue(path != null, "the matrix is not in this tree");
    final List<List<String>> rows = read(path);
    final List<String> head = rows.get(0);
    final int id = head.indexOf("identificador");
    final int family = head.indexOf("familia");
    final int scenario = head.indexOf("cenario");
    final int column = head.indexOf("coluna_calculada");
    final int status = head.indexOf("status_no_cenario");
    final int verdict = head.indexOf("veredito");
    final int block = head.indexOf("bloco_c2");
    final int note = head.indexOf("observacao");
    final Map<String, List<String>> live = new HashMap<>();
    for (final List<String> r : rows.subList(1, rows.size())) {
      final boolean out = OUT.contains(r.get(verdict)) || r.get(note).contains("OMITIDO")
          || r.get(note).contains("RETIRADO") || "sensibilidade".equals(r.get(status));
      final boolean plain = r.get(column).matches("[A-Za-z0-9_ -]+") && !r.get(column).contains("/");
      final boolean counted = "na criação".equals(r.get(status))
          || "acréscimo de C2".equals(r.get(status));
      if (!out && plain && counted) {
        live.put(r.get(column) + "|" + r.get(scenario), r);
      }
    }
    final Set<String> registered = new TreeSet<>();
    for (final Attribute a : Attributes.all()) {
      final String key = a.name() + "|" + (a.atCreation() ? "C1" : "C2");
      registered.add(key);
      final List<String> r = live.get(key);
      assertTrue(r != null, a.id() + " " + key + " is not a live row of the matrix");
      assertTrue(r.get(family).startsWith(FAMILY.get(a.family())), a.id() + " family");
      assertEquals(a.atCreation() ? "" : a.block() == Scenario.Block.CONTEUDO ? "conteúdo"
          : "exposição", r.get(block), a.id() + " block");
      assertEquals(a.id(), r.get(id), a.name() + " identifier");
    }
    final Set<String> missing = new TreeSet<>(live.keySet());
    missing.removeAll(registered);
    missing.removeIf(k -> k.startsWith("TYPE_initial|") || k.startsWith("REP_"));
    assertEquals(Set.of(), missing, "live rows of the matrix with no registered column");
  }
}
