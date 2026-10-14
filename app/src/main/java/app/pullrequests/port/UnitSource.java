package app.pullrequests.port;

import app.labels.IssueLabel;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where the Yu/Chouchen replication gets its units, read-only.
 *
 * <p>Narrow on purpose. The units are the merged pull requests the earlier pull-request pipeline already built
 * and froze, and this study must not write a byte into that database -- so it is handed a port that
 * cannot, rather than {@link app.labels.port.LabelStore}, which can drop the collection and does so
 * on every save. The type system is a cheaper guarantee than a convention.
 */
public interface UnitSource {

  /** Every unit. The split takes a quantile, and a quantile cannot be taken from a page. */
  List<IssueLabel> readAll();

  /**
   * {@code pull_request.creator_id} for each unit, from the source corpus.
   *
   * <p>A separate call rather than a field on the label because it comes from a different database:
   * the labels are the frozen output of a previous study, and the creator is raw corpus. Joining
   * them here keeps both readable as what they are.
   *
   * @return hex ids keyed by unit id, with no entry where the corpus recorded none
   */
  Map<String, String> creatorByUnit(Collection<String> unitIds);

  String describe();
}
