package app.issues.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which rows fit and which are scored, read from the verdicts: under the revised design, an issue
 * fits only where it teaches, and the inner validation keeps every issue, crossing ones included.
 */
class FittingSidesTest {

  private static final Population P = Population.FIRST_COMMIT;

  private static RowCache.Entry entry(final String id, final String outer, final String inner) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(P, new String[] {outer, inner});
    final Instant t = Instant.parse("2016-03-01T10:00:00Z");
    return new RowCache.Entry(new IssueUnit(id, "K-" + id, "p", "r", "e", t,
        t.plusSeconds(86_400 * 3), "Fixed", false, t.plusSeconds(3600)).withBusinessDays(2),
        new Row(id, Map.of(), Set.of()), parts);
  }

  /** The five cases of an outer training issue, named by their number, and an outer test issue. */
  private static final List<RowCache.Entry> FIVE = List.of(entry("c1", "TRAIN", "TRAIN"),
      entry("c2", "TRAIN", "CROSSING"), entry("c3", "CROSSING", "CROSSING"),
      entry("c4", "TRAIN", "TEST"), entry("c5", "CROSSING", "TEST"), entry("t", "TEST", ""));

  private static List<String> ids(final List<IssueUnit> units) {
    return units.stream().map(IssueUnit::issueId).toList();
  }

  @Test
  @DisplayName("INNER fits the inner fitting issues that teach, case 1, never case 2 or 3, and "
      + "scores the whole inner validation, case 5 included; the outer test is in neither")
  void inner() {
    final Fitting.Sides s = Fitting.sides(FIVE, Fitting.Mode.INNER, P);
    assertEquals(List.of("c1"), ids(s.fitUnits()));
    assertEquals(List.of("c4", "c5"), ids(s.scoreUnits()));
  }

  @Test
  @DisplayName("RESERVED fits the outer training issues that teach, cases 1, 2 and 4, never 3 or "
      + "5, and scores the outer test alone")
  void reserved() {
    final Fitting.Sides s = Fitting.sides(FIVE, Fitting.Mode.RESERVED, P);
    assertEquals(List.of("c1", "c2", "c4"), ids(s.fitUnits()));
    assertEquals(List.of("t"), ids(s.scoreUnits()));
  }

  @Test
  @DisplayName("without crossing verdicts, the sides of before: the inner fitting and validation, "
      + "the outer training and test")
  void oldRules() {
    final List<RowCache.Entry> plain = List.of(entry("a", "TRAIN", "TRAIN"),
        entry("b", "TRAIN", "TEST"), entry("t", "TEST", ""));
    assertEquals(List.of("a"), ids(Fitting.sides(plain, Fitting.Mode.INNER, P).fitUnits()));
    assertEquals(List.of("b"), ids(Fitting.sides(plain, Fitting.Mode.INNER, P).scoreUnits()));
    assertEquals(List.of("a", "b"),
        ids(Fitting.sides(plain, Fitting.Mode.RESERVED, P).fitUnits()));
    assertEquals(List.of("t"), ids(Fitting.sides(plain, Fitting.Mode.RESERVED, P).scoreUnits()));
  }
}
