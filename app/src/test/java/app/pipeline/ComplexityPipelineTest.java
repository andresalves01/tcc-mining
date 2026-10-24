package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.contribution.Contribution;
import app.contribution.port.ContributionLedger;
import app.features.CommitComplexity;
import app.features.port.CommitComplexitySource;
import app.features.port.CommitComplexityStore;
import app.labels.ChangedMethod;
import app.labels.IssueLabel;
import app.labels.port.LabelStore;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import app.pipeline.port.SilentProgress;

/** The expensive stage, exercised against in-memory doubles so its rules are cheap to check. */
class ComplexityPipelineTest {

  private static final Instant EPOCH = Instant.parse("2010-01-01T00:00:00Z");
  private static final Progress SILENT = SilentProgress.INSTANCE;

  private static Instant day(final int day) {
    return EPOCH.plus(day, ChronoUnit.DAYS);
  }

  private static IssueLabel label(final String id, final String developer, final String project) {
    return new IssueLabel(id, project, day(100), day(200), developer, day(100), 10, 5,
        List.of(new ChangedMethod("p.C.m()V", 3, 1)));
  }

  private static Contribution commit(final String id, final String developer,
      final String project, final int javaFiles) {

    return new Contribution(id, "hash" + id, project + "-id", project, "vcs", developer + "-raw",
        developer, false, day(50), 10, 0, 10, 0, 1, javaFiles);
  }

  private static final class Labels implements LabelStore {
    private final List<IssueLabel> rows;

    Labels(final IssueLabel... labels) {
      this.rows = List.of(labels);
    }

    @Override
    public long save(final Collection<IssueLabel> labels) {
      throw new AssertionError("this stage never writes labels");
    }

    @Override
    public List<IssueLabel> readAll() {
      return rows;
    }

    @Override
    public String describe() {
      return "target.issue_label";
    }
  }

  private static final class Ledger implements ContributionLedger {
    private final List<Contribution> rows;

    Ledger(final Contribution... contributions) {
      this.rows = List.of(contributions);
    }

    @Override
    public long save(final Collection<Contribution> c) {
      throw new AssertionError("this stage never writes the ledger");
    }

    @Override
    public List<Contribution> readAll() {
      return rows;
    }

    public long count() {
      return rows.size();
    }

    @Override
    public String describe() {
      return "target.developer_contribution";
    }
  }

  private static final class Store implements CommitComplexityStore {
    private final Map<String, CommitComplexity> rows = new LinkedHashMap<>();

    Store(final CommitComplexity... seeded) {
      for (final CommitComplexity c : seeded) {
        rows.put(c.commitId(), c);
      }
    }

    @Override
    public long save(final Collection<CommitComplexity> saved) {
      saved.forEach(c -> rows.put(c.commitId(), c));
      return saved.size();
    }

    @Override
    public Set<String> storedIds() {
      return Set.copyOf(rows.keySet());
    }

    public Map<String, CommitComplexity> readAll() {
      return rows;
    }

    @Override
    public String describe() {
      return "target.commit_complexity";
    }
  }

  /** Records what it was asked to read, which is the whole point of the narrowing tests. */
  private static final class RecordingSource implements CommitComplexitySource {
    private final List<String> asked = new ArrayList<>();

    @Override
    public Report scan(final Collection<String> commitIds,
        final Consumer<CommitComplexity> sink) {

      asked.addAll(commitIds);
      commitIds.forEach(id -> sink.accept(new CommitComplexity(id, 5, 2, 1, 1)));
      return new Report(commitIds.size(), commitIds.size(), commitIds.size(), commitIds.size());
    }
  }

  @Test
  @DisplayName("only commits that touched Java inside a pair that has labels are ever read")
  void narrowsToWhatAPredictorCouldAsk() {
    // Three fifths of the ledger survives this in the real corpus. The value computed for each
    // surviving commit is what a full scan would produce, so this narrows work and not definition.
    final RecordingSource source = new RecordingSource();
    final BuildComplexityTask.Report report = new BuildComplexityTask(
        new Labels(label("i1", "alice", "lucene")),
        new Ledger(
            commit("aaaaaaaaaaaaaaaaaaaaaaa1", "alice", "lucene", 2),
            commit("aaaaaaaaaaaaaaaaaaaaaaa2", "alice", "lucene", 0),
            commit("aaaaaaaaaaaaaaaaaaaaaaa3", "alice", "derby", 2),
            commit("aaaaaaaaaaaaaaaaaaaaaaa4", "bob", "lucene", 2)),
        source, new Store(), SILENT).run();

    assertEquals(List.of("aaaaaaaaaaaaaaaaaaaaaaa1"), source.asked,
        "the second touched no Java, the third is another project, the fourth another developer");
    assertEquals(1, report.targeted());
    assertEquals(1, report.written());
  }

  @Test
  @DisplayName("a commit already read is not paid for twice, so an interrupted run resumes")
  void resumesInsteadOfRestarting() {
    final RecordingSource source = new RecordingSource();
    final BuildComplexityTask.Report report = new BuildComplexityTask(
        new Labels(label("i1", "alice", "lucene")),
        new Ledger(
            commit("aaaaaaaaaaaaaaaaaaaaaaa1", "alice", "lucene", 2),
            commit("aaaaaaaaaaaaaaaaaaaaaaa2", "alice", "lucene", 2)),
        source,
        new Store(new CommitComplexity("aaaaaaaaaaaaaaaaaaaaaaa1", 9, 3, 2, 0)),
        SILENT).run();

    assertEquals(List.of("aaaaaaaaaaaaaaaaaaaaaaa2"), source.asked);
    assertEquals(2, report.targeted());
    assertEquals(1, report.alreadyStored());
  }

  @Test
  @DisplayName("a commit that changed no recoverable method is stored as zero, not skipped")
  void readFoundNothingIsNotTheSameAsNotRead() {
    // Storing the zero is what lets the next run tell the two apart; skipping it would make the
    // stage re-read every barren commit for ever.
    final Store store = new Store();
    new BuildComplexityTask(
        new Labels(label("i1", "alice", "lucene")),
        new Ledger(commit("aaaaaaaaaaaaaaaaaaaaaaa1", "alice", "lucene", 2)),
        (ids, sink) -> {
          ids.forEach(id -> sink.accept(new CommitComplexity(id, 0, 0, 0, 0)));
          return new CommitComplexitySource.Report(ids.size(), 0, 0, 0);
        },
        store, SILENT).run();

    assertTrue(store.storedIds().contains("aaaaaaaaaaaaaaaaaaaaaaa1"),
        "the barren commit is on record, so a resumed run knows it was already read");
  }

  @Test
  @DisplayName("building complexity before the funnel refuses by name")
  void refusesWithoutLabels() {
    final BuildComplexityTask task = new BuildComplexityTask(new Labels(),
        new Ledger(commit("aaaaaaaaaaaaaaaaaaaaaaa1", "alice", "lucene", 2)),
        new RecordingSource(), new Store(), SILENT);

    final BuildComplexityTask.LabelsEmptyException refusal =
        assertThrows(BuildComplexityTask.LabelsEmptyException.class, task::run);

    assertTrue(refusal.getMessage().contains("funnel"), "the refusal names the missing stage");
  }

  @Test
  @DisplayName("building complexity before the ledger refuses rather than reading nothing")
  void refusesWithoutLedger() {
    final BuildComplexityTask task = new BuildComplexityTask(
        new Labels(label("i1", "alice", "lucene")), new Ledger(),
        new RecordingSource(), new Store(), SILENT);

    final BuildComplexityTask.LedgerEmptyException refusal =
        assertThrows(BuildComplexityTask.LedgerEmptyException.class, task::run);

    assertTrue(refusal.getMessage().contains("build-ledger"), "and names the stage that fills it");
  }
}
