package app.identity.port;

import app.identity.JiraResolution;
import java.util.Set;

/**
 * Supplies developer identities recovered from the issue tracker.
 *
 * <p>
 * Needed because a label is keyed on {@code issue.assignee_id}, which shares
 * almost nothing with
 * the git author space that {@code H_j} is accumulated over.
 */
public interface JiraIdentitySource {

  /**
   * @param alreadyResolved person ids already known, so their issues are not
   *                        requested again
   */
  JiraResolution resolve(final Set<String> alreadyResolved) throws Exception;
}
