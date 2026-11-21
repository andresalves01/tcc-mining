package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.FirstCommit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.pipeline.IssuesFirstCommitAuditTask.IssueRow;
import app.pipeline.port.SilentProgress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    fc = new IssuesFirstCommitExtractTask(fixture.source(), SilentProgress.INSTANCE,
        SplitRule.REVISED).run();
    audit = new IssuesFirstCommitAuditTask(fixture.source(), SilentProgress.INSTANCE,
        SplitRule.REVISED, app.issues.FirstCommit.DEFAULT_WAIT_K).run();
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
      assertTrue(Verdict.OUTER_TRAINING.contains(e.outer(Population.FIRST_COMMIT)), row.key());
      final String inner = e.inner(Population.FIRST_COMMIT);
      if (row.side() == IssuesFirstCommitAuditTask.Side.FIT) {
        assertTrue(Verdict.TRAIN.is(inner) || Verdict.CROSSING.is(inner), row.key());
      } else {
        assertEquals("TEST", inner, row.key());
      }
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
    assertEquals(List.of(), SplitRule.REVISED.differences(entries, Population.FIRST_COMMIT));
    for (final RowCache.Entry e : entries) {
      assertEquals(Set.of(Population.FIRST_COMMIT), e.parts().keySet());
      assertTrue(e.row().values().get(IssuesFirstCommitExtractTask.TARGET) >= 1);
    }
    final long test = entries.stream()
        .filter(e -> "TEST".equals(e.outer(Population.FIRST_COMMIT))).count();
    assertEquals(fc.extraction().split().test(), test);
  }

  @Test
  @DisplayName("the wait fence's k reaches the extraction: the report carries it, FC_target_days "
      + "is counted at the audit's cap for that k, and the default is 1.5")
  void waitFence() {
    assertEquals(FirstCommit.DEFAULT_WAIT_K, fc.waitFenceK());
    final IssuesFirstCommitExtractTask.Report none = new IssuesFirstCommitExtractTask(
        fixture.source(), SilentProgress.INSTANCE, SplitRule.REVISED, FirstCommit.NO_WAIT_CAP)
        .run();
    final IssuesFirstCommitAuditTask.Report a = new IssuesFirstCommitAuditTask(fixture.source(),
        SilentProgress.INSTANCE, SplitRule.REVISED, FirstCommit.NO_WAIT_CAP).run();
    assertEquals(FirstCommit.NO_WAIT_CAP, none.waitFenceK());
    assertEquals(Double.POSITIVE_INFINITY, none.threshold().fence());
    assertEquals(a.threshold(), none.threshold());
    assertEquals(a.training().target(), none.training());
    final Map<String, RowCache.Entry> byId = none.extraction().entries().stream()
        .collect(Collectors.toMap(e -> e.unit().issueId(), e -> e));
    for (final IssueRow row : a.rows()) {
      if (row.side() != null) {
        final var v = byId.get(row.id()).row().values();
        assertEquals((double) row.count().total(), v.get(IssuesFirstCommitExtractTask.TARGET));
        assertEquals(0.0, v.get(IssuesFirstCommitExtractTask.CAPPED), "no wait is cut");
      }
    }
  }

  /** Each entry's id, outer side and inner side, in order. */
  private static List<String> sides(final List<RowCache.Entry> entries) {
    return entries.stream().map(e -> e.unit().issueId() + " "
        + e.outer(Population.FIRST_COMMIT) + " " + e.inner(Population.FIRST_COMMIT)).toList();
  }

  @Test
  @DisplayName("under the revised rule: the temporal rule's sides, so the same validation and test "
      + "issues, the crossing issues marked in the split columns and counted from the crossing "
      + "sets, the audit's cap learned without them, the training target with and without them, "
      + "the new projects by both definitions, and a matrix the revised check accepts and the "
      + "temporal one does not")
  void revised() {
    final ExtractionFixture spread = new ExtractionFixture();
    final IssuesFirstCommitExtractTask.Report r = new IssuesFirstCommitExtractTask(
        spread.source(), SilentProgress.INSTANCE, SplitRule.REVISED).run();
    final IssuesFirstCommitAuditTask.Report a = new IssuesFirstCommitAuditTask(spread.source(),
        SilentProgress.INSTANCE, SplitRule.REVISED, app.issues.FirstCommit.DEFAULT_WAIT_K).run();
    final Population fc = Population.FIRST_COMMIT;
    final Map<String, String[]> rv = new HashMap<>();
    r.extraction().entries().forEach(e -> rv.put(e.unit().issueId(),
        new String[] {e.outer(fc), e.inner(fc)}));
    int outerCrossing = 0;
    int innerCrossing = 0;
    for (final String id : rv.keySet()) {
      final String[] x = rv.get(id);
      outerCrossing += Verdict.CROSSING.is(x[0]) ? 1 : 0;
      innerCrossing += Verdict.CROSSING.is(x[1]) ? 1 : 0;
    }
    assertTrue(outerCrossing > 0 && innerCrossing > 0, outerCrossing + " " + innerCrossing);
    assertTrue(outerCrossing < r.extraction().split().train(), "not every issue crosses");
    assertEquals(outerCrossing, r.temporal().crossing());
    assertEquals(innerCrossing, r.temporal().innerCrossing());
    assertEquals(a.threshold(), r.threshold(), "the audit's cap");
    assertEquals(a.temporal().crossing(), r.temporal().crossing());

    // the training target: the whole side, and the issues that teach at the outer level
    assertEquals(r.extraction().split().train(), r.training().n());
    final IssuesFirstCommitExtractTask.Revised revised = r.temporal().revised();
    final List<Integer> teaching = new ArrayList<>();
    r.extraction().entries().stream().filter(e -> "TRAIN".equals(e.outer(fc)))
        .forEach(e -> teaching.add(e.row().values().get(IssuesFirstCommitExtractTask.TARGET)
            .intValue()));
    assertEquals(app.issues.FirstCommit.summary(teaching), revised.teaching());
    assertEquals(r.training().n() - outerCrossing, revised.teaching().n());

    // new projects: none that teaches, as the scoring reads them; and none on the training side
    final Map<String, Set<String>> by = new HashMap<>();
    r.extraction().entries().forEach(e -> by.computeIfAbsent(e.outer(fc), k -> new java.util
        .TreeSet<>()).add(e.unit().project()));
    final Set<String> taught = by.getOrDefault("TRAIN", Set.of());
    final Set<String> side = new TreeSet<>(taught);
    side.addAll(by.getOrDefault("CROSSING", Set.of()));
    final Set<String> tested = by.getOrDefault("TEST", Set.of());
    assertEquals(tested.stream().filter(p -> !taught.contains(p)).collect(Collectors.toSet()),
        r.temporal().newProjects().keySet());
    assertEquals(tested.stream().filter(p -> !side.contains(p)).collect(Collectors.toSet()),
        revised.newProjectsTrainingSide().keySet());

    assertEquals(List.of(), SplitRule.REVISED.differences(r.extraction().entries(), fc));
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
    assertEquals(fc.extraction().split().train() - fc.temporal().crossing(), back.size(),
        "the outer training issues that do not cross the outer cut");
    final Map<String, RowCache.Entry> entries = entries();
    for (final RowCache.Entry e : back) {
      final RowCache.Entry original = entries.get(e.unit().issueId());
      assertEquals(original.unit().startedAt(), e.unit().startedAt());
      assertEquals(original.row().values().get(IssuesFirstCommitExtractTask.TARGET),
          e.row().values().get(IssuesFirstCommitExtractTask.TARGET));
    }
  }

  /** An issue of both populations, the first by id. */
  /** The first issue of the population that was moved to In Progress before its resolution. */
  private String common(final ExtractionFixture f) {
    final app.issues.Universe universe = app.issues.Universe.of(f.source().load());
    return fc.extraction().entries().stream().map(e -> e.unit().issueId())
        .filter(id -> universe.started(id) != null).sorted().findFirst().orElseThrow();
  }

  @Test
  @DisplayName("decision 16a: the first commit's lines and its share of the issue's lines are "
      + "written after the target")
  void firstCommitAttributesWithoutPause() {
    assertEquals(IssuesFirstCommitExtractTask.COLUMNS, fc.extraction().columns()
        .subList(fc.extraction().columns().size() - IssuesFirstCommitExtractTask.COLUMNS.size(),
            fc.extraction().columns().size()));
    final Map<String, RowCache.Entry> entries = entries();
    int checked = 0;
    for (final IssueRow row : audit.rows()) {
      if (row.side() == null) {
        continue;
      }
      final Map<String, Double> v = entries.get(row.id()).row().values();
      assertEquals((double) row.verdict().kept().getFirst().lines(),
          v.get(app.issues.FirstCommit.FIRST_LINES), row.key());
      assertEquals(row.verdict().firstShare(), v.get(app.issues.FirstCommit.FIRST_SHARE),
          row.key());
      assertTrue(v.get(app.issues.FirstCommit.FIRST_SHARE) < app.issues.FirstCommit.SHARE);
      checked++;
    }
    assertTrue(checked > 0);
    for (final RowCache.Entry e : fc.extraction().entries()) {
      assertTrue(e.row().values().keySet().containsAll(app.issues.FirstCommit.ATTRIBUTE_COLUMNS));
    }
  }

  @Test
  @DisplayName("the extracted counts observed during the work run from the first commit: a "
      + "comment between the first In Progress and the first commit is not counted")
  void observedFromTheFirstCommit() {
    final ExtractionFixture before = new ExtractionFixture();
    final String id = common(before);
    final ExtractionFixture with = new ExtractionFixture();
    with.comment(id, "zed", ExtractionFixture.day(2) + 2 * ExtractionFixture.H);
    final double fcBefore = value(new IssuesFirstCommitExtractTask(before.source(),
        SilentProgress.INSTANCE, SplitRule.REVISED).run().extraction().entries(), id,
        "K03_comments");
    final double fcWith = value(new IssuesFirstCommitExtractTask(with.source(),
        SilentProgress.INSTANCE, SplitRule.REVISED).run().extraction().entries(), id,
        "K03_comments");
    assertEquals(fcBefore, fcWith, "before the first commit: not counted");
  }

  @Test
  @DisplayName("the start attributes and the team columns read at the first commit read nothing "
      + "after it: a later commit of a new author changes none of them")
  void nothingAfterTheFirstCommit() {
    final ExtractionFixture later = new ExtractionFixture();
    // after the last first commit of project p, on an issue outside the population
    later.commitOn("late", "p", "dev9", ExtractionFixture.at(22, 2) + 4 * ExtractionFixture.H,
        500, 902);
    final IssuesFirstCommitExtractTask.Report other =
        new IssuesFirstCommitExtractTask(later.source(), SilentProgress.INSTANCE,
            SplitRule.REVISED).run();
    assertEquals(fc.extraction().split(), other.extraction().split());
    assertEquals(new TreeMap<>(fc.extraction().team()),
        new TreeMap<>(other.extraction().team()));
    final List<String> start = new ArrayList<>();
    for (final app.issues.Attribute a : app.issues.Attributes.all()) {
      if (a.atCreation() || app.issues.Attributes.EXECUTOR_AT_START.contains(a.name())) {
        start.add(a.name());
      }
    }
    start.add(app.issues.FirstCommit.FIRST_LINES);
    int compared = 0;
    for (final RowCache.Entry e : fc.extraction().entries()) {
      for (final String column : start) {
        assertEquals(e.row().values().get(column),
            value(other.extraction().entries(), e.unit().issueId(), column, true),
            e.unit().key() + " " + column);
        compared++;
      }
    }
    assertTrue(compared > 100);
    // the commit is seen where the window reaches past the first commit: P-22's
    final String id = ExtractionFixture.id(22);
    assertEquals(value(fc.extraction().entries(), id, "K19c2_project_commits_14d_obs") + 1,
        value(other.extraction().entries(), id, "K19c2_project_commits_14d_obs"));
  }

  private static double value(final List<RowCache.Entry> entries, final String id,
      final String column) {
    return value(entries, id, column, false);
  }

  private static Double value(final List<RowCache.Entry> entries, final String id,
      final String column, final boolean nullable) {
    final Double v = entries.stream().filter(e -> e.unit().issueId().equals(id)).findFirst()
        .orElseThrow().row().values().get(column);
    if (v == null && !nullable) {
      throw new AssertionError(id + " has no " + column);
    }
    return v;
  }
}
