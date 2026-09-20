package app.identity;

import java.util.List;

/**
 * A Jira-to-git link that was offered by the username rule and declined.
 *
 * <p>Kept rather than discarded because a refusal is a claim about the data: it asserts that a
 * handle names more than one person. Publishing the refusals alongside the equivalence relation lets
 * a reader check that assertion instead of taking the merge rule on faith — and if a refusal turns
 * out to be wrong, it is visible as a false split rather than hidden inside a fused person.
 *
 * @param names the distinct full names that made the handle ambiguous, which is the evidence for
 *     the refusal
 */
public record RefusedBridge(String jiraPersonId, String handle, List<String> gitPersonIds,
    List<String> names) {}
