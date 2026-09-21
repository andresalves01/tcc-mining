package app.features;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the issue said about itself when it was opened.
 *
 * <p>None of these is in the published predictor set. They are carried because they are the only
 * description of a task available at {@code t_i} -- the specified task metrics describe the change
 * that eventually fixed it, which nobody could have read when deciding who to assign.
 *
 * <p>Every component is optional, and absent means the corpus did not record the field rather than
 * that the field was empty. {@code issue_links} in particular is stored only when an issue has
 * links, so reading its absence as zero is a choice about the data and belongs downstream, not
 * here.
 */
public record IssueAttributes(
    boolean found,
    OptionalInt descriptionLength,
    Optional<String> issueType,
    OptionalInt linkedIssues) {

  /** No document for this issue at all, which is different from a document with empty fields. */
  public static IssueAttributes missing() {
    return new IssueAttributes(false, OptionalInt.empty(), Optional.empty(), OptionalInt.empty());
  }
}
