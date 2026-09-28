package app.paper6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The registry, and whether it still says what the specification file says. */
class AttributesTest {

  private static final Path MATRIZ = Path.of("papers/01-ttc-semantics/replicacoes/"
      + "transposicao-do-metodo/MATRIZ-ATRIBUTOS-ISSUES.csv");

  @Test
  @DisplayName("the profiles nest, so the difference between two is the class that was added")
  void profilesNest() {
    for (final Family family : Family.values()) {
      Set<String> previous = Set.of();
      for (final Profile profile : Profile.values()) {
        final Set<String> now = names(Attributes.of(family, profile));
        assertTrue(now.containsAll(previous), family + " " + profile);
        previous = now;
      }
    }
  }

  @Test
  @DisplayName("nothing ex ante reads the person who did the work, or the work")
  void exAnteIsExAnte() {
    for (final Attribute attribute : Attributes.all()) {
      final boolean later = attribute.temporalClass() == TemporalClass.CONDICIONADO_A_ATRIBUICAO
          || attribute.temporalClass() == TemporalClass.OBSERVAVEL_APENAS_NO_DESFECHO;
      assertEquals(later, !Profile.T0_RECONSTRUIDO.admits(attribute.temporalClass()),
          attribute.name());
    }
    for (final String late : List.of("loc_tot_java", "iss_dev", "iss_team", "loc_iss",
        "additions_linked", "commits_linked")) {
      assertFalse(Profile.T0_RECONSTRUIDO.admits(Attributes.byName(late).temporalClass()), late);
    }
  }

  @Test
  @DisplayName("the unions are resolved: one text column, one type column, friday left out")
  void unionsAreResolved() {
    final Set<String> total = names(Attributes.of(Family.COMBINADO_TOTAL,
        Profile.DESFECHO_CONHECIDO));
    assertFalse(total.contains("friday_effect"));
    assertFalse(total.contains("description_length_at_open"), "Y11 already reads the text there");
    assertTrue(total.contains("title_description_word_count"));
    assertTrue(total.contains("issue_type_at_open"));
    assertTrue(names(Attributes.of(Family.ESPEC_PROPRIA, Profile.T0_RECONSTRUIDO))
        .contains("description_length_at_open"));
  }

  @Test
  @DisplayName("the grid has the sizes the specification file records")
  void theGridIsTheSizeItShouldBe() {
    assertEquals(9, Attributes.of(Family.YU_ISSUE, Profile.STRICT_T0).size());
    assertEquals(12, Attributes.of(Family.YU_ISSUE, Profile.T0_RECONSTRUIDO).size());
    assertEquals(15, Attributes.of(Family.YU_ISSUE, Profile.DESFECHO_CONHECIDO).size());
    assertEquals(6, Attributes.of(Family.CHOUCHEN_ISSUE, Profile.STRICT_T0).size());
    assertEquals(7, Attributes.of(Family.CHOUCHEN_ISSUE, Profile.T0_RECONSTRUIDO).size());
    assertEquals(9, Attributes.of(Family.CHOUCHEN_ISSUE, Profile.DESFECHO_CONHECIDO).size());
    assertEquals(5, Attributes.of(Family.ESPEC_PROPRIA, Profile.STRICT_T0).size());
    assertEquals(8, Attributes.of(Family.ESPEC_PROPRIA, Profile.T0_RECONSTRUIDO).size());
    assertEquals(15, Attributes.of(Family.ESPEC_PROPRIA, Profile.ATRIBUICAO_DADA).size());
    assertEquals(18, Attributes.of(Family.ESPEC_PROPRIA, Profile.DESFECHO_CONHECIDO).size());
    assertEquals(0, Attributes.of(Family.TRIAGEM_JIRA, Profile.STRICT_T0).size());
    assertEquals(9, Attributes.of(Family.TRIAGEM_JIRA, Profile.T0_RECONSTRUIDO).size());
    assertEquals(13, Attributes.of(Family.COMBINADO_LITERATURA, Profile.STRICT_T0).size());
    assertEquals(16, Attributes.of(Family.COMBINADO_LITERATURA, Profile.T0_RECONSTRUIDO).size());
    assertEquals(18, Attributes.of(Family.COMBINADO_TOTAL, Profile.STRICT_T0).size());
    assertEquals(29, Attributes.of(Family.COMBINADO_TOTAL, Profile.T0_RECONSTRUIDO).size());
    assertEquals(36, Attributes.of(Family.COMBINADO_TOTAL, Profile.ATRIBUICAO_DADA).size());
    assertEquals(42, Attributes.of(Family.COMBINADO_TOTAL, Profile.DESFECHO_CONHECIDO).size());
    assertEquals(19, Attributes.of(Family.COMBINADO_LITERATURA, Profile.DESFECHO_CONHECIDO).size());
    assertEquals(44, Attributes.all().size(), "15 de Yu, 5 so de Chouchen, 9 de triagem, 15 proprias");
  }

  @Test
  @DisplayName("the registry still says what MATRIZ-ATRIBUTOS-ISSUES.csv says, in both directions")
  void theRegistryAgreesWithTheSpecification() throws IOException {
    Path here = Path.of("").toAbsolutePath();
    Path matriz = null;
    for (int up = 0; up < 4 && here != null; up++, here = here.getParent()) {
      if (Files.isRegularFile(here.resolve(MATRIZ))) {
        matriz = here.resolve(MATRIZ);
        break;
      }
    }
    Assumptions.assumeTrue(matriz != null, "MATRIZ-ATRIBUTOS-ISSUES.csv not in this checkout");

    final List<String> lines = Files.readAllLines(matriz, StandardCharsets.UTF_8);
    final Set<String> seen = new HashSet<>();
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final String[] f = line.split(";", -1);
      final String id = f[0];
      final String name = f[1];
      if (name.isEmpty()) {
        continue; // a construct of the PR study that does not transpose: documented, not declared
      }
      final Attribute attribute = Attributes.byName(name);
      assertEquals(id, attribute.id(), name);
      assertEquals(f[2], attribute.temporalClass().name(), name + ": classe temporal");
      final Set<String> families = new HashSet<>(List.of(f[3].split("\\+")));
      assertEquals(families, names(attribute.families()), name + ": familias");
      seen.add(name);
    }
    assertEquals(names(Attributes.all()), seen, "every declared construct has a row, and only they");
  }

  private static Set<String> names(final List<Attribute> attributes) {
    final Set<String> out = new HashSet<>();
    attributes.forEach(a -> out.add(a.name()));
    return out;
  }

  private static Set<String> names(final Set<Family> families) {
    final Set<String> out = new HashSet<>();
    families.forEach(f -> out.add(f.name()));
    return out;
  }
}
