package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import app.features.FeatureRow;
import app.features.IssueAttributes;
import app.features.port.FeatureStore;
import app.features.port.IssueAttributeSource;
import app.labels.ChangedMethod;
import app.labels.IssueLabel;
import app.labels.TemporalSplit;
import app.labels.port.LabelStore;
import app.pipeline.port.Progress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the features use case against in-memory doubles, with no MongoDB and no corpus. */
class FeaturePipelineTest {

  private static final Instant EPOCH = Instant.parse("2010-01-01T00:00:00Z");
  private static final Duration NINETY_DAYS = Duration.ofDays(90);
  private static final Progress SILENT = Progress.silent();

  private static Instant day(final int day) {
    return EPOCH.plus(day, ChronoUnit.DAYS);
  }

  /**
   * The first label store in the suite that actually holds something. The split only ever needed
   * one that was empty, because the only thing worth asserting there was the refusal.
   */
  private static final class InMemoryLabels implements LabelStore {

    private final Map<String, IssueLabel> rows = new LinkedHashMap<>();

    InMemoryLabels(final IssueLabel... labels) {
      for (final IssueLabel label : labels) {
        rows.put(label.issueId(), label);
      }
    }

    @Override
    public long save(final Collection<IssueLabel> labels) {
      labels.forEach(label -> rows.put(label.issueId(), label));
      return labels.size();
    }

    @Override
    public List<IssueLabel> readAll() {
      return new ArrayList<>(rows.values());
    }

    @Override
    public long saveSplit(final TemporalSplit.Split split, final double trainShare,
        final int minResolved) {
      throw new AssertionError("building features must not touch the split");
    }

    @Override
    public String describe() {
      return "target.issue_label";
    }
  }

  private static final class InMemoryLedger implements ContributionLedger {

    private final List<Contribution> rows;

    InMemoryLedger(final Contribution... contributions) {
      this.rows = List.of(contributions);
    }

    @Override
    public long save(final Collection<Contribution> contributions) {
      throw new AssertionError("building features must not write to the ledger");
    }

    @Override
    public List<Contribution> readAll() {
      return rows;
    }

    @Override
    public long count() {
      return rows.size();
    }

    @Override
    public String describe() {
      return "target.developer_contribution";
    }
  }

  /** Captures what would have been written, so the assertions can read the matrix itself. */
  private static final class CapturingStore implements FeatureStore {

    private List<FeatureRow> written = List.of();

    @Override
    public long save(final Collection<FeatureRow> rows, final Duration teamWindow) {
      this.written = List.copyOf(rows);
      return rows.size();
    }

    @Override
    public String describe() {
      return "target.issue_features";
    }
  }

  private static IssueLabel label(final String id, final String developer, final String project,
      final int created, final int resolved) {

    return new IssueLabel(id, project, day(created), day(resolved), developer, day(created), 1,
        60, 40, List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  private static Contribution commit(final String developer, final String project, final int day,
      final int javaLines) {

    return new Contribution(developer + project + day, "hash" + day, project + "-id", project,
        "vcs", developer + "-raw", developer, false, day(day), javaLines, 0, javaLines, 0, 1, 1);
  }

  private static IssueAttributeSource noAttributes() {
    return ids -> Map.of();
  }

  @Test
  @DisplayName("building features before the funnel refuses by name instead of writing nothing")
  void refusesWithoutLabels() {
    // A matrix over zero labels writes an empty collection and exits 0, which reads exactly like a
    // stage that ran and found the corpus wanting. The refusal has to name what is missing.
    final BuildFeaturesTask task = new BuildFeaturesTask(new InMemoryLabels(),
        new InMemoryLedger(commit("alice", "lucene", 10, 100)), noAttributes(),
        new CapturingStore(), SILENT);

    final SplitTask.LabelsEmptyException refusal =
        assertThrows(SplitTask.LabelsEmptyException.class, () -> task.run(NINETY_DAYS));

    assertTrue(refusal.getMessage().contains("target.issue_label"),
        "the refusal names the store it found empty");
    assertTrue(refusal.getMessage().contains("funnel"),
        "a refusal that does not name the missing stage leaves the operator guessing");
  }

  @Test
  @DisplayName("building features before the ledger refuses rather than scoring everyone at zero")
  void refusesWithoutLedger() {
    // Without the ledger every developer has no observable history, so every row would carry a
    // legitimate-looking zero and the model would be fitted against a corpus of strangers.
    final BuildFeaturesTask task = new BuildFeaturesTask(
        new InMemoryLabels(label("i1", "alice", "lucene", 100, 200)),
        new InMemoryLedger(), noAttributes(), new CapturingStore(), SILENT);

    final BuildFeaturesTask.LedgerEmptyException refusal =
        assertThrows(BuildFeaturesTask.LedgerEmptyException.class, () -> task.run(NINETY_DAYS));

    assertTrue(refusal.getMessage().contains("target.developer_contribution"),
        "the refusal names the ledger it found empty");
    assertTrue(refusal.getMessage().contains("build-ledger"),
        "and names the stage that fills it");
  }

  @Test
  @DisplayName("nothing authored at or after the task's creation reaches its row")
  void noLeakageFromTheTaskItself() {
    // The one way this stage can be wrong without anything failing: reach one commit too far and
    // score a task using the work that resolved it.
    final IssueLabel subject = label("i1", "alice", "lucene", 100, 200);
    final CapturingStore store = new CapturingStore();
    new BuildFeaturesTask(
        new InMemoryLabels(subject),
        new InMemoryLedger(
            commit("alice", "lucene", 50, 7),
            commit("alice", "lucene", 100, 1_000_000),
            commit("alice", "lucene", 150, 1_000_000)),
        noAttributes(), store, SILENT).run(NINETY_DAYS);

    final FeatureRow row = store.written.get(0);

    assertEquals(7, row.developer().javaLines(),
        "only the commit on day 50 is history; the one authored at the creation instant is not, "
            + "and the one from day 150 is the work being predicted");
    assertEquals(1, row.developer().commits());
    assertEquals(50.0, row.developer().tenureDays(), 1e-9);
  }

  @Test
  @DisplayName("a first task carries a real zero, and is told apart from a developer never seen")
  void zeroHistoryIsNotAGap() {
    final CapturingStore store = new CapturingStore();
    final BuildFeaturesTask.Report report = new BuildFeaturesTask(
        new InMemoryLabels(
            label("i1", "alice", "lucene", 100, 200),
            label("i2", "stranger", "lucene", 100, 200)),
        new InMemoryLedger(commit("alice", "lucene", 500, 10)),
        noAttributes(), store, SILENT).run(NINETY_DAYS);

    assertEquals(2, report.scan().withoutHistory(), "neither had committed before day 100");
    assertEquals(1, report.scan().unknownPair(),
        "only one of the two zeros is missing evidence rather than a first day");
    assertTrue(store.written.get(0).developerKnownToLedger(),
        "alice commits later on, so the ledger does know the pair");
    assertFalse(store.written.get(1).developerKnownToLedger());
    assertEquals(0, store.written.get(0).developer().javaLines(),
        "a first task enters as zero volume and is not dropped");
  }

  @Test
  @DisplayName("load, team statistics and task metrics land on the row as of the creation instant")
  void theRowDescribesTheMomentTheTaskArrived() {
    final CapturingStore store = new CapturingStore();
    final BuildFeaturesTask.Report report = new BuildFeaturesTask(
        new InMemoryLabels(
            label("i1", "alice", "lucene", 300, 400),
            label("i2", "alice", "lucene", 100, 500),
            label("i3", "bob", "lucene", 100, 500)),
        new InMemoryLedger(
            commit("alice", "lucene", 250, 100),
            commit("bob", "lucene", 260, 300)),
        noAttributes(), store, SILENT).run(NINETY_DAYS);

    final FeatureRow row = store.written.stream()
        .filter(r -> r.issueId().equals("i1")).findFirst().orElseThrow();

    assertEquals(1, row.load().developerInProject(), "alice's other issue was open on day 300");
    assertEquals(2, row.load().team(), "alice and bob were both active, and both held one open");
    assertEquals(2, row.team().size(), "both committed inside the ninety days before day 300");
    assertEquals(200.0, row.team().accumulatedLines().orElseThrow().mean(), 1e-9,
        "alice's 100 and bob's 300");
    assertEquals(100.0, row.team().accumulatedLines().orElseThrow().deviation(), 1e-9);
    assertEquals(100, row.taskLines(), "sixty added and forty deleted, never netted");
    assertEquals(3.0, row.taskComplexity(), 1e-9);
    assertTrue(row.accumulatedComplexity().isEmpty(),
        "McCC_tot has no per-commit table behind it yet, and absent must not become zero");
    assertTrue(row.team().accumulatedComplexity().isEmpty(), "so the team's version is absent too");
    assertEquals(2, report.scan().emptyTeam(),
        "the other two arrived on day 100, before anyone had committed to the project at all");
  }

  @Test
  @DisplayName("an issue the source corpus does not describe is marked, not filled with zeros")
  void missingAttributesAreMarked() {
    final CapturingStore store = new CapturingStore();
    final IssueAttributeSource partial = ids -> Map.of("i1",
        new IssueAttributes(true, OptionalInt.of(240), Optional.of("Bug"), OptionalInt.empty()));

    final BuildFeaturesTask.Report report = new BuildFeaturesTask(
        new InMemoryLabels(
            label("i1", "alice", "lucene", 100, 200),
            label("i2", "alice", "lucene", 110, 200)),
        new InMemoryLedger(commit("alice", "lucene", 50, 10)),
        partial, store, SILENT).run(NINETY_DAYS);

    assertEquals(1, report.scan().attributesFound());
    assertEquals(1, report.scan().withDescription());
    assertEquals(0, report.scan().withLinkedIssues(),
        "issue_links is stored only when an issue has links, so its absence is not a zero here");
    final FeatureRow absent = store.written.stream()
        .filter(r -> r.issueId().equals("i2")).findFirst().orElseThrow();
    assertFalse(absent.attributes().found());
    assertTrue(absent.attributes().issueType().isEmpty());
  }
}
