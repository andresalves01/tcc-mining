package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The revised team columns, kept apart from the registered matrix and joined by issue id. */
class TeamColumnsTest {

  private static RowCache.Entry entry(final String id) {
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {"TRAIN", "TRAIN"});
    return new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", t, t.plusSeconds(7200),
        "Fixed", false, t.plusSeconds(3600)), new Row(id, Map.of("A05_s_l", 3.0), Set.of()), parts);
  }

  private static Map<String, Double> team(final Double median, final Double sd) {
    final Map<String, Double> out = new HashMap<>();
    out.put(Extractor.TEAM_LOC_MEDIAN, median);
    out.put(Extractor.TEAM_LOC_SD, sd);
    return out;
  }

  @Test
  @DisplayName("the file round-trips, a missing value stays missing, and the join adds the four "
      + "columns to each row by id")
  void roundTripAndJoin(@TempDir final Path dir) throws IOException {
    final Map<String, Map<String, Double>> values = new LinkedHashMap<>();
    values.put("a", team(10.0, 0.0));
    values.put("b", team(null, null));
    final Path file = dir.resolve("team.tsv");
    TeamColumns.write(file, values);
    assertEquals("id\t" + String.join("\t", Extractor.TEAM_REVISED),
        Files.readAllLines(file).get(0));
    assertEquals("b\t\t\t\t", Files.readAllLines(file).get(2));

    final List<RowCache.Entry> joined =
        TeamColumns.join(List.of(entry("a"), entry("b")), TeamColumns.read(file));
    final Row a = joined.get(0).row();
    assertEquals(10.0, a.values().get(Extractor.TEAM_LOC_MEDIAN));
    assertEquals(0.0, a.values().get(Extractor.TEAM_LOC_SD));
    assertEquals(3.0, a.values().get("A05_s_l"), "the matrix's own columns stay");
    assertTrue(a.absent().contains(Extractor.TEAM_MCCC_MEDIAN));
    final Row b = joined.get(1).row();
    assertNull(b.values().get(Extractor.TEAM_LOC_MEDIAN));
    assertTrue(b.absent().containsAll(Extractor.TEAM_REVISED));
  }

  @Test
  @DisplayName("a wrong header, a short line, a non-number or a repeated id is refused on read; a "
      + "file that is not a team file is never overwritten")
  void refusesMalformed(@TempDir final Path dir) throws IOException {
    final String header = "id\t" + String.join("\t", Extractor.TEAM_REVISED);
    final Path file = dir.resolve("team.tsv");
    for (final List<String> lines : List.of(List.of("id\tx"), List.of(header, "a\t1.0"),
        List.of(header, "a\tx\t\t\t"), List.of(header, "a\t\t\t\t", "a\t\t\t\t"))) {
      Files.write(file, lines);
      assertThrows(IOException.class, () -> TeamColumns.read(file), lines.toString());
    }
    final Path matrix = dir.resolve("matrix.tsv");
    Files.write(matrix, List.of("id\tchave\tprojeto", "a\tP-1\tp"));
    assertThrows(IOException.class, () -> TeamColumns.write(matrix, Map.of("a", Map.of())));
    assertEquals(List.of("id\tchave\tprojeto", "a\tP-1\tp"), Files.readAllLines(matrix));
    TeamColumns.write(file, Map.of("a", Map.of()));
    TeamColumns.write(file, Map.of("b", Map.of()));
    assertEquals(List.of(header, "b\t\t\t\t"), Files.readAllLines(file),
        "a team file is replaced");
  }

  @Test
  @DisplayName("a file whose issues are not exactly the matrix's is refused")
  void refusesOtherIssues() {
    final Map<String, Map<String, Double>> values = new LinkedHashMap<>();
    values.put("a", team(1.0, 0.0));
    values.put("z", team(1.0, 0.0));
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> TeamColumns.join(List.of(entry("a"), entry("b")), values));
    assertEquals("the team file and the matrix differ: 1 issues of the matrix are not in the "
        + "file (the first b), 1 issues of the file are not in the matrix (the first z)",
        e.getMessage());
  }
}
