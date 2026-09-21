package app.features;

import java.time.Instant;
import java.util.OptionalDouble;

/**
 * One row of the design matrix: everything known about a task and the developer who did it, as of
 * the moment the task was created.
 *
 * <p>Values are stored raw. The study's logarithms and its standardisation are both fitted
 * decisions -- the scaling uses the training side's own statistics -- so applying either here would
 * bake one modelling choice into what is supposed to be mined data, and would make a second choice
 * cost another pass over the corpus.
 *
 * <p>The individual score {@code H_j} is deliberately absent: its weights are estimated, and a
 * stored composite would freeze an estimate into the inputs that produced it.
 *
 * @param developerKnownToLedger whether the ledger holds any commit by this developer in this
 *     project. It separates a first task, whose zero is real, from a pair nothing is known about,
 *     whose zero is a gap -- both read as zero volume and only one is evidence
 * @param accumulatedComplexity {@code McCC_tot,j}; empty for every row until the per-commit
 *     complexity table exists, and empty rather than zero because an unmeasured history is not a
 *     history of simple code
 * @param taskComplexity mean over the methods the fix changed, which is a description of the
 *     outcome and not of the request
 */
public record FeatureRow(
    String issueId,
    String projectName,
    String canonicalAuthorId,
    Instant createdAt,
    DeveloperHistory developer,
    boolean developerKnownToLedger,
    OptionalDouble accumulatedComplexity,
    long taskLines,
    double taskComplexity,
    double taskNesting,
    Load load,
    TeamContext team,
    IssueAttributes attributes) {

  /** Whether anything was observable about this developer here before the task was created. */
  public boolean developerHadHistory() {
    return developer.commits() > 0;
  }
}
