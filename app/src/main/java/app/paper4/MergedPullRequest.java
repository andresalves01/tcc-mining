package app.paper4;

import app.labels.ExamOrigin;
import java.time.Instant;
import java.util.Optional;

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
 * @param authorId who this pull request is attributed to. First choice is the single distinct
 *     commit author across every commit linked to it, from {@code pull_request_commit.author_id}
 *     -- a field the GitHub API reports directly on each commit record, so it costs nothing to
 *     read even where the commit's own SHA has no match in the mined repository. When that is
 *     unavailable, {@code pull_request.creator_id} is the fallback, and {@link #identityFromCreator}
 *     says so on that row. {@code null} only when neither exists
 * @param identityFromCreator whether the fallback was used. Carried rather than hidden because the
 *     two are not the same claim: the commit author wrote the code, the creator merely opened the
 *     request, and on a pull request opened by a maintainer on someone else's behalf they differ.
 *     Keeping the flag lets every result be recomputed with and without the recovered rows
 * @param openedAt when the pull request was created. The reference instant every accumulated
 *     predictor is read as of, and the left edge of the outcome interval
 * @param mergedAt the earliest {@code merged} event. The right edge of the outcome interval
 * @param examinedAt the earliest external examination inside {@code [openedAt, mergedAt]}: the
 *     first formal review, or the first comment from someone who is not a commit author of this
 *     pull request, whichever came first. <b>Empty</b> where the corpus records neither, which is
 *     21,9% of merged pull requests -- and empty rather than falling back to the merge, because a
 *     fallback would score those rows as waiting their whole life, which asserts that nobody ever
 *     looked
 * @param examOrigin which of the two recognised it, empty on the same rows
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
    boolean identityFromCreator,
    Instant openedAt,
    Instant mergedAt,
    Optional<Instant> examinedAt,
    Optional<ExamOrigin> examOrigin,
    long javaLinesAdded,
    long javaLinesDeleted,
    int linkedCommits,
    int descriptionLength) {

  /** Fractional days, matching {@code IssueLabel.ttcDays()}: zero is possible, {@code ln(0)} is not. */
  public double durationDays() {
    return java.time.Duration.between(openedAt, mergedAt).toMillis() / 86_400_000.0;
  }

  /** Whether the decomposition into waiting and the cycle after it is defined for this row. */
  public boolean examined() {
    return examinedAt.isPresent();
  }
}
