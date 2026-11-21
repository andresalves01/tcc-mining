package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DayBoundaries;
import app.issues.Augmentation.Variant;
import app.issues.Embedder;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.embedding.ReferenceCheck;
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
    parts.put(Population.FIRST_COMMIT, new String[] {outer, inner});
    final Instant start = T.plusSeconds(3600 * startHours);
    return new RowCache.Entry(new IssueUnit(id, "P-" + id, "p", "r", "d", T,
        start.plusSeconds(3600 * hours), "Fixed", false, start),
        new Row(id, Map.of("A01_desc_length", 2.0), Set.of("TXT_desc_fog")), parts);
  }

  /** An embedder giving each reference sentence its reference vector plus {@code delta}. */
  private static Embedder reference(final float delta) {
    return text -> {
      final ReferenceCheck.Item item = ReferenceCheck.items().stream()
          .filter(i -> i.text().equals(text)).findFirst().orElseThrow();
      final float[] v = new float[item.vector().length];
      for (int d = 0; d < v.length; d++) {
        v[d] = (float) item.vector()[d];
      }
      v[v.length - 1] += delta;
      return new Embedder.Embedding(v, 1);
    };
  }

  private static List<long[]> ids(final String text) {
    return List.of(ReferenceCheck.items().stream().filter(i -> i.text().equals(text))
        .findFirst().orElseThrow().ids());
  }

  @Test
  @DisplayName("a model that does not reproduce the sentence-transformers reference is refused "
      + "before anything is embedded; one that does reports its largest difference")
  void selfCheck() throws CommandFailedException {
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> IssuesSimilarityCommand.selfCheck(reference(1e-3f),
            IssuesSimilarityCommandTest::ids));
    assertTrue(String.join("\n", e.lines()).contains("REFUSED"), e.lines().toString());
    assertThrows(CommandFailedException.class, () -> IssuesSimilarityCommand.selfCheck(
        reference(0), t -> List.of(new long[] {101, 102})));
    assertEquals(2e-5, IssuesSimilarityCommand.selfCheck(reference(2e-5f),
        IssuesSimilarityCommandTest::ids).maxDifference(), 1e-6);
  }

  @Test
  @DisplayName("the clusters' sizes, project make-up, assigned cluster and distance go to the "
      + "diagnostic files and each fit's distortion to the run record, never to the matrix")
  void clusterDiagnostics() {
    final List<Augmentation.Issue> issues = new ArrayList<>();
    final Map<String, float[]> vectors = new HashMap<>();
    for (int i = 0; i < 8; i++) {
      final double a = Math.PI * i / 4;
      vectors.put("d" + i, new float[] {(float) Math.cos(a), (float) Math.sin(a)});
      issues.add(new Augmentation.Issue("f" + i, i < 2 ? "q" : "p", "r", 0, (10 + i) * 3_600_000L,
          10 + i,
          10 + i, "TRAIN", "TRAIN", "d" + i));
    }
    issues.add(new Augmentation.Issue("v", "p", "r", 100 * 3_600_000L, 101 * 3_600_000L, 1, 1,
        "TRAIN", "TEST", "d0"));
    final Augmentation.Result result = Augmentation.run(issues,
        s -> new Embedder.Embedding(vectors.get(s), 1), 2, new SmileKMeans(),
        Augmentation.Histories.POINT_IN_TIME);
    final List<RowCache.Entry> entries = new ArrayList<>();
    for (final Augmentation.Issue i : issues) {
      final RowCache.Entry e = entry(i.id(), 0, 1, i.outer(), i.inner());
      final Map<String, Double> values = new HashMap<>(e.row().values());
      entries.add(new RowCache.Entry(new IssueUnit(i.id(), "P-" + i.id(), i.project(), "r", "d",
          T, T, "Fixed", false, T), new Row(i.id(), values, e.row().absent()), e.parts()));
    }
    final IssuesSimilarityTask.Report r = new IssuesSimilarityTask.Report(List.of(),
        new ProjectSplit.Sizes(9, 0, 8, 1), entries, result, 9, Set.of());
    final Map<String, List<String>> files =
        IssuesSimilarityCommand.diagnostics(r, Population.FIRST_COMMIT);

    assertEquals("variant\tk\tvectors\tdistinct\tfitted\tdistortion",
        files.get("cluster-fits.tsv").get(0));
    assertTrue(files.get("cluster-fits.tsv").contains("inner\t16\t8\t8\tfalse\t"),
        files.get("cluster-fits.tsv").toString());
    assertTrue(files.get("cluster-fits.tsv").stream()
        .anyMatch(l -> l.startsWith("inner\t8\t8\t8\ttrue\t")));
    final List<String> sizes = files.get("cluster-sizes.tsv");
    assertEquals(9, sizes.stream().filter(l -> l.startsWith("inner\t8\t")).count() + 1,
        "a header and eight clusters of the inner fitting set");
    assertTrue(sizes.stream().filter(l -> l.startsWith("inner\t8\t"))
        .allMatch(l -> l.endsWith("\t1")), sizes.toString());
    final List<String> projects = files.get("cluster-projects.tsv");
    assertEquals(2, projects.stream().filter(l -> l.startsWith("inner\t8\t")
        && l.endsWith("\tq\t1")).count(), projects.toString());
    final String v = files.get("cluster-queries.tsv").stream()
        .filter(l -> l.startsWith("v\tinner\tinner_validation\t8\t")).findFirst()
        .orElseThrow();
    assertTrue(v.endsWith("\t0.0\t1"), "on f0's vector, its only member: " + v);
    assertTrue(files.get("cluster-queries.tsv").contains(
        "v\tinner\tinner_validation\t16\t\t\t0"),
        "not fitted: no cluster, no distance");
    assertEquals(10.0, result.columns().get("v").get(Augmentation.cluster(8, Variant.INNER)));
    assertTrue(Augmentation.columns().stream().filter(c -> c.startsWith("cluster"))
        .allMatch(c -> c.matches("cluster_median_\\d{1,6}_(inner|outer)")),
        "only the medians are columns: no cluster id, distance or centre");

    final List<Map<String, Object>> record = IssuesSimilarityCommand.fits(result.clusterFits());
    assertEquals(result.clusterFits().size(), record.size());
    final Map<String, Object> eight = record.stream().filter(m -> m.get("variant").equals("inner")
        && m.get("k").equals(8)).findFirst().orElseThrow();
    assertEquals(true, eight.get("fitted"));
    assertTrue((Double) eight.get("distortion") >= 0);
    assertNull(record.stream().filter(m -> m.get("k").equals(16)).findFirst().orElseThrow()
        .get("distortion"));
  }
}
