package app.paper4;

import java.time.Instant;

/**
 * One merged pull request, before identity is resolved and before it becomes an
 * {@link app.labels.IssueLabel}.
 *
 * <p>Deliberately carries the raw, unresolved author id rather than a canonical one: resolution
 * happens once, in {@link app.pipeline.BuildPullRequestLabelsTask}, against the same
 * {@code personId -> canonicalPersonId} map every other stage in this codebase resolves through.
 * An adapter that resolved identity itself would duplicate that map's construction and could
 * silently disagree with it.
 *
 * @param authorId the single distinct commit author across every commit linked to this pull
 *     request, from {@code pull_request_commit.author_id} -- a field the GitHub API reports
 *     directly on each commit record, so it costs nothing to read even where the commit's own SHA
 *     has no match in the mined repository. {@code null} when the pull request had no commit with
 *     an author id, or more than one distinct author: both are exclusions, carried as one field
 *     rather than two, because nothing downstream needs to tell them apart
 * @param openedAt when the pull request was created. The reference instant every accumulated
 *     predictor is read as of, and the left edge of the outcome interval
 * @param mergedAt the earliest {@code merged} event. The right edge of the outcome interval
 * @param reviewAt the earliest review submitted on this pull request, falling back to
 *     {@code mergedAt} when none was recorded -- the rule Andre set. Never null, so an outcome
 *     defined against it is defined for every row; whether the fallback rows belong in a given
 *     outcome is a question for whoever picks it, and {@link #reviewed()} is how they tell
 * @param javaLinesAdded sum of {@code pull_request_file.additions} over paths ending {@code .java}
 * @param javaLinesDeleted sum of {@code pull_request_file.deletions} over paths ending {@code
 *     .java}
 * @param linkedCommits how many {@code pull_request_commit} rows this pull request has, carried for
 *     the audit trail only -- nothing in the design matrix reads it
 */
public record MergedPullRequest(
    String pullRequestId,
    String projectName,
    String authorId,
    Instant openedAt,
    Instant mergedAt,
    Instant reviewAt,
    boolean reviewed,
    long javaLinesAdded,
    long javaLinesDeleted,
    int linkedCommits,
    int descriptionLength) {

  /** Fractional days, matching {@code IssueLabel.ttcDays()}: zero is possible, {@code ln(0)} is not. */
  public double durationDays() {
    return java.time.Duration.between(openedAt, mergedAt).toMillis() / 86_400_000.0;
  }

  /** The interval {@code outcome} names, as a pair of instants. */
  public Instant startOf(final PullRequestOutcome outcome) {
    return outcome == PullRequestOutcome.REVIEW_TO_MERGE ? reviewAt : openedAt;
  }

  public Instant endOf(final PullRequestOutcome outcome) {
    return outcome == PullRequestOutcome.OPEN_TO_REVIEW ? reviewAt : mergedAt;
  }
}
