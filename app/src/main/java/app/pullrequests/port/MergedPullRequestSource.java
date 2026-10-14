package app.pullrequests.port;

import app.pullrequests.MergedPullRequest;
import java.util.List;

/**
 * Where merged pull requests are read from. A port, so the label-building task never names a
 * storage technology, mirroring {@link app.labels.port.LabelSource}.
 */
public interface MergedPullRequestSource {

  /** Every merged pull request whose pull request system resolves to a named project. */
  List<MergedPullRequest> scan();

  /** Human-readable location, for logging. */
  String describe();
}
