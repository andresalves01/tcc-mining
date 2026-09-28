package app.paper6.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.Row;
import app.paper6.Family;
import app.paper6.IssueUnit;
import app.paper6.Profile;
import app.paper6.RowCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pieces the reserved run relies on, checked on numbers small enough to verify by hand. */
class ModelTest {

  private static RowCache.Entry entry(final String id, final String outer, final String inner,
      final double hours) {
    final Instant t0 = Instant.parse("2015-01-01T00:00:00Z");
    return new RowCache.Entry(new IssueUnit(id, "K-" + id, "p", "r", "a", t0,
        t0.plusMillis((long) (hours * 3_600_000)), t0, 1, 1, 1),
        new Row(id, Map.of(), Set.of()), outer, inner);
  }

  @Test
  @DisplayName("the development mode never fits on nor scores a reserved row")
  void innerNeverSeesTheReservedRows() {
    final List<RowCache.Entry> entries = List.of(entry("a", "TRAIN", "TRAIN", 1),
        entry("b", "TRAIN", "TEST", 2), entry("c", "TEST", "", 3),
        entry("d", "TRAIN", "CROSSES_CUT", 4));
    final Fitting.Sides inner = Fitting.sides(entries, Fitting.Mode.INNER);

    assertEquals(List.of("a"), inner.fitUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(List.of("b"), inner.scoreUnits().stream().map(IssueUnit::issueId).toList());

    final Fitting.Sides reserved = Fitting.sides(entries, Fitting.Mode.RESERVED);
    assertEquals(3, reserved.fitUnits().size(), "the whole training side, both inner halves");
    assertEquals(List.of("c"), reserved.scoreUnits().stream().map(IssueUnit::issueId).toList());
  }

  @Test
  @DisplayName("the range is read, not filtered: its limits are placed as percentiles of the truth")
  void theWindowReadsTheRange() {
    final double[] observed = {0.5, 2, 10, 100, 9000};
    final double[] predicted = {1, 1, 10, 50, 9000};
    final Window w = Window.of(predicted, observed, Window.ONE_HOUR, Window.ONE_YEAR);

    assertEquals(3, w.n());
    assertEquals(20.0, w.lowPercentile(), 1e-9, "one of five is shorter than an hour");
    assertEquals(80.0, w.highPercentile(), 1e-9, "four of five are within a year");
    assertEquals((1 + 0 + 50) / 3.0, w.mae(), 1e-9);
  }

  @Test
  @DisplayName("the project bootstrap reports the point gain and brackets it")
  void projectBootstrap() {
    final List<String> projects = new ArrayList<>();
    final double[] observed = new double[60];
    final double[] model = new double[60];
    final double[] reference = new double[60];
    for (int i = 0; i < 60; i++) {
      projects.add("p" + (i % 6));
      observed[i] = 10 + i;
      model[i] = observed[i] + 1;
      reference[i] = observed[i] + 4;
    }
    final ProjectBootstrap.Interval gain =
        ProjectBootstrap.gain(projects, model, reference, observed);

    assertEquals(75.0, gain.gain(), 1e-9, "one hour of error against four");
    assertTrue(gain.low() <= 75.0 && gain.high() >= 75.0);
    assertTrue(gain.modelBetter());
  }

  @Test
  @DisplayName("the grid: 18 cells of family by profile, empty and repeated cells named, and 2 blocks")
  void theGrid() {
    final List<String> skipped = new ArrayList<>();
    final List<Cell> cells = Cell.grid(skipped);

    assertEquals(20, cells.size());
    assertEquals(6, skipped.size(), "one empty triage cell and five repeated ones");
    assertTrue(skipped.stream().anyMatch(s -> s.startsWith("TRIAGEM_JIRA / STRICT_T0")));
    assertFalse(cells.stream().anyMatch(c -> c.family() == Family.TRIAGEM_JIRA
        && c.profile() == Profile.STRICT_T0));
    assertEquals(7, Cell.byLabel(Cell.SO_ATRIBUICAO).attributes().size());
    assertEquals(6, Cell.byLabel(Cell.SO_DESFECHO).attributes().size());
  }
}
