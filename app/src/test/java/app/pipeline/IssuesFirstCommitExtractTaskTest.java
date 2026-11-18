package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
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
  @DisplayName("the random rule is the default: the same extraction, with nothing of the temporal "
      + "rule")
  void randomByDefault() {
    final IssuesFirstCommitExtractTask.Report random = new IssuesFirstCommitExtractTask(
        fixture.source(), SilentProgress.INSTANCE, SplitRule.RANDOM).run();
    assertEquals(fc.extraction().split(), random.extraction().split());
    assertEquals(sides(fc.extraction().entries()), sides(random.extraction().entries()));
    assertEquals(fc.extraction().entries().stream().map(RowCache.Entry::row).toList(),
        random.extraction().entries().stream().map(RowCache.Entry::row).toList());
    assertEquals(fc.threshold(), random.threshold());
    assertNull(fc.temporal());
    assertNull(random.temporal());
  }

  /** Each entry's id, outer side and inner side, in order. */
  private static List<String> sides(final List<RowCache.Entry> entries) {
    return entries.stream().map(e -> e.unit().issueId() + " "
        + e.outer(Population.FIRST_COMMIT) + " " + e.inner(Population.FIRST_COMMIT)).toList();
  }

  @Test
  @DisplayName("under the temporal rule: the audit's sides, cap and training targets, its cuts, "
      + "crossing issues and new projects, the same columns, and a matrix the temporal check "
      + "accepts and the random one does not")
  void temporal() {
    final IssuesFirstCommitExtractTask.Report t = new IssuesFirstCommitExtractTask(
        fixture.source(), SilentProgress.INSTANCE, SplitRule.TEMPORAL).run();
    final IssuesFirstCommitAuditTask.Report a = new IssuesFirstCommitAuditTask(fixture.source(),
        SilentProgress.INSTANCE, SplitRule.TEMPORAL).run();
    final var s = t.extraction().split();
    final var sizes = a.sizes();
    assertEquals(List.of(sizes.rows(), sizes.projects(), sizes.train(), sizes.test(), sizes.fit(),
        sizes.validation()),
        List.of(s.rows(), s.projects(), s.train(), s.test(), s.fit(), s.validation()));
    assertEquals(a.threshold(), t.threshold());
    assertEquals(a.training().target(), t.training());
    assertEquals(a.temporal().cuts(), t.temporal().cuts());
    assertEquals(a.temporal().crossing(), t.temporal().crossing());
    assertEquals(a.temporal().innerCrossing(), t.temporal().innerCrossing());
    assertEquals(a.temporal().newProjects(), t.temporal().newProjects());
    assertTrue(t.temporal().cuts().inner().isBefore(t.temporal().cuts().outer()));

    final Map<String, RowCache.Entry> entries = t.extraction().entries().stream()
        .collect(Collectors.toMap(e -> e.unit().issueId(), e -> e));
    int training = 0;
    for (final IssueRow row : a.rows()) {
      if (row.side() == null) {
        assertFalse(entries.containsKey(row.id()), row.key() + " left the population");
        continue;
      }
      training++;
      final RowCache.Entry e = entries.get(row.id());
      assertEquals("TRAIN", e.outer(Population.FIRST_COMMIT));
      assertEquals(row.side() == IssuesFirstCommitAuditTask.Side.FIT ? "TRAIN" : "TEST",
          e.inner(Population.FIRST_COMMIT));
      assertEquals((double) row.count().total(),
          e.row().values().get(IssuesFirstCommitExtractTask.TARGET), row.key());
    }
    assertEquals(sizes.train(), training);

    assertEquals(fc.extraction().columns(), t.extraction().columns());
    assertEquals(List.of(), SplitRule.TEMPORAL.differences(t.extraction().entries(),
        Population.FIRST_COMMIT));
    assertNotEquals(List.of(), SplitRule.RANDOM.differences(t.extraction().entries(),
        Population.FIRST_COMMIT), "the fixture's two rules draw different sides");
    for (final RowCache.Entry e : t.extraction().entries()) {
      assertTrue(e.row().values().get(IssuesFirstCommitExtractTask.TARGET) >= 1);
    }
  }

  @Test
  @DisplayName("under the revised rule: the temporal rule's sides, so the same validation and test "
      + "issues, the crossing issues marked in the split columns and counted from the crossing "
      + "sets, the audit's cap learned without them, the training target with and without them, "
      + "the new projects by both definitions, and a matrix the revised check accepts and the "
      + "temporal one does not")
  void revised() {
    final ExtractionFixture spread = new ExtractionFixture(1);
    final IssuesFirstCommitExtractTask.Report r = new IssuesFirstCommitExtractTask(
        spread.source(), SilentProgress.INSTANCE, SplitRule.REVISED).run();
    final IssuesFirstCommitExtractTask.Report t = new IssuesFirstCommitExtractTask(
        spread.source(), SilentProgress.INSTANCE, SplitRule.TEMPORAL).run();
    final IssuesFirstCommitAuditTask.Report a = new IssuesFirstCommitAuditTask(spread.source(),
        SilentProgress.INSTANCE, SplitRule.REVISED).run();
    final Population fc = Population.FIRST_COMMIT;
    final Map<String, String[]> rv = new HashMap<>();
    r.extraction().entries().forEach(e -> rv.put(e.unit().issueId(),
        new String[] {e.outer(fc), e.inner(fc)}));
    final Map<String, String[]> tv = new HashMap<>();
    t.extraction().entries().forEach(e -> tv.put(e.unit().issueId(),
        new String[] {e.outer(fc), e.inner(fc)}));
    assertEquals(tv.keySet(), rv.keySet(), "the same population");
    int outerCrossing = 0;
    int innerCrossing = 0;
    for (final String id : rv.keySet()) {
      final String[] x = rv.get(id);
      final String[] y = tv.get(id);
      assertEquals("TEST".equals(y[0]), "TEST".equals(x[0]), "the same outer test: " + id);
      assertEquals("TEST".equals(y[1]), "TEST".equals(x[1]), "the same inner validation: " + id);
      outerCrossing += "CROSSING".equals(x[0]) ? 1 : 0;
      innerCrossing += "CROSSING".equals(x[1]) ? 1 : 0;
    }
    assertTrue(outerCrossing > 0 && innerCrossing > 0, outerCrossing + " " + innerCrossing);
    assertTrue(outerCrossing < r.extraction().split().train(), "not every issue crosses");
    assertEquals(outerCrossing, r.temporal().crossing());
    assertEquals(innerCrossing, r.temporal().innerCrossing());
    assertEquals(t.temporal().crossing(), r.temporal().crossing(),
        "the issues the temporal design counts as crossing");
    assertEquals(t.temporal().innerCrossing(), r.temporal().innerCrossing());
    assertEquals(a.threshold(), r.threshold(), "the audit's cap");
    assertEquals(a.temporal().crossing(), r.temporal().crossing());

    // the training target: the whole side, and the issues that teach at the outer level
    assertEquals(r.extraction().split().train(), r.training().n());
    final IssuesFirstCommitExtractTask.Revised revised = r.temporal().revised();
    assertNull(t.temporal().revised(), "the temporal design adds nothing of the revised one");
    final List<Integer> teaching = new java.util.ArrayList<>();
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
    final Set<String> side = new java.util.TreeSet<>(taught);
    side.addAll(by.getOrDefault("CROSSING", Set.of()));
    final Set<String> tested = by.getOrDefault("TEST", Set.of());
    assertEquals(tested.stream().filter(p -> !taught.contains(p)).collect(Collectors.toSet()),
        r.temporal().newProjects().keySet());
    assertEquals(tested.stream().filter(p -> !side.contains(p)).collect(Collectors.toSet()),
        revised.newProjectsTrainingSide().keySet());

    assertEquals(List.of(), SplitRule.REVISED.differences(r.extraction().entries(), fc));
    assertNotEquals(List.of(), SplitRule.TEMPORAL.differences(r.extraction().entries(), fc));
    assertNotEquals(List.of(), SplitRule.REVISED.differences(t.extraction().entries(), fc));
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

  /** An issue of both populations, the first by id. */
  private String common(final IssuesInProgressExtractTask.Report inProgress) {
    final Set<String> started = inProgress.entries().stream().map(e -> e.unit().issueId())
        .collect(Collectors.toSet());
    return fc.extraction().entries().stream().map(e -> e.unit().issueId())
        .filter(started::contains).sorted().findFirst().orElseThrow();
  }

  @Test
  @DisplayName("decision 16a: the first commit's lines and its share of the issue's lines are "
      + "written after the target; the pause flag, which reads the status, is not")
  void firstCommitAttributesWithoutPause() {
    assertEquals(IssuesFirstCommitExtractTask.COLUMNS, fc.extraction().columns()
        .subList(fc.extraction().columns().size() - IssuesFirstCommitExtractTask.COLUMNS.size(),
            fc.extraction().columns().size()));
    assertTrue(!fc.extraction().columns().contains(IssuesInProgressExtractTask.PAUSED));
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
      assertTrue(!e.row().values().containsKey(IssuesInProgressExtractTask.PAUSED));
      assertTrue(!e.row().absent().contains(IssuesInProgressExtractTask.PAUSED));
      assertTrue(e.row().values().keySet().containsAll(app.issues.FirstCommit.ATTRIBUTE_COLUMNS));
    }
    final IssuesInProgressExtractTask.Report inProgress =
        new IssuesInProgressExtractTask(fixture.source(), SilentProgress.INSTANCE).run();
    assertTrue(inProgress.columns().contains(IssuesInProgressExtractTask.PAUSED),
        "the In Progress cache keeps it");
  }

  @Test
  @DisplayName("the extracted counts observed during the work run from the first commit: a "
      + "comment between the first In Progress and the first commit counts for In Progress only")
  void observedFromTheFirstCommit() {
    final ExtractionFixture before = new ExtractionFixture();
    final String id = common(new IssuesInProgressExtractTask(before.source(),
        SilentProgress.INSTANCE).run());
    final ExtractionFixture with = new ExtractionFixture();
    with.comment(id, "zed", ExtractionFixture.day(2) + 2 * ExtractionFixture.H);
    final double fcBefore = value(new IssuesFirstCommitExtractTask(before.source(),
        SilentProgress.INSTANCE).run().extraction().entries(), id, "K03_comments");
    final double fcWith = value(new IssuesFirstCommitExtractTask(with.source(),
        SilentProgress.INSTANCE).run().extraction().entries(), id, "K03_comments");
    final double ipBefore = value(new IssuesInProgressExtractTask(before.source(),
        SilentProgress.INSTANCE).run().entries(), id, "K03_comments");
    final double ipWith = value(new IssuesInProgressExtractTask(with.source(),
        SilentProgress.INSTANCE).run().entries(), id, "K03_comments");
    assertEquals(fcBefore, fcWith, "before the first commit: not counted");
    assertEquals(ipBefore + 1, ipWith, "after the first In Progress: counted");
  }

  @Test
  @DisplayName("the start attributes and the team columns read at the first commit read nothing "
      + "after it: a later commit of a new author changes none of them")
  void nothingAfterTheFirstCommit() {
    final ExtractionFixture later = new ExtractionFixture();
    // after every first commit of the fixture, in project p, on an issue outside the population
    later.commitOn("late", "p", "dev9", ExtractionFixture.day(2) + 4 * ExtractionFixture.H, 500,
        902);
    final IssuesFirstCommitExtractTask.Report other =
        new IssuesFirstCommitExtractTask(later.source(), SilentProgress.INSTANCE).run();
    assertEquals(fc.extraction().split(), other.extraction().split());
    assertEquals(new java.util.TreeMap<>(fc.extraction().team()),
        new java.util.TreeMap<>(other.extraction().team()));
    final List<String> start = new java.util.ArrayList<>();
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
    // the commit is seen where the window reaches past the first commit
    final String id = fc.extraction().entries().stream()
        .filter(e -> e.unit().project().equals("p")).findFirst().orElseThrow().unit().issueId();
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
