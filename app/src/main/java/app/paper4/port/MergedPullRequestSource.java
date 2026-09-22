package app.paper4.port;

import app.paper4.MergedPullRequest;
import java.util.List;

/**
 * Where merged pull requests are read from. A port, so the label-building task never names a
 * storage technology, mirroring {@link app.labels.port.LabelSource}.
 */
public interface MergedPullRequestSource {

  /**
   * Every merged pull request in a project this study already has issue labels for.
   *
   * <p>The restriction to labelled projects is applied here rather than in the task above it,
   * because which projects qualify is a fact about two source collections in two different
   * databases -- {@code pull_request_system.project_id} and {@code issue_label.project} -- and an
   * adapter is exactly where that kind of cross-collection lookup belongs.
   */
  List<MergedPullRequest> scan();

  /** Human-readable location, for logging. */
  String describe();
}
