package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.pipeline.IssuesFirstCommitAuditTask.IssueRow;
import app.pipeline.port.SilentProgress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extraction of the first-commit population on a corpus held in memory: the audit's
 * population, split, cap and target, and the columns read at the first commit.
 */
class IssuesFirstCommitExtractTaskTest {

  @TempDir Path dir;

  private ExtractionFixture fixture;
  private IssuesFirstCommitExtractTask.Report fc;
  private IssuesFirstCommitAuditTask.Report audit;

  @BeforeEach
  void run() {
    fixture = new ExtractionFixture();
    fc = new IssuesFirstCommitExtractTask(fixture.source(), SilentProgress.INSTANCE).run();
    audit = new IssuesFirstCommitAuditTask(fixture.source(), SilentProgress.INSTANCE).run();
  }

  private Map<String, RowCache.Entry> entries() {
    return fc.extraction().entries().stream()
        .collect(Collectors.toMap(e -> e.unit().issueId(), e -> e));
  }

  @Test
  @DisplayName("the population, the split, the cap and the training target are the audit's")
  void sameAsAudit() {
    final var s = fc.extraction().split();
    final var a = audit.sizes();
    assertTrue(s.rows() >= 10, "the fixture yields a population: " + s);
    assertEquals(List.of(a.rows(), a.projects(), a.train(), a.test(), a.fit(), a.validation()),
        List.of(s.rows(), s.projects(), s.train(), s.test(), s.fit(), s.validation()));
    assertEquals(audit.funnel(), fc.funnel());
    assertEquals(audit.funnel().getFirst().remaining(), fc.base());
    assertEquals(audit.funnel().getLast().remaining(), s.rows());
    assertEquals(audit.funnel().getLast().removed(), s.zeroDays(), "the issues of a target of 0");
    assertEquals(s.rows() + s.zeroDays(), s.started());
    assertEquals(audit.threshold(), fc.threshold());
    assertEquals(audit.training().target(), fc.training());

    // every training issue carries the audit's target and its parts
    final Map<String, RowCache.Entry> entries = entries();
    int training = 0;
    for (final IssueRow row : audit.rows()) {
      if (row.side() == null) {
        assertTrue(!entries.containsKey(row.id()), row.key() + " left the population");
        continue;
      }
      training++;
      final RowCache.Entry e = entries.get(row.id());
      assertEquals("TRAIN", e.outer(Population.FIRST_COMMIT));
      assertEquals(row.side() == IssuesFirstCommitAuditTask.Side.FIT ? "TRAIN" : "TEST",
          e.inner(Population.FIRST_COMMIT));
      final Map<String, Double> v = e.row().values();
      assertEquals((double) row.count().total(), v.get(IssuesFirstCommitExtractTask.TARGET));
      assertEquals((double) row.count().implementation(),
          v.get(IssuesFirstCommitExtractTask.IMPLEMENTATION));
      assertEquals((double) row.count().tail(), v.get(IssuesFirstCommitExtractTask.TAIL));
      assertEquals((double) row.count().capped(), v.get(IssuesFirstCommitExtractTask.CAPPED));
      assertEquals((double) row.count().marks(), v.get(IssuesFirstCommitExtractTask.MARKS));
      assertEquals((double) row.count().patches(), v.get(IssuesFirstCommitExtractTask.PATCHES));
      assertEquals(row.verdict().hour().doubleValue(), v.get(IssuesFirstCommitExtractTask.DAY_BOUNDARY));
      assertEquals(row.verdict().kept().getFirst().at(), e.unit().startedAt().toEpochMilli(),
          "the start is the first commit that counts");
    }
    assertEquals(a.train(), training);
    assertTrue(audit.training().withPatches() > 0, "the fixture has patches among the marks");
  }

  @Test
  @DisplayName("the split is the rule's, drawn over the population alone, under its own columns")
  void split() {
    final List<RowCache.Entry> entries = fc.extraction().entries();
    assertEquals(List.of(), ProjectSplit.differences(entries, Population.FIRST_COMMIT));
    for (final RowCache.Entry e : entries) {
      assertEquals(Set.of(Population.FIRST_COMMIT), e.parts().keySet());
      assertTrue(e.row().values().get(IssuesFirstCommitExtractTask.TARGET) >= 1);
    }
    final long test = entries.stream()
        .filter(e -> "TEST".equals(e.outer(Population.FIRST_COMMIT))).count();
    assertEquals(fc.extraction().split().test(), test);
  }

  @Test
  @DisplayName("the columns are read at the first commit, the gate passes there, and without an "
      + "assignee the executor is the first commit's author")
  void columnsAtFirstCommit() {
    assertTrue(fc.extraction().travelPassed(), fc.extraction().travel().toString());
    final Map<String, RowCache.Entry> entries = entries();
    // P-6 was Minor at its In Progress and Major from before its first commit on
    assertEquals(3.0, entries.get(ExtractionFixture.id(6)).row().values().get("Z08_priority"));
    // P-5: no assignee; moved to In Progress by dev1, first commit by dev0-alt (Dev Zero)
    final RowCache.Entry five = entries.get(ExtractionFixture.id(5));
    assertEquals("Dev Zero", five.unit().executor());
    final IssuesInProgressExtractTask.Report inProgress =
        new IssuesInProgressExtractTask(fixture.source(), SilentProgress.INSTANCE).run();
    final RowCache.Entry started = inProgress.entries().stream()
        .filter(e -> e.unit().issueId().equals(ExtractionFixture.id(5))).findFirst()
        .orElseThrow();
    assertEquals("Dev One", started.unit().executor());
    final RowCache.Entry six = inProgress.entries().stream()
        .filter(e -> e.unit().issueId().equals(ExtractionFixture.id(6))).findFirst()
        .orElseThrow();
    assertEquals(2.0, six.row().values().get("Z08_priority"), "Minor at the In Progress");
    assertNotEquals(started.unit().startedAt(), five.unit().startedAt());
    final Map<String, Integer> executors = new HashMap<>(fc.extraction().executors());
    assertEquals(fc.extraction().split().rows(),
        executors.values().stream().mapToInt(Integer::intValue).sum());
    assertTrue(executors.containsKey("no assignee: the first commit's author"));
    assertTrue(executors.containsKey("assignee at the first commit"));
  }

  @Test
  @DisplayName("the cache keeps the first-commit split columns, the start and the target")
  void cache() throws Exception {
    final Path path = dir.resolve("first-commit.tsv");
    RowCache.write(path, fc.extraction().entries(), fc.extraction().columns(),
        List.of(Population.FIRST_COMMIT));
    final List<String> header = List.of(java.nio.file.Files.readAllLines(path).getFirst()
        .split("\t"));
    assertTrue(header.containsAll(List.of("iniciada_em", "primeiro_commit_externa",
        "primeiro_commit_interna")), header.toString());
    assertTrue(header.containsAll(IssuesFirstCommitExtractTask.TARGET_COLUMNS));
    assertTrue(!header.contains("andamento_externa"));
    final List<RowCache.Entry> back = RowCache.read(path, Population.FIRST_COMMIT, "TRAIN");
    assertEquals(fc.extraction().split().train(), back.size());
    final Map<String, RowCache.Entry> entries = entries();
    for (final RowCache.Entry e : back) {
      final RowCache.Entry original = entries.get(e.unit().issueId());
      assertEquals(original.unit().startedAt(), e.unit().startedAt());
      assertEquals(original.row().values().get(IssuesFirstCommitExtractTask.TARGET),
          e.row().values().get(IssuesFirstCommitExtractTask.TARGET));
    }
    assertThrows(java.io.IOException.class,
        () -> RowCache.read(path, Population.IN_PROGRESS, "TRAIN"));
  }

  @Test
  @DisplayName("the universe does not build the first-commit population itself")
  void universeRefuses() {
    final Universe universe = Universe.of(fixture.corpus());
    assertThrows(IllegalArgumentException.class,
        () -> universe.population(Population.FIRST_COMMIT));
    assertThrows(IllegalArgumentException.class,
        () -> universe.unit(Population.FIRST_COMMIT, ExtractionFixture.id(1)));
  }
}
