package app.pipeline;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import app.features.AsOfLedger;
import app.features.FeatureMatrix;
import app.features.IssueAttributes;
import app.features.OpenIssues;
import app.features.port.FeatureStore;
import app.features.port.IssueAttributeSource;
import app.labels.IssueLabel;
import app.labels.port.LabelStore;
import app.pipeline.port.Progress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds one predictor row per stored label, every quantity read as of the task's creation.
 *
 * <p>Reads the labels and the ledger back rather than re-mining either, which keeps the stage cheap
 * enough to re-run whenever a definition changes. That matters more here than anywhere else in the
 * pipeline: the predictor set is the part of the study still being argued about, and a matrix that
 * costs an hour to rebuild is a matrix whose definitions stop being questioned.
 *
 * <p>No duration bound is applied. Whether the sample should be restricted to a coherent band is an
 * open question of method, and answering it by filtering here would make the unrestricted arm
 * impossible to report beside the restricted one.
 */
public final class BuildFeaturesTask {

  /** Thrown rather than writing a matrix of zeros, which reads as a corpus with no history. */
  public static final class LedgerEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LedgerEmptyException(final String ledger) {
      super(ledger + " holds no contributions -- run build-ledger before building features");
    }
  }

  public record Report(FeatureMatrix.Scan scan, long written, int labels) {}

  private final LabelStore labels;
  private final ContributionLedger ledger;
  private final IssueAttributeSource attributes;
  private final FeatureStore store;
  private final Progress progress;

  public BuildFeaturesTask(
      final LabelStore labels,
      final ContributionLedger ledger,
      final IssueAttributeSource attributes,
      final FeatureStore store,
      final Progress progress) {

    this.labels = labels;
    this.ledger = ledger;
    this.attributes = attributes;
    this.store = store;
    this.progress = progress;
  }

  public Report run(final Duration teamWindow) {
    progress.phase(1, 5, "reading the labels back from %s", labels.describe());
    final List<IssueLabel> stored = labels.readAll();
    if (stored.isEmpty()) {
      throw new SplitTask.LabelsEmptyException(labels.describe());
    }
    progress.detail("%,d labels", stored.size());

    progress.phase(2, 5, "indexing %s by (developer, project)", ledger.describe());
    final List<Contribution> contributions = ledger.readAll();
    if (contributions.isEmpty()) {
      throw new LedgerEmptyException(ledger.describe());
    }
    final AsOfLedger asOf = new AsOfLedger(contributions);
    final OpenIssues open = new OpenIssues(stored);
    progress.detail("%,d contributions indexed", contributions.size());

    progress.phase(3, 5, "reading what each issue said when it was opened");
    final Map<String, IssueAttributes> described =
        attributes.of(stored.stream().map(IssueLabel::issueId).toList());
    progress.detail("%,d of %,d issues found in the source corpus", described.size(),
        stored.size());

    progress.phase(4, 5, "building the matrix as of each creation instant");
    final FeatureMatrix.Scan scan =
        FeatureMatrix.of(stored, asOf, open, described, teamWindow);

    progress.phase(5, 5, "writing %s", store.describe());
    return new Report(scan, store.save(scan.rows(), teamWindow), stored.size());
  }
}
