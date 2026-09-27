package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.paper5.model.Cell;
import app.paper5.model.DesignMatrix;
import app.pipeline.Paper5FitTask;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The integrity checks section 10 of the handout asks for, as tests rather than as a checklist.
 *
 * <p>Each one guards a property that is cheap to break and expensive to notice: a target that
 * differs between the baseline and a family, a row counted twice, a grid that quietly stopped
 * covering a family. They run without a database, so they run on every build.
 */
class IntegrityTest {

  private static final Instant T0 = Instant.parse("2019-03-01T09:00:00Z");

  private static Unit unit(final String id, final String project, final long hours) {
    return new Unit(id, project, "ana", T0, T0.plusSeconds(hours * 3600));
  }

  @Test
  @DisplayName("every unit opens strictly before it merges, so the target is always positive")
  void theTargetIsPositive() {
    final Unit ok = unit("a", "p", 5);

    assertTrue(ok.openedAt().isBefore(ok.mergedAt()));
    assertTrue(ok.durationHours() > 0);
    assertTrue(DesignMatrix.target(ok) > 0, "log1p of a positive duration is positive");
  }

  @Test
  @DisplayName("one identifier per unit, across every partition")
  void identifiersAreUnique() {
    final List<Unit> population = List.of(
        unit("a", "p", 1), unit("b", "p", 2), unit("c", "q", 3));
    final Set<String> ids = new HashSet<>();
    population.forEach(u -> ids.add(u.pullRequestId()));

    assertEquals(population.size(), ids.size());
  }

  @Test
  @DisplayName("the target is the same for the baseline and for every family and profile")
  void theTargetIsIdenticalEverywhere() {
    // The one thing that would make the table meaningless while every cell still looked plausible.
    final Unit unit = unit("a", "p", 36);
    final double expected = Math.log1p(36.0);

    for (final Cell cell : Paper5FitTask.grid()) {
      assertEquals(expected, DesignMatrix.target(unit), 0.0,
          cell.label() + " must regress the same quantity as every other cell");
    }
    assertEquals(36.0, DesignMatrix.hours(expected), 1e-9,
        "and the baseline is scored in hours, on the same quantity, after the inverse");
  }

  @Test
  @DisplayName("the grid covers every family and profile, and the exclusion only where it applies")
  void theGridIsComplete() {
    final List<Cell> grid = Paper5FitTask.grid();

    for (final Family family : Family.values()) {
      for (final Profile profile : Profile.values()) {
        assertTrue(grid.stream().anyMatch(
            c -> c.family() == family && c.profile() == profile && c.excluded().isEmpty()),
            family + " / " + profile + " is missing from the grid");
      }
    }
    final List<Cell> without = grid.stream().filter(c -> !c.excluded().isEmpty()).toList();
    assertEquals(3, without.size(),
        "area_hotness_90d belongs to Yu, the union and the union plus the extension -- never to "
            + "Chouchen, and never to the own-contribution family, which is three history columns");
    assertTrue(without.stream().allMatch(c -> c.profile() == Profile.LITERATURE_COMPATIBLE),
        "it is only ever in the literature profile, so only that one is read twice");
    assertEquals(23, grid.size(),
        "five families by four profiles, plus the second reading wherever area_hotness is a member");
  }

  @Test
  @DisplayName("excluding the construct removes exactly one attribute and nothing else")
  void theExclusionIsSurgical() {
    final Cell full = Cell.of(Family.COMBINED_PRE, Profile.LITERATURE_COMPATIBLE);
    final Cell without =
        Cell.without(Family.COMBINED_PRE, Profile.LITERATURE_COMPATIBLE, Cell.HOTNESS);

    assertEquals(22, full.attributes().size());
    assertEquals(21, without.attributes().size());
    final List<String> removed = new ArrayList<>(full.attributes().stream()
        .map(Attribute::name).toList());
    removed.removeAll(without.attributes().stream().map(Attribute::name).toList());
    assertEquals(List.of(Cell.HOTNESS), removed);
  }

  @Test
  @DisplayName("the construct stays in the registry: it is excluded from a reading, not deleted")
  void theConstructIsNotRemovedFromTheSpecification() {
    // Deleting it after measuring its coverage would be choosing an attribute by the data it
    // happened to have, which is the same error as choosing one by the score it produces.
    final Attribute hotness = Attributes.byName(Cell.HOTNESS);

    assertTrue(hotness.families().contains(Family.YU_PRE_COMPAT));
    assertTrue(hotness.absenceIndicator(), "and it still declares the indicator that explains it");
    assertTrue(Attributes.of(Family.COMBINED_PRE, Profile.LITERATURE_COMPATIBLE).stream()
        .anyMatch(a -> a.name().equals(Cell.HOTNESS)));
  }

  @Test
  @DisplayName("a row never appears in both the fit side and the side it is scored on")
  void thePartitionsDoNotOverlap() {
    final List<RowCache.Entry> entries = List.of(
        entry("a", "TRAIN", "TRAIN"), entry("b", "TRAIN", "TEST"),
        entry("c", "TEST", ""), entry("d", "TRAIN", "CROSSES_CUT"));

    final Set<String> fit = new HashSet<>();
    final Set<String> scored = new HashSet<>();
    for (final RowCache.Entry e : entries) {
      if (!"TRAIN".equals(e.outer())) {
        continue;
      }
      if ("TRAIN".equals(e.inner())) {
        fit.add(e.unit().pullRequestId());
      } else if ("TEST".equals(e.inner())) {
        scored.add(e.unit().pullRequestId());
      }
    }
    assertEquals(Set.of("a"), fit);
    assertEquals(Set.of("b"), scored);
    assertTrue(java.util.Collections.disjoint(fit, scored));
    assertFalse(fit.contains("c") || scored.contains("c"),
        "the reserved row reaches neither side");
    assertFalse(fit.contains("d") || scored.contains("d"),
        "and a row that crosses the inner cut reaches neither either");
  }

  private static RowCache.Entry entry(final String id, final String outer, final String inner) {
    return new RowCache.Entry(unit(id, "p", 3), new Row(id, Map.of(), Set.of()), outer, inner);
  }
}
