package app.pullrequests;

import java.time.Duration;
import java.time.Instant;

/**
 * One merged pull request as the Yu/Chouchen replication sees it.
 *
 * <p>Deliberately a different type from {@link app.labels.IssueLabel}, which is what the label
 * store hands back, because the two studies key on different people. A label carries
 * {@code canonicalAuthorId} -- the commit author, resolved through the identity registry, which is
 * what an experiment that orders developers needs. This study keys on
 * {@code pull_request.creator_id}, present on every pull request in the corpus and never resolved
 * against anything: it is whoever opened the request, which is the person Yu and Chouchen describe.
 *
 * <p>The two are joined rather than substituted. Nothing here reads the canonical id, and nothing
 * in the earlier pull-request pipeline reads {@code creatorId}.
 *
 * @param pullRequestId the corpus id, also the key into {@code pull_request}
 * @param creatorId who opened it. An opaque key: {@code people} is empty in the published corpus,
 *     so this groups rows and describes nobody
 * @param openedAt {@code t0}. Every historical window is half-open on it, {@code [start, t0)}
 * @param mergedAt the right edge of the outcome
 */
public record Unit(
    String pullRequestId, String project, String creatorId, Instant openedAt, Instant mergedAt)
    implements Outcome {

  /**
   * The outcome, in hours, which is the scale the specification reports MAE on.
   *
   * <p>Hours rather than days because the handout's primary table is in hours and converts to days
   * for reading, and converting once here keeps the two from drifting apart by a rounding.
   */
  public double durationHours() {
    return Duration.between(openedAt, mergedAt).toMillis() / 3_600_000.0;
  }
}
