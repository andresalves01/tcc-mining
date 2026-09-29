package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.Family;
import app.paper5.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frozen specification, checked against the document that froze it.
 *
 * <p>The registration is a promise made to a reader who was not here, and the code is what actually
 * runs. They are two copies of one decision, so they get compared rather than trusted -- the same
 * reason {@code AttributesTest} reads {@code MATRIZ-ATRIBUTOS.csv}. If a parameter is edited in one
 * place and not the other, this test says so before the reserved partition is spent on the
 * difference.
 */
class Paper5ReservedTaskTest {

  private static final Path REGISTRO = Path.of(
      "papers/pull-requests/replicacoes/validacao-yu-chouchen/REGISTRO-PREVIO.md");

  /** The registration names the specifications in prose; this is the only place that maps them. */
  private static final Map<String, Family> BY_LABEL = Map.of(
      "Os três juntos", Family.COMBINADO_MAIS_ARTIGO4,
      "Contribuição própria", Family.CONTRIBUICAO_PROPRIA,
      "Chouchen", Family.CHOUCHEN_CORE_10,
      "Yu + Chouchen", Family.COMBINED_PRE,
      "Yu", Family.YU_PRE_COMPAT);

  @Test
  @DisplayName("every alpha and lambda in the code is the one the registration froze")
  void theParametersMatchTheRegistration() throws IOException {
    final Path registro = locate();
    Assumptions.assumeTrue(registro != null,
        "REGISTRO-PREVIO.md nao encontrado a partir de " + Path.of("").toAbsolutePath());

    final Map<Family, double[]> declared = parse(registro);
    assertEquals(5, declared.size(),
        "a tabela de parametros do registro tem de ter as cinco especificacoes");

    for (final Paper5ReservedTask.Frozen frozen : Paper5ReservedTask.FROZEN) {
      final double[] fromDocument = declared.get(frozen.family());
      assertTrue(fromDocument != null, frozen.family() + " nao aparece no registro previo");
      assertEquals(fromDocument[0], frozen.alpha(), 1e-12,
          frozen.family() + ": alpha no codigo difere do registro");
      assertEquals(fromDocument[1], frozen.lambda(), 1e-12,
          frozen.family() + ": lambda no codigo difere do registro");
    }
    assertEquals(5, Paper5ReservedTask.FROZEN.size());
  }

  @Test
  @DisplayName("the reserved run reads one profile, and it is the one the registration fixed")
  void oneProfileOnly() {
    // Reading a second profile would be a second look at the reserved rows under another name.
    assertEquals(Profile.T0_TEXT_PROXY, Paper5ReservedTask.PROFILE);
    assertEquals(5, Paper5ReservedTask.FROZEN.stream()
        .map(Paper5ReservedTask.Frozen::family).distinct().count(),
        "uma especificacao por familia, nenhuma repetida sob outro parametro");
  }

  @Test
  @DisplayName("area_hotness_90d is inside the frozen reading, as decided")
  void hotnessIsIncluded() {
    // Decided on 27/09/2026 after measuring: during development the model was trained where the
    // construct was absent in 0,8% of rows and scored where it was absent in 52,7%, and including
    // it changed the error by thirteen hundredths of an hour. There is no second reading.
    assertTrue(app.paper5.Attributes.of(Family.YU_PRE_COMPAT, Paper5ReservedTask.PROFILE).stream()
        .anyMatch(a -> a.name().equals(app.paper5.model.Cell.HOTNESS)));
  }

  private static Map<Family, double[]> parse(final Path registro) throws IOException {
    // | rotulo | **alpha** | **lambda** |
    final Pattern row = Pattern.compile(
        "^\\|\\s*(.+?)\\s*\\|\\s*\\*\\*([0-9],[0-9]+)\\*\\*\\s*\\|\\s*\\*\\*([0-9,]+)\\*\\*\\s*\\|$");
    final Map<Family, double[]> found = new HashMap<>();
    final List<String> unmatched = new ArrayList<>();
    for (final String line : Files.readAllLines(registro, StandardCharsets.UTF_8)) {
      final Matcher matcher = row.matcher(line.trim());
      if (!matcher.matches()) {
        continue;
      }
      final Family family = BY_LABEL.get(matcher.group(1));
      if (family == null) {
        unmatched.add(matcher.group(1));
        continue;
      }
      found.put(family, new double[] {
          Double.parseDouble(matcher.group(2).replace(',', '.')),
          Double.parseDouble(matcher.group(3).replace(',', '.'))});
    }
    assertTrue(unmatched.isEmpty(),
        "o registro tem linhas de parametro que este teste nao sabe mapear: " + unmatched);
    return found;
  }

  private static Path locate() {
    Path here = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && here != null; up++, here = here.getParent()) {
      final Path candidate = here.resolve(REGISTRO);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }
}
