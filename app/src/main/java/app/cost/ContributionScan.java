package app.cost;

import java.util.List;

/**
 * What one pass over the corpus produced, and what it had to leave out.
 *
 * <p>The counters are not decoration. A ledger is a silent artefact — it looks equally plausible
 * whether it captured every commit or a third of them — so the discards are reported alongside it
 * and each has a distinct cause worth telling apart: a merge is excluded by decision, an
 * unresolvable author is a gap in the identity reconstruction, and a commit with no file action is
 * a gap in the corpus itself.
 */
public record ContributionScan(
    List<Contribution> contributions,
    long commitsScanned,
    long mergesSkipped,
    long authorsUnresolved,
    long fileActionsRead,
    long commitsWithoutFileAction,
    long botContributions) {

  public long linesAdded() {
    return contributions.stream().mapToLong(Contribution::linesAdded).sum();
  }

  public long linesDeleted() {
    return contributions.stream().mapToLong(Contribution::linesDeleted).sum();
  }

  public long javaLinesAdded() {
    return contributions.stream().mapToLong(Contribution::javaLinesAdded).sum();
  }

  public long javaLinesDeleted() {
    return contributions.stream().mapToLong(Contribution::javaLinesDeleted).sum();
  }

  /**
   * Lines touched in {@code .java} files: added plus removed.
   *
   * <p>Reported rather than left to be derived because this, and not the addition count, is the
   * volume the model ranks developers by — a log that reports only additions invites the reader to
   * check the wrong number against the paper.
   */
  public long javaVolume() {
    return javaLinesAdded() + javaLinesDeleted();
  }

  public double unresolvedShare() {
    return commitsScanned == 0 ? 0 : (double) authorsUnresolved / commitsScanned;
  }
}
