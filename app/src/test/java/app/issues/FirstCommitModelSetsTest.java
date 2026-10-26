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
 * What the first-commit population changes in the model sets and in the target the models read,
 * and what it leaves as it was for the In Progress one.
 */
class FirstCommitModelSetsTest {

  private static final long D = 86_400_000L;
  private static final Combination C = new Combination(5, 10, 8);

  private static List<String> names(final Cell cell) {
    return cell.attributes().stream().map(Attribute::name).toList();
  }

  @Test
  @DisplayName("the In Progress sets are the ones the calls without a population give, and read "
      + "neither attribute of decision 16a nor the pause flag")
  void inProgressUnchanged() {
    for (final Augmentation.Variant v : Augmentation.Variant.values()) {
      assertEquals(ElasticNetSelection.cells(C, v),
          ElasticNetSelection.cells(C, v, Population.IN_PROGRESS));
      assertEquals(ElasticNetSelection.everyCell(v),
          ElasticNetSelection.everyCell(v, Population.IN_PROGRESS));
    }
    for (final Cell cell : ElasticNetSelection.everyCell(Augmentation.Variant.INNER)) {
      assertFalse(names(cell).contains(FirstCommit.FIRST_LINES), cell.label());
      assertFalse(names(cell).contains(FirstCommit.FIRST_SHARE), cell.label());
      assertFalse(names(cell).contains("PAUSA_volta_open"), cell.label());
    }
    // the own start model of thirteen, as declared
    assertEquals(13, ElasticNetSelection.cells(C, Augmentation.Variant.INNER).get(0)
        .attributes().size());
  }

  @Test
  @DisplayName("first commit: its size closes the own start model, and so both retrospective "
      + "readings; its share closes the retrospective columns of both; the literature sets and "
      + "the pause flag as they are")
  void firstCommitSets() {
    final List<Cell> ip = ElasticNetSelection.cells(C, Augmentation.Variant.INNER);
    final List<Cell> fc =
        ElasticNetSelection.cells(C, Augmentation.Variant.INNER, Population.FIRST_COMMIT);
    final int start = ElasticNetSelection.MODELS.indexOf(ElasticNetSelection.BEFORE_PRESSURE);
    final int main = ElasticNetSelection.MODELS.indexOf(ElasticNetSelection.RETROSPECTIVE);
    final int ceiling = ElasticNetSelection.MODELS.indexOf(ElasticNetSelection.WITH_DISCUSSION);

    final List<String> ownStart = new ArrayList<>(names(ip.get(start)));
    ownStart.add(FirstCommit.FIRST_LINES);
    assertEquals(ownStart, names(fc.get(start)));

    final List<String> mainReading = new ArrayList<>(ownStart);
    mainReading.addAll(ElasticNetSelection.MAIN_READING);
    mainReading.add(FirstCommit.FIRST_SHARE);
    assertEquals(mainReading, names(fc.get(main)));

    final List<String> withDiscussion = new ArrayList<>(mainReading);
    withDiscussion.addAll(Retrospective.DISCUSSION);
    assertEquals(withDiscussion, names(fc.get(ceiling)));

    for (final String literature : List.of(ElasticNetSelection.ALZUBAIDI_START,
        ElasticNetSelection.KIKAS_START, ElasticNetSelection.KIKAS_RETROSPECTIVE)) {
      final int m = ElasticNetSelection.MODELS.indexOf(literature);
      assertEquals(ip.get(m), fc.get(m), literature);
    }
    for (final Cell cell : ElasticNetSelection.everyCell(Augmentation.Variant.INNER,
        Population.FIRST_COMMIT)) {
      assertFalse(names(cell).contains("PAUSA_volta_open"), cell.label());
    }
    final Attribute size = fc.get(start).attributes().getLast();
    assertTrue(size.atCreation());
    assertEquals(app.study.Construct.Transform.LOG1P, size.transform());
    final Attribute share = fc.get(main).attributes().getLast();
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
      + "of 1 to the interval's business days, or a mixture with other rows, is refused")
  void firstCommitTarget() {
    final List<RowCache.Entry> rows = List.of(row("a", Population.FIRST_COMMIT, target(3)),
        row("b", Population.FIRST_COMMIT, target(9)));
    final BusinessDays.Population p =
        BusinessDays.population(rows, Augmentation.Variant.INNER);
    assertEquals(List.of(3, 9), p.entries().stream().map(e -> e.unit().businessDays()).toList());
    assertEquals(2, p.kept().train());
    assertEquals(FirstCommit.RULE, BusinessDays.target(rows));
    assertEquals(FirstCommit.POPULATION, BusinessDays.populationRule(rows));
    for (final double bad : List.of(0.0, 10.0, 2.5)) {
      assertThrows(IllegalArgumentException.class, () -> BusinessDays.population(
          List.of(row("a", Population.FIRST_COMMIT, target(bad))), Augmentation.Variant.INNER),
          "target " + bad);
    }
    final Map<String, Double> inProgress = new HashMap<>(Map.of(BusinessDays.BOUNDARY, 4.0,
        BusinessDays.ACTIVE, 3.0));
    assertThrows(IllegalArgumentException.class, () -> BusinessDays.population(List.of(
        rows.get(0), row("c", Population.IN_PROGRESS, inProgress)),
        Augmentation.Variant.INNER));
    assertEquals(List.of(FirstCommit.BOUNDARY_COLUMN, FirstCommit.TARGET_COLUMN),
        BusinessDays.columns(Population.FIRST_COMMIT));
    assertEquals(BusinessDays.COLUMNS, BusinessDays.columns(Population.IN_PROGRESS));
  }

  @Test
  @DisplayName("a matrix's population is the one split all its rows carry; none, two or a "
      + "mixture is refused")
  void matrixPopulation() {
    final RowCache.Entry fc = row("a", Population.FIRST_COMMIT, target(3));
    final RowCache.Entry ip = row("b", Population.IN_PROGRESS, Map.of());
    assertEquals(Population.FIRST_COMMIT, RowCache.population(List.of(fc)));
    assertEquals(Population.IN_PROGRESS, RowCache.population(List.of(ip)));
    assertThrows(IllegalArgumentException.class, () -> RowCache.population(List.of(fc, ip)));
    assertThrows(IllegalArgumentException.class, () -> RowCache.population(List.of()));
    final Map<Population, String[]> both = new EnumMap<>(Population.class);
    both.put(Population.IN_PROGRESS, new String[] {"TRAIN", "TRAIN"});
    both.put(Population.FIRST_COMMIT, new String[] {"TRAIN", "TRAIN"});
    assertThrows(IllegalArgumentException.class, () -> RowCache.population(List.of(
        new RowCache.Entry(fc.unit(), fc.row(), both))));
    assertEquals(1, ElasticNetSelection.sizes(List.of(fc)).train(),
        "the sizes read the first-commit split");
  }
}
