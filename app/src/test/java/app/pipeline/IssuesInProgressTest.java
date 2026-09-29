package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Corpus;
import app.issues.Scenario;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The secondary study's cells and rules that live outside the extractor. */
class IssuesInProgressTest {

  @Test
  @DisplayName("six cells: the executor's columns join C1, the change's only the content block, "
      + "and no exposure anywhere")
  void cells() {
    final List<Cell> grid = Cell.grid(Population.IN_PROGRESS);
    assertEquals(6, grid.size());
    for (final Cell cell : grid) {
      for (final Attribute a : cell.attributes()) {
        assertTrue(a.block() != Scenario.Block.EXPOSICAO, cell.label() + " " + a.name());
        if (cell.scenario() == Scenario.C1 && a.block() != null) {
          assertTrue(Attributes.EXECUTOR_AT_START.contains(a.name()), a.name());
        }
      }
    }
    final Cell own = Cell.byLabel("PROPRIA / C1", Population.IN_PROGRESS);
    assertTrue(own.attributes().stream().anyMatch(a -> a.name().equals("A13_exec_open_load")));
    assertFalse(own.attributes().stream().anyMatch(a -> a.name().equals("A15_change_loc")));
    final Cell content = Cell.byLabel("UNIAO / C1_CONTEUDO", Population.IN_PROGRESS);
    assertTrue(content.attributes().stream().anyMatch(a -> a.name().equals("D08_file_familiarity")));
    assertEquals(11, Cell.grid(Population.FIXED).size(), "the main grid is untouched");
  }

  @Test
  @DisplayName("the cache keeps the start and the partition by column name, and the main layout "
      + "writes no start column")
  void cacheRoundTrip(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
      throws java.io.IOException {
    final java.time.Instant c = java.time.Instant.parse("2016-01-01T00:00:00Z");
    final app.issues.IssueUnit unit = new app.issues.IssueUnit("a", "P-1", "p", "r", "dev", c,
        c.plusSeconds(7200), "Fixed", false, c.plusSeconds(3600));
    final java.util.Map<Population, String[]> parts = new java.util.EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {"TRAIN", "TEST"});
    final app.issues.RowCache.Entry entry = new app.issues.RowCache.Entry(unit,
        new app.paper5.Row("a", java.util.Map.of("x", 1.0), java.util.Set.of()), parts);
    final java.nio.file.Path started = dir.resolve("andamento.tsv");
    app.issues.RowCache.write(started, List.of(entry), List.of("x"),
        List.of(Population.IN_PROGRESS));
    final app.issues.RowCache.Entry back = app.issues.RowCache.read(started).get(0);
    assertEquals(1.0, back.unit().durationHours(), 1e-9, "from the start, not the creation");
    assertEquals("TEST", back.inner(Population.IN_PROGRESS));
    assertEquals(1.0, back.row().values().get("x"));

    final java.nio.file.Path main = dir.resolve("principal.tsv");
    final java.util.Map<Population, String[]> fixed = new java.util.EnumMap<>(Population.class);
    fixed.put(Population.FIXED, new String[] {"TEST", ""});
    app.issues.RowCache.write(main, List.of(new app.issues.RowCache.Entry(new app.issues.IssueUnit(
        "a", "P-1", "p", "r", null, c, c.plusSeconds(7200), "Fixed", false),
        new app.paper5.Row("a", java.util.Map.of("x", 1.0), java.util.Set.of()), fixed)),
        List.of("x"));
    assertEquals("id\tchave\tprojeto\trelator\texecutor\tcriada_em\tresolvida_em\tmotivo\tmovida"
        + "\tfixed_externa\tfixed_interna\tampla_externa\tampla_interna\therdada_externa"
        + "\therdada_interna\tx", java.nio.file.Files.readAllLines(main).get(0));
    assertEquals(2.0, app.issues.RowCache.read(main).get(0).unit().durationHours(), 1e-9);
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
