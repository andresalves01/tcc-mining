package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Corpus;
import app.issues.Extractor;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.study.Row;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The literature sets and the extraction's rules that live outside the extractor. */
class IssuesInProgressTest {

  @Test
  @DisplayName("the literature sets: what surfaced after the start only in the retrospective one, "
      + "and D06 nowhere")
  void cells() {
    for (final Cell cell : Cell.literature()) {
      for (final Attribute a : cell.attributes()) {
        assertFalse(a.name().equals(Attributes.OUT_AT_START), cell.label());
        if (a.block() != null && !Attributes.EXECUTOR_AT_START.contains(a.name())) {
          assertTrue(cell.scenario().admits(a.block()), cell.label() + " " + a.name());
        }
      }
    }
    assertTrue(Cell.byLabel("KIKAS / C2").attributes().stream()
        .anyMatch(a -> a.name().equals("K17c2_project_issues_14d_obs")));
    assertFalse(Cell.byLabel("KIKAS / C1").attributes().stream()
        .anyMatch(a -> a.name().equals("K17c2_project_issues_14d_obs")));
  }

  @Test
  @DisplayName("the revised team columns stay out of the registry, the literature sets and the "
      + "rows the extracted matrix is written from")
  void revisedTeamOutsideRegistered() {
    final Map<String, Double> extracted = new HashMap<>();
    Attributes.all().forEach(a -> extracted.put(a.name(), 1.0));
    extracted.put(Attributes.SENSITIVITY_A15W, 1.0);
    Extractor.TEAM_REVISED.forEach(column -> extracted.put(column, 2.0));
    final Row row = IssuesInProgressExtractTask.row("a", extracted);

    for (final String column : Extractor.TEAM_REVISED) {
      assertFalse(Attributes.stored().contains(column), column + " would change the matrix");
      assertFalse(row.values().containsKey(column) || row.absent().contains(column), column);
      for (final Cell cell : Cell.literature()) {
        assertFalse(cell.attributes().stream().anyMatch(a -> a.name().equals(column)),
            cell.label() + " " + column);
      }
    }
  }

  @Test
  @DisplayName("the cache keeps the start and the partition by column name")
  void cacheRoundTrip(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
      throws java.io.IOException {
    final java.time.Instant c = java.time.Instant.parse("2016-01-01T00:00:00Z");
    final app.issues.IssueUnit unit = new app.issues.IssueUnit("a", "P-1", "p", "r", "dev", c,
        c.plusSeconds(7200), "Fixed", false, c.plusSeconds(3600));
    final java.util.Map<Population, String[]> parts = new java.util.EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {"TRAIN", "TEST"});
    final app.issues.RowCache.Entry entry = new app.issues.RowCache.Entry(unit,
        new app.study.Row("a", java.util.Map.of("x", 1.0), java.util.Set.of()), parts);
    final java.nio.file.Path started = dir.resolve("andamento.tsv");
    app.issues.RowCache.write(started, List.of(entry), List.of("x"),
        List.of(Population.IN_PROGRESS));
    final app.issues.RowCache.Entry back = app.issues.RowCache.read(started).get(0);
    assertEquals(1.0, back.unit().durationHours(), 1e-9, "from the start, not the creation");
    assertEquals("TEST", back.inner(Population.IN_PROGRESS));
    assertEquals(1.0, back.row().values().get("x"));
  }

  @Test
  @DisplayName("a pause is a change from In Progress back to Open inside [t0, t1)")
  void pause() {
    final List<Corpus.Event> events = List.of(
        new Corpus.Event("a", 10L, "status", "d", "Open", "In Progress", "1%%0"),
        new Corpus.Event("a", 20L, "status", "d", "In Progress", "Patch Available", "2%%0"),
        new Corpus.Event("a", 40L, "status", "d", "In Progress", "Open", "3%%0"));
    assertFalse(IssuesInProgressExtractTask.paused(events, 10L, 30L), "Patch Available is not");
    assertTrue(IssuesInProgressExtractTask.paused(events, 10L, 50L));
  }
}
