package app.pipeline;

import app.contribution.Contribution;
import app.contribution.port.ContributionLedger;
import app.features.CommitComplexity;
import app.features.port.CommitComplexitySource;
import app.features.port.CommitComplexityStore;
import app.labels.IssueLabel;
import app.labels.port.LabelStore;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the per-commit complexity table the accumulated complexity indicator is summed from.
 *
 * <p>The funnel already reads each commit's changed methods, but it keeps only the set
 * deduplicated once per issue. This indicator needs the other rule — once per commit, recontributing
 * across commits — and no arithmetic on the stored labels can recover it, because the second visit
 * to a method was thrown away rather than aggregated.
 *
 * <p><b>Narrowed to what could ever be asked.</b> Only commits belonging to a (developer, project)
 * pair that appears in a label, and only those that touched Java, can enter anyone's accumulated
 * complexity. That is about three fifths of the ledger, and the value computed for each is
 * identical to what a full scan would produce — this is a narrowing of work, not of definition.
 *
 * <p>Already-stored commits are skipped, so an interrupted run resumes rather than restarting. That
 * matters here more than anywhere else: this is the stage that reads three collections per commit
 * across a quarter of a million of them.
 */
public final class BuildComplexityTask {

  /** Thrown when there are no labels, so no commit could ever be asked about. */
  public static final class LabelsEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LabelsEmptyException(final String store) {
      super(store + " holds no labels -- run funnel first");
    }
  }

  /** Thrown when the ledger is empty, so there is no commit to read. */
  public static final class LedgerEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LedgerEmptyException(final String ledger) {
      super(ledger + " holds no contributions -- run build-ledger first");
    }
  }

  /** Flushed often enough that a run killed near the end keeps almost all of its work. */
  private static final int FLUSH_EVERY = 5_000;

  public record Report(int targeted, int alreadyStored, CommitComplexitySource.Report scan,
      long written) {}

  private final LabelStore labels;
  private final ContributionLedger ledger;
  private final CommitComplexitySource source;
  private final CommitComplexityStore store;
  private final Progress progress;

  public BuildComplexityTask(
      final LabelStore labels,
      final ContributionLedger ledger,
      final CommitComplexitySource source,
      final CommitComplexityStore store,
      final Progress progress) {

    this.labels = labels;
    this.ledger = ledger;
    this.source = source;
    this.store = store;
    this.progress = progress;
  }

  public Report run() {
    progress.phase(1, 4, "reading the labels back from %s", labels.describe());
    final List<IssueLabel> stored = labels.readAll();
    if (stored.isEmpty()) {
      throw new LabelsEmptyException(labels.describe());
    }
    final Set<String> pairs = new HashSet<>(1 << 12);
    for (final IssueLabel label : stored) {
      pairs.add(label.canonicalAuthorId() + '\u0000' + label.projectName());
    }
    progress.detail("%,d labels over %,d (developer, project) pairs", stored.size(), pairs.size());

    progress.phase(2, 4, "narrowing %s to the commits a predictor could ask about",
        ledger.describe());
    final List<Contribution> contributions = ledger.readAll();
    if (contributions.isEmpty()) {
      throw new LedgerEmptyException(ledger.describe());
    }
    final List<String> targeted = new ArrayList<>(1 << 18);
    for (final Contribution c : contributions) {
      if (c.javaFilesTouched() > 0
          && pairs.contains(c.canonicalId() + '\u0000' + c.projectName())) {
        targeted.add(c.commitId());
      }
    }
    progress.detail("%,d of %,d ledger commits touch Java inside a pair that has labels",
        targeted.size(), contributions.size());

    progress.phase(3, 4, "checking what %s already holds", store.describe());
    final Set<String> already = store.storedIds();
    final List<String> missing = targeted.stream().filter(id -> !already.contains(id)).toList();
    progress.detail("%,d already read, %,d to go", targeted.size() - missing.size(),
        missing.size());

    progress.phase(4, 4, "reading changed methods, three collections per commit");
    final List<CommitComplexity> batch = new ArrayList<>(FLUSH_EVERY);
    final long[] written = {0};
    final CommitComplexitySource.Report scan = source.scan(missing, row -> {
      batch.add(row);
      if (batch.size() == FLUSH_EVERY) {
        written[0] += store.save(batch);
        batch.clear();
      }
    });
    written[0] += store.save(batch);
    return new Report(targeted.size(), targeted.size() - missing.size(), scan, written[0]);
  }
}
