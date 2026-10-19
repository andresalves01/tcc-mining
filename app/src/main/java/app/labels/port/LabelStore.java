package app.labels.port;

import app.labels.IssueLabel;
import java.util.Collection;
import java.util.List;

/** Where the surviving labels are kept, so a fifteen-minute extraction is paid for once. */
public interface LabelStore {

  long save(Collection<IssueLabel> labels);

  /** Everything stored. */
  List<IssueLabel> readAll();

  String describe();
}
