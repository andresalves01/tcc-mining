package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.identity.PersonRecord;
import app.identity.RefusedBridge;
import app.identity.port.PersonRegistry;
import app.labels.IssueLabel;
import app.labels.TemporalSplit;
import app.labels.port.LabelStore;
import app.paper4.MergedPullRequest;
import app.paper4.PullRequestOutcome;
import app.paper4.port.MergedPullRequestSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the pull-request labelling use case against fakes, with no MongoDB. */
class BuildPullRequestLabelsTaskTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private static MergedPullRequest pr(
      final String id, final String project, final String authorId, final int hoursToMerge) {
    // Sem revisao registrada: a fronteira cai no merge, que e a regra do fallback.
    final Instant merged = T0.plus(Duration.ofHours(hoursToMerge));
    return new MergedPullRequest(id, project, authorId, T0, merged, merged, false, 10, 2, 3, 40);
  }

  private static MergedPullRequest reviewed(final String id, final String authorId,
      final int hoursToReview, final int hoursToMerge) {
    return new MergedPullRequest(id, "demo", authorId, T0,
        T0.plus(Duration.ofHours(hoursToMerge)), T0.plus(Duration.ofHours(hoursToReview)), true,
        10, 2, 3, 40);
  }

  private static final class FakeSource implements MergedPullRequestSource {

    private final List<MergedPullRequest> rows;

    FakeSource(final List<MergedPullRequest> rows) {
      this.rows = rows;
    }

    @Override
    public List<MergedPullRequest> scan() {
      return rows;
    }

    @Override
    public String describe() {
      return "fake source";
    }
  }

  /** Records what was saved, so a test can inspect it without a real store. */
  private static final class RecordingStore implements LabelStore {

    private final List<IssueLabel> saved = new ArrayList<>();

    @Override
    public long save(final Collection<IssueLabel> labels) {
      saved.addAll(labels);
      return labels.size();
    }

    @Override
    public List<IssueLabel> readAll() {
      return List.copyOf(saved);
    }

    @Override
    public long saveSplit(
        final TemporalSplit.Split split, final double trainShare, final int minResolved) {
      throw new AssertionError("this task never splits, only labels");
    }

    @Override
    public String describe() {
      return "target.issue_label";
    }
  }

  private static final class FakeRegistry implements PersonRegistry {

    private final Map<String, String> canonical;

    FakeRegistry(final Map<String, String> canonical) {
      this.canonical = canonical;
    }

    @Override
    public Map<String, PersonRecord> readAll() {
      return Map.of();
    }

    @Override
    public Map<String, Integer> readAuthoredCommits() {
      return Map.of();
    }

    @Override
    public Map<String, String> readCanonical() {
      return canonical;
    }

    @Override
    public Set<String> readBots() {
      return Set.of();
    }

    @Override
    public long saveIdentities(final Collection<PersonRecord> records) {
      throw new AssertionError("this task never writes identities");
    }

    @Override
    public long saveCanonical(final Map<String, String> c, final Map<String, PersonRecord> r,
        final Map<String, Integer> a) {
      throw new AssertionError("this task never writes identities");
    }

    @Override
    public long saveRefusedBridges(final Collection<RefusedBridge> refusals) {
      throw new AssertionError("this task never writes identities");
    }

    @Override
    public String describe() {
      return "fake registry";
    }

    @Override
    public String describeIdentities() {
      return "fake registry (identities)";
    }

    @Override
    public String describeCanonical() {
      return "fake registry (canonical)";
    }
  }

  @Test
  @DisplayName("a pull request with no single commit author is excluded and counted, not skipped in silence")
  void excludesMissingOrAmbiguousAuthor() {
    final List<MergedPullRequest> rows = List.of(
        pr("pr1", "demo", null, 5), // MergedPullRequest.authorId() null: zero or several authors
        pr("pr2", "demo", "alice", 5));
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(rows), store, new FakeRegistry(Map.of("alice", "canonical-alice")));

    final BuildPullRequestLabelsTask.Report report = task.run();

    assertEquals(2, report.scanned());
    assertEquals(1, report.noSingleAuthor());
    assertEquals(0, report.unresolvedIdentity());
    assertEquals(1, report.written());
    assertEquals(1, store.readAll().size());
    assertEquals("canonical-alice", store.readAll().get(0).canonicalAuthorId());
  }

  @Test
  @DisplayName("an empty registry refuses by name instead of silently writing zero rows")
  void refusesAnEmptyRegistry() {
    final List<MergedPullRequest> rows = List.of(pr("pr1", "demo", "alice", 5));
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task =
        new BuildPullRequestLabelsTask(new FakeSource(rows), store, new FakeRegistry(Map.of()));

    final BuildPullRequestLabelsTask.RegistryEmptyException refusal =
        assertThrows(BuildPullRequestLabelsTask.RegistryEmptyException.class, task::run);
    assertTrue(refusal.getMessage().contains("reconstruct-people"));
  }

  @Test
  @DisplayName("a single commit author with no canonical identity is excluded and counted, in a non-empty registry")
  void excludesUnresolvedAuthor() {
    final List<MergedPullRequest> rows =
        List.of(pr("pr1", "demo", "ghost", 5), pr("pr2", "demo", "alice", 5));
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(rows), store, new FakeRegistry(Map.of("alice", "canonical-alice")));

    final BuildPullRequestLabelsTask.Report report = task.run();

    assertEquals(0, report.noSingleAuthor());
    assertEquals(1, report.unresolvedIdentity());
    assertEquals(1, report.written());
  }

  @Test
  @DisplayName("task-level complexity is a documented sentinel, never an exception and never a real zero")
  void carriesTheAbsentComplexitySentinel() {
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(List.of(pr("pr1", "demo", "alice", 5))), store,
        new FakeRegistry(Map.of("alice", "canonical-alice")));

    task.run();

    final IssueLabel label = store.readAll().get(0);
    assertEquals(1, label.methods().size());
    assertEquals(BuildPullRequestLabelsTask.ABSENT_COMPLEXITY, label.methods().get(0));
    assertEquals(0.0, label.meanMccc().orElseThrow());
    assertEquals(0.0, label.meanNl().orElseThrow());
    assertTrue(label.hasRecoverableMethods(),
        "a sentinel must not trip the funnel's own no-methods exclusion");
  }

  @Test
  @DisplayName("REVIEW_TO_MERGE measures from the review, and excludes what was never reviewed")
  void reviewToMergeExcludesUnreviewed() {
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(List.of(
            reviewed("pr1", "alice", 6, 30),
            pr("pr2", "demo", "alice", 30))),   // sem revisao: fronteira no merge
        store, new FakeRegistry(Map.of("alice", "canonical-alice")),
        PullRequestOutcome.REVIEW_TO_MERGE);

    final BuildPullRequestLabelsTask.Report report = task.run();

    assertEquals(1, report.withoutReview(),
        "um PR sem revisao nao tem intervalo pos-revisao, e sair e mais honesto que valer zero");
    assertEquals(1, report.written());
    final IssueLabel label = store.readAll().get(0);
    assertEquals(T0.plus(Duration.ofHours(6)), label.createdAt());
    assertEquals(T0.plus(Duration.ofHours(30)), label.resolvedAt());
    assertEquals(1.0, label.ttcDays(), 1e-9);
  }

  @Test
  @DisplayName("OPEN_TO_REVIEW measures up to the review, and keeps what fell back to the merge")
  void openToReviewKeepsTheFallback() {
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(List.of(
            reviewed("pr1", "alice", 6, 30),
            pr("pr2", "demo", "alice", 48))),
        store, new FakeRegistry(Map.of("alice", "canonical-alice")),
        PullRequestOutcome.OPEN_TO_REVIEW);

    final BuildPullRequestLabelsTask.Report report = task.run();

    assertEquals(0, report.withoutReview(), "o fallback mantem a linha, nao a exclui");
    assertEquals(2, report.written());
    assertEquals(0.25, store.readAll().get(0).ttcDays(), 1e-9);
    assertEquals(2.0, store.readAll().get(1).ttcDays(), 1e-9,
        "sem revisao, o intervalo ate a fronteira e a vida inteira do PR");
  }

  @Test
  @DisplayName("the outcome interval is opened-to-merged, matching MergedPullRequest exactly")
  void outcomeIntervalMatchesOpenedToMerged() {
    final RecordingStore store = new RecordingStore();
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        new FakeSource(List.of(pr("pr1", "demo", "alice", 48))), store,
        new FakeRegistry(Map.of("alice", "canonical-alice")));

    task.run();

    final IssueLabel label = store.readAll().get(0);
    assertEquals(T0, label.createdAt());
    assertEquals(T0.plus(Duration.ofHours(48)), label.resolvedAt());
    assertEquals(2.0, label.ttcDays(), 1e-9);
    // Nao e primeiro commit: carrega a fronteira de revisao, aqui igual ao merge pelo fallback.
    assertEquals(T0.plus(Duration.ofHours(48)), label.firstCommitAt());
  }
}
