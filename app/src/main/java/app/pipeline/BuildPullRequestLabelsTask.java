package app.pipeline;

import app.identity.port.PersonRegistry;
import app.labels.ChangedMethod;
import app.labels.IssueLabel;
import app.labels.port.LabelStore;
import app.paper4.MergedPullRequest;
import app.paper4.PullRequestOutcome;
import app.paper4.port.MergedPullRequestSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code issue_label} from merged pull requests instead of resolved issues.
 *
 * <p>Deliberately not a {@link app.labels.port.LabelSource} implementation: that port's contract
 * ({@code scan(pilot, canonicalByPerson, bots)}) is shaped around the issue funnel's own concerns
 * -- linked-commit discovery, method extraction, bot exclusion by commit -- none of which a merged
 * pull request needs. Writing a new, narrower task costs less than forcing a shape that does not
 * fit, and the two stages after this one ({@link SplitTask}, {@link BuildFeaturesTask}) are reused
 * completely unmodified regardless of which task produced the labels they read back.
 *
 * <p><b>Task-level complexity is not computable for this population.</b> The commit whose changed
 * methods a pull request touched cannot be recovered from this corpus for the majority of merged
 * pull requests -- the same broken join documented on {@link MergedPullRequestSource} for the
 * outcome interval also breaks per-method complexity. Every label therefore carries one sentinel
 * {@link ChangedMethod}, {@link #ABSENT_COMPLEXITY}, whose name says so directly in the stored
 * document. It exists only so {@link IssueLabel#meanMccc()} and {@link IssueLabel#meanNl()} do not
 * throw -- both average to exactly zero across every label, which is a constant column that the
 * standardiser used by {@code app.model} already drops by name, the same mechanism that already
 * handles {@code McCC_tot,j} before the complexity table existed.
 */
public final class BuildPullRequestLabelsTask {

  /** Named so a caller can tell "nothing to bridge" from every other kind of failure. */
  public static final class RegistryEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RegistryEmptyException(final String registry) {
      super(registry + " holds no canonical identities -- this reads an already-built registry, "
          + "not one this stage constructs; point LEDGER_DB at a database where "
          + "reconstruct-people, harvest-jira and canonicalize have already run");
    }
  }

  /**
   * A pull request whose commit author has no canonical identity carries a {@code null} value in
   * {@code MergedPullRequest.authorId()} already -- see that type for why zero and multiple
   * authors are folded into the same {@code null}. This report separates the two only for the
   * count printed to the operator; the label set itself never distinguishes them.
   *
   * @param scanned every merged pull request the source returned, before any exclusion
   * @param noSingleAuthor {@code authorId() == null}: no commit carried an author id, or more than
   *     one distinct author was linked
   * @param unresolvedIdentity a single commit author existed, but the registry holds no canonical
   *     id for them
   * @param written rows the store reports as written
   */
  public record Report(
      int scanned, int noSingleAuthor, int unresolvedIdentity, long written,
      PullRequestOutcome outcome, int withoutReview, int nonPositiveInterval) {}

  /** The one place this stage says what its complexity columns mean, visible in raw documents too. */
  public static final ChangedMethod ABSENT_COMPLEXITY =
      new ChangedMethod("(complexidade de tarefa nao computavel para pull requests)", 0.0, 0.0);

  private final MergedPullRequestSource source;
  private final LabelStore store;
  private final PersonRegistry registry;
  private final PullRequestOutcome outcome;

  public BuildPullRequestLabelsTask(
      final MergedPullRequestSource source, final LabelStore store, final PersonRegistry registry) {
    this(source, store, registry, PullRequestOutcome.OPEN_TO_MERGE);
  }

  public BuildPullRequestLabelsTask(final MergedPullRequestSource source, final LabelStore store,
      final PersonRegistry registry, final PullRequestOutcome outcome) {
    this.source = source;
    this.store = store;
    this.registry = registry;
    this.outcome = outcome;
  }

  public Report run() {
    final Map<String, String> canonical = registry.readCanonical();
    if (canonical.isEmpty()) {
      throw new RegistryEmptyException(registry.describe());
    }

    final List<MergedPullRequest> merged = source.scan();
    final List<IssueLabel> labels = new ArrayList<>(merged.size());
    int noSingleAuthor = 0;
    int unresolvedIdentity = 0;
    int withoutReview = 0;
    int nonPositiveInterval = 0;
    for (final MergedPullRequest pr : merged) {
      if (pr.authorId() == null) {
        noSingleAuthor++;
        continue;
      }
      final String developer = canonical.get(pr.authorId());
      if (developer == null) {
        unresolvedIdentity++;
        continue;
      }
      if (outcome == PullRequestOutcome.REVIEW_TO_MERGE && !pr.reviewed()) {
        // Sem revisao a fronteira e o proprio merge, e o intervalo seria zero. Excluir e honesto;
        // escorar num epsilon seria inventar trabalho que o registro nao mostra.
        withoutReview++;
        continue;
      }
      if (!pr.endOf(outcome).isAfter(pr.startOf(outcome))) {
        nonPositiveInterval++;
        continue;
      }
      labels.add(toLabel(pr, developer, outcome));
    }

    final long written = store.save(labels);
    return new Report(merged.size(), noSingleAuthor, unresolvedIdentity, written,
        outcome, withoutReview, nonPositiveInterval);
  }

  private static IssueLabel toLabel(final MergedPullRequest pr, final String developer,
      final PullRequestOutcome outcome) {
    return new IssueLabel(
        pr.pullRequestId(),
        pr.projectName(),
        pr.startOf(outcome),
        pr.endOf(outcome),
        developer,
        // Nao e o primeiro commit -- essa data nao e recuperavel nesta populacao, ver
        // MergedPullRequestSource. Carrega a fronteira de revisao, que e o que esta posicao
        // significa aqui: um instante intermediario entre as duas pontas do intervalo. Nenhum
        // calculo a le -- nem TemporalSplit nem MongoObservations -- so o documento gravado.
        pr.reviewAt(),
        pr.linkedCommits(),
        pr.javaLinesAdded(),
        pr.javaLinesDeleted(),
        List.of(ABSENT_COMPLEXITY));
  }
}
