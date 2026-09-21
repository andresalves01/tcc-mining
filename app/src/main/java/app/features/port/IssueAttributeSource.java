package app.features.port;

import app.features.IssueAttributes;
import java.util.Collection;
import java.util.Map;

/**
 * What the source corpus recorded about an issue when it was opened.
 *
 * <p>Its own port because it is the only part of this stage that reaches back into the published
 * release; everything else is derived from what earlier stages already wrote.
 */
public interface IssueAttributeSource {

  /** Keyed by issue id. An id with no document is absent from the map, not mapped to blanks. */
  Map<String, IssueAttributes> of(Collection<String> issueIds);
}
