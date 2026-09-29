package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The attribute registry, and whether it still says what the specification says. */
class AttributesTest {

  private static final Path MATRIZ = Path.of(
      "papers/pull-requests/replicacoes/validacao-yu-chouchen/MATRIZ-ATRIBUTOS.csv");

  @Test
  @DisplayName("the grid has the sizes the specification fixed, in every cell")
  void theGridIsTheSizeItShouldBe() {
    // The seven of Chouchen under T0_TEXT_PROXY are the seven the complement already accepted as
    // approved. That cell is the check on the whole table: if the profiles or the family sets
    // drifted, it is the first number to move.
    // A uniao RESOLVIDA: 22 da literatura mais 13 do Artigo 4, e nao 40, porque as cinco colunas
    // que os dois lados ja tinham entram uma vez so, pelo nome da literatura.
    assertEquals(22, Attributes.of(Family.COMBINADO_MAIS_ARTIGO4, Profile.STRICT_T0).size());
    assertEquals(35,
        Attributes.of(Family.COMBINADO_MAIS_ARTIGO4, Profile.LITERATURE_COMPATIBLE).size());
    assertEquals(34, Attributes.of(Family.COMBINADO_MAIS_ARTIGO4, Profile.T0_TEXT_PROXY).size(),
        "a leitura ex ante da uniao: tudo menos a contagem de commits");

    assertEquals(9, Attributes.of(Family.YU_PRE_COMPAT, Profile.STRICT_T0).size());
    assertEquals(16, Attributes.of(Family.YU_PRE_COMPAT, Profile.T0_TEXT_PROXY).size());
    assertEquals(17, Attributes.of(Family.YU_PRE_COMPAT, Profile.LITERATURE_COMPATIBLE).size());

    assertEquals(6, Attributes.of(Family.CHOUCHEN_CORE_10, Profile.STRICT_T0).size());
    assertEquals(10, Attributes.of(Family.CHOUCHEN_CORE_10, Profile.T0_TEXT_PROXY).size());
    assertEquals(10,
        Attributes.of(Family.CHOUCHEN_CORE_10, Profile.LITERATURE_COMPATIBLE).size());

    assertEquals(13, Attributes.of(Family.COMBINED_PRE, Profile.STRICT_T0).size());
    assertEquals(21, Attributes.of(Family.COMBINED_PRE, Profile.T0_TEXT_PROXY).size());
    assertEquals(22, Attributes.of(Family.COMBINED_PRE, Profile.LITERATURE_COMPATIBLE).size());
  }

  @Test
  @DisplayName("the profiles nest, so a difference between two of them is the group that was added")
  void profilesNest() {
    for (final Family family : Family.values()) {
      final Set<String> strict = names(Attributes.of(family, Profile.STRICT_T0));
      final Set<String> text = names(Attributes.of(family, Profile.T0_TEXT_PROXY));
      final Set<String> all = names(Attributes.of(family, Profile.LITERATURE_COMPATIBLE));

      assertTrue(text.containsAll(strict), family + ": the text profile must contain the strict one");
      assertTrue(all.containsAll(text), family + ": the literature profile must contain the text one");
    }
  }

  @Test
  @DisplayName("friday_effect is Yu's alone, because created_weekday_utc already contains it")
  void theCombinedFamilyDropsTheRedundantIndicator() {
    final Attribute friday = Attributes.byName("friday_effect");

    assertEquals(Set.of(Family.YU_PRE_COMPAT), friday.families());
    assertFalse(names(Attributes.of(Family.COMBINED_PRE, Profile.LITERATURE_COMPATIBLE))
        .contains("friday_effect"));
    assertEquals(42, Attributes.all().size(),
        "vinte e duas na uniao da literatura, mais friday_effect que ela descarta, mais as dezoito "
            + "do Artigo 4, mais linhas alteradas -- derivada, de familia nenhuma, so para a "
            + "ablacao do tamanho");
    assertTrue(Attributes.byName("lines_changed_at_open").families().isEmpty(),
        "a derivada nao pode entrar em cenario nenhum: ela e o adversario, nao um construto");
  }

  @Test
  @DisplayName("a construct the two papers share is declared once, under both families")
  void sharedConstructsAreDeclaredOnce() {
    for (final String shared :
        List.of("additions_at_open", "deletions_at_open", "owner_merged_ratio",
            "title_description_word_count")) {

      final Attribute attribute = Attributes.byName(shared);
      assertTrue(attribute.families().contains(Family.YU_PRE_COMPAT), shared);
      assertTrue(attribute.families().contains(Family.CHOUCHEN_CORE_10), shared);
      assertTrue(attribute.id().startsWith("Y"), shared + " keeps Yu's id");
    }
    assertEquals(Attributes.all().size(),
        names(Attributes.all()).size(), "no name is declared twice");
  }

  @Test
  @DisplayName("only the three constructs whose absence was measured carry an indicator")
  void absenceIndicatorsAreDeclaredWhereAbsenceWasMeasured() {
    assertEquals(Set.of("owner_merged_ratio", "social_connection_90d", "area_hotness_90d",
            "creator_project_median_hours",
            "loc_tot_java", "a4_loc_por_commit", "mu_l", "s_l", "extra_desc_length"),
        Attributes.all().stream().filter(Attribute::absenceIndicator)
            .map(Attribute::name).collect(java.util.stream.Collectors.toSet()));
    assertEquals("owner_merged_ratio_ausente",
        Attributes.byName("owner_merged_ratio").absenceName());
    assertThrows(IllegalStateException.class,
        () -> Attributes.byName("friday_effect").absenceName(),
        "asking for an indicator that was never declared is a programming error, not an empty name");
  }

  @Test
  @DisplayName("the registry still says what MATRIZ-ATRIBUTOS.csv says")
  void theRegistryAgreesWithTheSpecification() throws IOException {
    // The specification is a deliverable the author reads, and the registry is what runs. They are
    // two copies of one decision, so they get checked against each other rather than trusted.
    final Path matriz = locate();
    Assumptions.assumeTrue(matriz != null,
        "MATRIZ-ATRIBUTOS.csv not found from " + Path.of("").toAbsolutePath()
            + " -- the specification folder is not in this checkout");

    final List<String[]> rows = readCsv(matriz);
    assertEquals(27, rows.size(), "the specification lists Y01..Y17 and C01..C10");

    for (final String[] row : rows) {
      final String id = row[0];
      final Family family = Family.valueOf(row[1]);
      final String name = row[2];
      final String status = row[9];

      final Attribute attribute = Attributes.byName(name);
      assertTrue(attribute.families().contains(family),
          id + " (" + name + ") is declared in " + family + " by the specification");
      if (id.startsWith("Y")) {
        assertEquals(id, attribute.id(), name + ": the id is Yu's");
      }

      // The one classification the two documents state in the same terms: a construct rejected for
      // leakage is exactly one the two ex ante profiles must not admit.
      // A leitura ex ante mais ampla e T0_TEXT_PROXY: tudo o que o estudo admite como conhecido
      // ou aproximavel na abertura. Uma coluna descartada e exatamente uma que ela nao admite.
      final boolean descartado = status.startsWith("DESCARTADO");
      assertEquals(descartado, !Profile.T0_TEXT_PROXY.admits(attribute.temporalClass()),
          id + " (" + name + "): a especificacao diz '" + status + "' e o registro diz "
              + attribute.temporalClass());
    }
  }

  private static Set<String> names(final List<Attribute> attributes) {
    return attributes.stream().map(Attribute::name).collect(HashSet::new, Set::add, Set::addAll);
  }

  /** Tests run from the Gradle subproject, so the repository root is one or more levels up. */
  private static Path locate() {
    Path here = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && here != null; up++, here = here.getParent()) {
      final Path candidate = here.resolve(MATRIZ);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** Enough CSV for this file: quoted fields with doubled quotes inside, no embedded newlines. */
  private static List<String[]> readCsv(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    final List<String[]> rows = new ArrayList<>();
    for (final String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      final List<String> fields = new ArrayList<>();
      final StringBuilder field = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
        final char c = line.charAt(i);
        if (c == '"') {
          if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = !quoted;
          }
        } else if (c == ',' && !quoted) {
          fields.add(field.toString());
          field.setLength(0);
        } else {
          field.append(c);
        }
      }
      fields.add(field.toString());
      rows.add(fields.toArray(new String[0]));
    }
    return rows;
  }
}
