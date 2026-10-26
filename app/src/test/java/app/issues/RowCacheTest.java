package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.issues.Universe.Population;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The extraction on disk, read whole or only the rows of one side of the outer cut. */
class RowCacheTest {

  @TempDir
  Path dir;

  private static RowCache.Entry entry(final String id, final String outer, final String inner) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant t = Instant.parse("2016-03-01T10:00:00Z");
    return new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", t,
        t.plusSeconds(86_400 * 3), "Fixed", false, t.plusSeconds(3600)),
        new Row(id, Map.of("A01_desc_length", 12.0), Set.of()), parts);
  }

  /**
   * A cache of two outer training rows, one outer test row and one crossing row, the last two
   * corrupted after their split fields: a value, a date and a field count no read could parse.
   */
  private Path corrupted() throws IOException {
    final Path path = dir.resolve("matrix.tsv");
    RowCache.write(path, List.of(entry("a", "TRAIN", "TRAIN"), entry("b", "TEST", ""),
        entry("c", "TRAIN", "TEST"), entry("x", "CROSSES", "")), List.of("A01_desc_length"),
        List.of(Population.IN_PROGRESS));
    final List<String> lines = new ArrayList<>(Files.readAllLines(path));
    for (int k = 1; k < lines.size(); k++) {
      if (lines.get(k).contains("\tTEST\t\t") || lines.get(k).contains("\tCROSSES\t")) {
        lines.set(k, lines.get(k).replace("2016-", "not-a-date-").replace("\t12.0", "\tnot a "
            + "number\textra"));
      }
    }
    return Files.write(path, lines);
  }

  @Test
  @DisplayName("read for one side of the outer cut, the other rows are never parsed beyond their "
      + "split field: values, dates and field counts no read could parse are skipped untouched")
  void readsOnlyOneSideOfTheOuterCut() throws IOException {
    final Path path = corrupted();
    assertThrows(RuntimeException.class, () -> RowCache.read(path));
    final List<RowCache.Entry> training = RowCache.read(path, Population.IN_PROGRESS, "TRAIN");
    assertEquals(List.of("a", "c"), training.stream().map(e -> e.unit().issueId()).toList());
    assertEquals(12.0, training.get(1).row().values().get("A01_desc_length"));
    assertEquals("TEST", training.get(1).inner(Population.IN_PROGRESS));
    assertEquals(Instant.parse("2016-03-01T11:00:00Z"), training.get(0).unit().startedAt());
  }

  @Test
  @DisplayName("a cache without the population's split columns refuses a read by its outer cut")
  void refusesWithoutThePopulation() throws IOException {
    final Path path = dir.resolve("unsplit.tsv");
    Files.writeString(path, "id\tchave\tprojeto\trelator\texecutor\tcriada_em\tresolvida_em"
        + "\tmotivo\tmovida\tA01_desc_length\n"
        + "a\tK-1\tp\tr\te\t2016-01-01T00:00:00Z\t2016-01-02T00:00:00Z\tFixed\t0\t1.0\n");
    assertThrows(IOException.class, () -> RowCache.read(path, Population.IN_PROGRESS, "TRAIN"));
  }

  @Test
  @DisplayName("a cache holds the start of one population only")
  void onePopulationPerCache() {
    assertThrows(IllegalArgumentException.class, () -> RowCache.write(dir.resolve("two.tsv"),
        List.of(), List.of("A01_desc_length"),
        List.of(Population.IN_PROGRESS, Population.FIRST_COMMIT)));
  }

  @Test
  @DisplayName("the split alone: every row's id, key, project and verdicts, no value or date "
      + "parsed, and the population read from the header")
  void readsTheSplitAlone() throws IOException {
    final Path path = corrupted();
    final List<RowCache.Entry> splits = RowCache.splits(path);
    assertEquals(List.of("a", "b", "c", "x"),
        splits.stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of("TRAIN", "TEST", "TRAIN", "CROSSES"),
        splits.stream().map(e -> e.outer(Population.IN_PROGRESS)).toList());
    assertEquals(List.of("TRAIN", "", "TEST", ""),
        splits.stream().map(e -> e.inner(Population.IN_PROGRESS)).toList());
    assertEquals("P-b", splits.get(1).unit().key());
    assertEquals("p", splits.get(1).unit().project());
    assertEquals(Map.of(), splits.get(1).row().values());
    assertEquals(Population.IN_PROGRESS, RowCache.splitPopulation(path));
    assertEquals(List.of(Population.IN_PROGRESS), RowCache.splitPopulations(path));

    final Path none = dir.resolve("none.tsv");
    RowCache.write(none, List.of(), List.of("A01_desc_length"), List.of());
    assertEquals(List.of(), RowCache.splitPopulations(none));
    assertThrows(IOException.class, () -> RowCache.splitPopulation(none));
  }
}
