package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.IssueUnit;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.IssuesSimilarityTask;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the similarity command writes: the augmented matrix and its diagnostics. */
class IssuesSimilarityCommandTest {

  private static final Instant T = Instant.parse("2016-01-01T00:00:00Z");

  private static RowCache.Entry entry(final String id, final long startHours,
      final long hours, final String outer, final String inner) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant start = T.plusSeconds(3600 * startHours);
    return new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", T,
        start.plusSeconds(3600 * hours), "Fixed", false, start),
        new Row(id, Map.of("A01_desc_length", 2.0), Set.of("TXT_desc_fog")), parts);
  }

  @Test
  @DisplayName("the matrix keeps every header name and column of the cache, the new inner and "
      + "outer columns after them; a missing value reads back missing")
  void matrix(@TempDir final Path dir) throws IOException {
    final List<RowCache.Entry> base = List.of(entry("a", 0, 10, "TRAIN", "TRAIN"),
        entry("b", 1, 20, "TRAIN", "TRAIN"), entry("v", 100, 1, "TRAIN", "TEST"),
        entry("t", 200, 1, "TEST", ""));
    final Path cache = dir.resolve("cache.tsv");
    RowCache.write(cache, base, List.of("A01_desc_length", "TXT_desc_fog"),
        List.of(Population.IN_PROGRESS));
    final String header = Files.readAllLines(cache).get(0);

    final Map<String, String> text = Map.of("a", "x", "b", "y", "v", "x");
    final List<Augmentation.Issue> issues = new ArrayList<>();
    for (final RowCache.Entry e : base) {
      issues.add(new Augmentation.Issue(e.unit().issueId(), "p", e.unit().reporter(),
          e.unit().startedAt().toEpochMilli(), e.unit().resolvedAt().toEpochMilli(),
          e.outer(Population.IN_PROGRESS), e.inner(Population.IN_PROGRESS),
          text.get(e.unit().issueId())));
    }
    final Augmentation.Result result = Augmentation.run(issues);
    final List<RowCache.Entry> augmented = new ArrayList<>();
    for (final RowCache.Entry e : base) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.putAll(result.columns().get(e.unit().issueId()));
      augmented.add(new RowCache.Entry(e.unit(),
          new Row(e.row().unitId(), values, new HashSet<>(e.row().absent())), e.parts()));
    }
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask.Report(List.of(),
        new RegistrationInProgress.Sizes(3, 1, 2, 1), augmented, result, 3);

    final Path out = dir.resolve("matrix.tsv");
    IssuesSimilarityCommand.writeMatrix(out, r, RowCache.columns(cache));
    final String written = Files.readAllLines(out).get(0);
    assertEquals(header + "\t" + String.join("\t", Augmentation.columns()), written);
    assertTrue(written.contains("\tjaccard_median_5_inner\tjaccard_median_5_outer\t"));

    final Map<String, RowCache.Entry> back = new HashMap<>();
    RowCache.read(out).forEach(e -> back.put(e.unit().issueId(), e));
    assertEquals(10.0, back.get("v").row().values().get(Augmentation.jaccard(5, Variant.INNER)));
    assertNull(back.get("t").row().values().get(Augmentation.jaccard(5, Variant.OUTER)));
    assertEquals(2.0, back.get("t").row().values().get("A01_desc_length"));
    assertTrue(back.get("t").row().absent().contains("TXT_desc_fog"));

    final Map<String, List<String>> files = IssuesSimilarityCommand.diagnostics(r);
    assertEquals(Set.of("jaccard-queries.tsv", "jaccard-neighbours.tsv", "coverage.tsv",
        "blocks.tsv"), files.keySet());
    assertTrue(files.get("jaccard-neighbours.tsv").contains("v\tinner\t1\ta\tp\t0.0"),
        files.get("jaccard-neighbours.tsv").toString());
  }
}
