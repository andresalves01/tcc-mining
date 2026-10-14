package app.pipeline;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import app.features.AsOfLedger;
import app.features.CommitComplexity;
import app.features.FeatureMatrix;
import app.features.IssueAttributes;
import app.features.OpenIssues;
import app.features.port.CommitComplexityStore;
import app.features.port.FeatureStore;
import app.pullrequests.PullRequestExtras;
import app.pullrequests.port.PullRequestExtraSource;
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
 *
 * <p>The per-commit complexity table is optional. When it is missing the accumulated complexity and
 * the team's version of it are written as absent rather than zero, and the run says so — building
 * that table is a separate stage because it costs hours and this one costs seconds.
 */
public final class BuildFeaturesTask {

  /** Thrown rather than writing a matrix of zeros, which reads as a corpus with no history. */
  public static final class LedgerEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LedgerEmptyException(final String ledger) {
      super(ledger + " holds no contributions -- run build-ledger before building features");
    }
  }

  public record Report(FeatureMatrix.Scan scan, long written, int labels,
      boolean withComplexity) {}

  private final LabelStore labels;
  private final ContributionLedger ledger;
  private final CommitComplexityStore complexity;
  private final IssueAttributeSource attributes;
  private final FeatureStore store;
  private final Progress progress;
  private final ContributionLedger presence;
  private final PullRequestExtraSource extras;

  public BuildFeaturesTask(
      final LabelStore labels,
      final ContributionLedger ledger,
      final CommitComplexityStore complexity,
      final IssueAttributeSource attributes,
      final FeatureStore store,
      final Progress progress) {

    this(labels, ledger, complexity, attributes, store, progress, null);
  }

  /**
   * @param presence where "who was active in this project inside the window" is read from, when
   *     that is not the same observation the accumulated history comes from. {@code null} means it
   *     is the ledger itself -- which is what every stage reading a single mined history does, and
   *     is behaviour-identical to the constructor without this argument. See {@link AsOfLedger}'s
   *     three-argument constructor for why the two can diverge
   */
  public BuildFeaturesTask(
      final LabelStore labels,
      final ContributionLedger ledger,
      final CommitComplexityStore complexity,
      final IssueAttributeSource attributes,
      final FeatureStore store,
      final Progress progress,
      final ContributionLedger presence) {

    this(labels, ledger, complexity, attributes, store, progress, presence, null);
  }

  /**
   * @param extras preditores que so existem para pull requests, ou {@code null} quando a populacao
   *     nao tem nenhum -- que e todo rotulo de issue
   */
  public BuildFeaturesTask(
      final LabelStore labels,
      final ContributionLedger ledger,
      final CommitComplexityStore complexity,
      final IssueAttributeSource attributes,
      final FeatureStore store,
      final Progress progress,
      final ContributionLedger presence,
      final PullRequestExtraSource extras) {

    this.labels = labels;
    this.ledger = ledger;
    this.complexity = complexity;
    this.attributes = attributes;
    this.store = store;
    this.progress = progress;
    this.presence = presence;
    this.extras = extras;
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
    final Map<String, CommitComplexity> perCommit = complexity.readAll();
    final List<Contribution> active = presence == null ? contributions : presence.readAll();
    final AsOfLedger asOf = new AsOfLedger(contributions, perCommit, active);
    final OpenIssues open = new OpenIssues(stored);
    progress.detail("%,d contributions indexed", contributions.size());
    if (presence != null) {
      progress.detail("%,d rows of activity from %s decide the active team instead",
          active.size(), presence.describe());
    }
    progress.detail(perCommit.isEmpty()
        ? "no per-commit complexity in " + complexity.describe()
            + " -- McCC_tot, mu_M and s_M will be absent; run complexity to build it"
        : String.format("%,d commits carry complexity", perCommit.size()));

    progress.phase(3, 5, "reading what each issue said when it was opened");
    final Map<String, IssueAttributes> described =
        attributes.of(stored.stream().map(IssueLabel::issueId).toList());
    progress.detail("%,d of %,d issues found in the source corpus", described.size(),
        stored.size());

    final Map<String, Map<String, Double>> extraValues = new java.util.HashMap<>();
    if (extras != null) {
      progress.detail("reading %s", extras.describe());
      extras.of(stored.stream().map(IssueLabel::issueId).toList())
          .forEach((id, value) -> extraValues.put(id, value.values()));
      progress.detail("%,d of %,d labels carry pull-request predictors",
          extraValues.size(), stored.size());
    }

    progress.phase(4, 5, "building the matrix as of each creation instant");
    final FeatureMatrix.Scan scan =
        FeatureMatrix.of(stored, asOf, open, described, teamWindow, extraValues);

    progress.phase(5, 5, "writing %s", store.describe());
    return new Report(scan, store.save(scan.rows(), teamWindow), stored.size(),
        !perCommit.isEmpty());
  }
}
