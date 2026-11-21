package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    parts.put(Population.FIRST_COMMIT, new String[] {outer, inner});
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
    return corrupted("2016-");
  }

  /** The same, with only the dates that begin with {@code dates} corrupted. */
  private Path corrupted(final String dates) throws IOException {
    final Path path = dir.resolve("matrix.tsv");
    RowCache.write(path, List.of(entry("a", "TRAIN", "TRAIN"), entry("b", "TEST", ""),
        entry("c", "TRAIN", "TEST"), entry("x", "CROSSES", "")), List.of("A01_desc_length"),
        List.of(Population.FIRST_COMMIT));
    final List<String> lines = new ArrayList<>(Files.readAllLines(path));
    for (int k = 1; k < lines.size(); k++) {
      if (lines.get(k).contains("\tTEST\t\t") || lines.get(k).contains("\tCROSSES\t")) {
        lines.set(k, lines.get(k).replace(dates, "not-a-date-").replace("\t12.0", "\tnot a "
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
    final List<RowCache.Entry> training = RowCache.read(path, Population.FIRST_COMMIT, "TRAIN");
    assertEquals(List.of("a", "c"), training.stream().map(e -> e.unit().issueId()).toList());
    assertEquals(12.0, training.get(1).row().values().get("A01_desc_length"));
    assertEquals("TEST", training.get(1).inner(Population.FIRST_COMMIT));
    assertEquals(Instant.parse("2016-03-01T11:00:00Z"), training.get(0).unit().startedAt());
  }

  @Test
  @DisplayName("a cache without the population's split columns refuses a read by its outer cut")
  void refusesWithoutThePopulation() throws IOException {
    final Path path = dir.resolve("unsplit.tsv");
    Files.writeString(path, "id\tchave\tprojeto\trelator\texecutor\tcriada_em\tresolvida_em"
        + "\tmotivo\tmovida\tA01_desc_length\n"
        + "a\tK-1\tp\tr\te\t2016-01-01T00:00:00Z\t2016-01-02T00:00:00Z\tFixed\t0\t1.0\n");
    assertThrows(IOException.class, () -> RowCache.read(path, Population.FIRST_COMMIT, "TRAIN"));
  }

  @Test
  @DisplayName("a cache holds the start of one population only")
  void onePopulationPerCache() {
    assertThrows(IllegalArgumentException.class, () -> RowCache.write(dir.resolve("two.tsv"),
        List.of(), List.of("A01_desc_length"),
        List.of(Population.FIRST_COMMIT, Population.FIRST_COMMIT)));
  }

  @Test
  @DisplayName("the split alone: every row's id, key, project, start and verdicts, no value or "
      + "other date parsed, and the population read from the header")
  void readsTheSplitAlone() throws IOException {
    // the creation (10:00) and the resolution (three days on) corrupted, the start (11:00) kept
    final Path path = corrupted("2016-03-01T10");
    Files.write(path, Files.readAllLines(path).stream()
        .map(l -> l.contains("not-a-date") ? l.replace("2016-03-04T10", "not-a-date") : l)
        .toList());
    final List<RowCache.Entry> splits = RowCache.splits(path);
    assertEquals(List.of("a", "b", "c", "x"),
        splits.stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of("TRAIN", "TEST", "TRAIN", "CROSSES"),
        splits.stream().map(e -> e.outer(Population.FIRST_COMMIT)).toList());
    assertEquals(List.of("TRAIN", "", "TEST", ""),
        splits.stream().map(e -> e.inner(Population.FIRST_COMMIT)).toList());
    assertEquals("P-b", splits.get(1).unit().key());
    assertEquals("p", splits.get(1).unit().project());
    assertEquals(Map.of(), splits.get(1).row().values());
    assertEquals(Instant.parse("2016-03-01T11:00:00Z"), splits.get(1).unit().startedAt());
    assertEquals(null, splits.get(1).unit().createdAt());
    assertEquals(Population.FIRST_COMMIT, RowCache.splitPopulation(path));
    assertEquals(List.of(Population.FIRST_COMMIT), RowCache.splitPopulations(path));

    final Path none = dir.resolve("none.tsv");
    RowCache.write(none, List.of(), List.of("A01_desc_length"), List.of());
    assertEquals(List.of(), RowCache.splitPopulations(none));
    assertThrows(IOException.class, () -> RowCache.splitPopulation(none));
  }

  /**
   * A cache of the five cases of an outer training issue (a and y inner fitting, y crossing the
   * outer cut; v and z inner validation, z crossing it; w crossing the inner cut alone) and one
   * outer test row, t, corrupted after its split fields: a value, its dates and its field count.
   */
  private Path revised() throws IOException {
    final Path path = dir.resolve("revised.tsv");
    RowCache.write(path, List.of(entry("a", "TRAIN", "TRAIN"), entry("w", "TRAIN", "CROSSING"),
        entry("y", "CROSSING", "CROSSING"), entry("t", "TEST", ""), entry("v", "TRAIN", "TEST"),
        entry("z", "CROSSING", "TEST")), List.of("A01_desc_length"),
        List.of(Population.FIRST_COMMIT));
    final List<String> lines = new ArrayList<>(Files.readAllLines(path));
    for (int k = 1; k < lines.size(); k++) {
      if (lines.get(k).contains("\tTEST\t\t")) {
        // the creation and the first resolution corrupted, the start (11:00) kept
        lines.set(k, lines.get(k).replace("2016-03-01T10", "not-a-date")
            .replace("2016-03-04T10", "not-a-date").replace("\t12.0", "\tnot a number\textra"));
      }
    }
    return Files.write(path, lines);
  }

  @Test
  @DisplayName("the outer training side, crossing issues included, read whole; the outer test "
      + "never parsed beyond its split field; a read by a set of outer verdicts reads those alone")
  void readsTheTrainingSide() throws IOException {
    final Path path = revised();
    final List<RowCache.Entry> training = RowCache.training(path, Population.FIRST_COMMIT);
    assertEquals(List.of("a", "w", "y", "v", "z"),
        training.stream().map(e -> e.unit().issueId()).toList());
    assertEquals(12.0, training.get(2).row().values().get("A01_desc_length"));
    assertEquals("CROSSING", training.get(2).inner(Population.FIRST_COMMIT));
    assertEquals(List.of("a", "w", "v"), RowCache.read(path, Population.FIRST_COMMIT,
        Set.of("TRAIN")).stream().map(e -> e.unit().issueId()).toList());
    assertEquals(List.of("y", "z"), RowCache.read(path, Population.FIRST_COMMIT,
        Set.of("CROSSING")).stream().map(e -> e.unit().issueId()).toList());
    assertEquals(RowCache.read(path, Population.FIRST_COMMIT, Set.of("TRAIN")).stream()
        .map(e -> e.unit().issueId()).toList(), RowCache.read(path, Population.FIRST_COMMIT,
        "TRAIN").stream().map(e -> e.unit().issueId()).toList());
  }

  @Test
  @DisplayName("the split alone reads the first resolution of the training side's rows only, "
      + "crossing ones included, which the revised check needs; never an outer test row's")
  void splitsReadTheTrainingResolution() throws IOException {
    final List<RowCache.Entry> splits = RowCache.splits(revised());
    final Instant resolved = Instant.parse("2016-03-04T10:00:00Z");
    for (final RowCache.Entry e : splits) {
      if (e.unit().issueId().equals("t")) {
        assertNull(e.unit().resolvedAt());
      } else {
        assertEquals(resolved, e.unit().resolvedAt(), e.unit().issueId());
      }
      assertNull(e.unit().createdAt());
    }
  }
}
