package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.ElasticNetSelection.Combination;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The first-commit population's model sets and the target the models read.
 */
class FirstCommitModelSetsTest {

  private static final long D = 86_400_000L;
  private static final Combination C = new Combination(5, 10, 8);

  private static List<String> names(final Cell cell) {
    return cell.attributes().stream().map(Attribute::name).toList();
  }

  @Test
  @DisplayName("first commit: its size closes the own start model, at creation and log1p, and so "
      + "both retrospective readings; its share closes the retrospective columns of both, raw")
  void firstCommitSets() {
    final List<Cell> cells = ElasticNetSelection.cells(C, Augmentation.Variant.INNER,
        Population.FIRST_COMMIT);
    final Cell start = cells.get(ElasticNetSelection.MODELS.indexOf(
        ElasticNetSelection.BEFORE_PRESSURE));
    final Cell main = cells.get(ElasticNetSelection.MODELS.indexOf(
        ElasticNetSelection.RETROSPECTIVE));
    final Cell ceiling = cells.get(ElasticNetSelection.MODELS.indexOf(
        ElasticNetSelection.WITH_DISCUSSION));

    final Attribute size = start.attributes().getLast();
    assertEquals(FirstCommit.FIRST_LINES, size.name());
    assertTrue(size.atCreation());
    assertEquals(app.study.Construct.Transform.LOG1P, size.transform());

    final List<String> mainReading = new ArrayList<>(names(start));
    mainReading.addAll(ElasticNetSelection.MAIN_READING);
    mainReading.add(FirstCommit.FIRST_SHARE);
    assertEquals(mainReading, names(main));
    final List<String> withDiscussion = new ArrayList<>(mainReading);
    withDiscussion.addAll(Retrospective.DISCUSSION);
    assertEquals(withDiscussion, names(ceiling));

    final Attribute share = main.attributes().getLast();
    assertFalse(share.atCreation(), "a retrospective attribute");
    assertEquals(app.study.Construct.Transform.NONE, share.transform());
  }

  private static RowCache.Entry row(final String id, final Population p,
      final Map<String, Double> values) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(p, new String[] {"TRAIN", "TRAIN"});
    // a Monday at noon to the Friday of the next week: nine business days at any boundary
    final long start = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli();
    return new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "e",
        Instant.ofEpochMilli(start - D), Instant.ofEpochMilli(start + 11 * D), "Fixed", false,
        Instant.ofEpochMilli(start)), new Row(id, values, Set.of()), parts);
  }

  private static Map<String, Double> target(final double days) {
    final Map<String, Double> out = new HashMap<>();
    out.put(FirstCommit.TARGET_COLUMN, days);
    out.put(FirstCommit.BOUNDARY_COLUMN, 4.0);
    return out;
  }

  @Test
  @DisplayName("the first-commit rows carry their target as their business days; a target out "
      + "of 1 to the interval's business days, or a row without one, is refused")
  void firstCommitTarget() {
    final List<RowCache.Entry> rows = List.of(row("a", Population.FIRST_COMMIT, target(3)),
        row("b", Population.FIRST_COMMIT, target(9)));
    final BusinessDays.Population p =
        BusinessDays.population(rows, Augmentation.Variant.INNER);
    assertEquals(List.of(3, 9), p.entries().stream().map(e -> e.unit().businessDays()).toList());
    assertEquals(2, p.kept().train());
    assertEquals(FirstCommit.RULE, BusinessDays.target(rows, FirstCommit.DEFAULT_WAIT_K));
    assertEquals(FirstCommit.POPULATION, BusinessDays.populationRule(rows));
    for (final double bad : List.of(0.0, 10.0, 2.5)) {
      assertThrows(IllegalArgumentException.class, () -> BusinessDays.population(
          List.of(row("a", Population.FIRST_COMMIT, target(bad))), Augmentation.Variant.INNER),
          "target " + bad);
    }
    assertThrows(IllegalArgumentException.class, () -> BusinessDays.population(List.of(
        rows.get(0), row("c", Population.FIRST_COMMIT, new HashMap<>())),
        Augmentation.Variant.INNER));
    assertEquals(List.of(FirstCommit.BOUNDARY_COLUMN, FirstCommit.TARGET_COLUMN),
        BusinessDays.columns(Population.FIRST_COMMIT));
  }

  @Test
  @DisplayName("a matrix's population is the first-commit split its rows carry; none is refused")
  void matrixPopulation() {
    final RowCache.Entry fc = row("a", Population.FIRST_COMMIT, target(3));
    assertEquals(Population.FIRST_COMMIT, RowCache.population(List.of(fc)));
    assertThrows(IllegalArgumentException.class, () -> RowCache.population(List.of()));
    assertEquals(1, ElasticNetSelection.sizes(List.of(fc)).train(),
        "the sizes read the first-commit split");
  }
}
