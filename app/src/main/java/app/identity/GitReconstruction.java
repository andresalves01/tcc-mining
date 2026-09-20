package app.identity;

import java.util.List;
import java.util.Map;

/**
 * What reading the bundled repositories produced.
 *
 * @param identities {@code personId -> (name, email)}
 * @param authoredCommits how many commits each person authored, for reporting and bot triage
 * @param commitsScanned commit documents examined in the source corpus
 * @param unresolvedCommits commits whose SHA was absent from the bundled history, i.e. history
 *     rewritten after the 2021 mining run
 * @param bijectionViolations person ids observed with more than one identity. Must be empty:
 *     {@code pycoshark} declares {@code People.email} as {@code unique_with=["name"]}, so one
 *     {@code ObjectId} denotes exactly one identity. A non-empty list means that guarantee does not
 *     hold here and every downstream merge would rest on a false premise.
 */
public record GitReconstruction(
    Map<String, Identity> identities,
    Map<String, Integer> authoredCommits,
    int commitsScanned,
    int unresolvedCommits,
    List<String> bijectionViolations) {

  public boolean isBijective() {
    return bijectionViolations.isEmpty();
  }

  public double unresolvedShare() {
    return commitsScanned == 0 ? 0 : (double) unresolvedCommits / commitsScanned;
  }
}
