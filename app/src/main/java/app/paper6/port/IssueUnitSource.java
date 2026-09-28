package app.paper6.port;

import app.labels.IssueLabel;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where the transposition gets its issues, read-only.
 *
 * <p>The labels are the frozen output of the thesis pipeline and this study must not write a byte
 * into that database, so it gets a port that cannot -- not the label store, which drops the
 * collection on every save.
 */
public interface IssueUnitSource {

  /**
   * One label, with what the transposition needs beside it.
   *
   * @param reporterId {@code issue.reporter_id}; {@code creator_id} where the reporter is missing;
   *     empty where both are, which then reads as a person with no recorded history
   * @param storedSplit the partition the thesis pipeline wrote, kept only so the population check
   *     can confirm the split rule reproduces it
   */
  record Label(IssueLabel label, String reporterId, String key, String storedSplit,
      boolean reporterFromCreator) {}

  List<Label> readAll();

  /**
   * The author's stored columns, from {@code issue_features}, by issue. Also carries the three
   * creation columns at their stored final state -- {@code extra_desc_length},
   * {@code extra_linked_issues}, and the issue type coded as the extractor codes it -- so the
   * difference between them and the replayed values can be measured.
   */
  Map<String, Map<String, Double>> ownColumns(Collection<String> issueIds);

  String describe();
}
