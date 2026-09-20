package app.labels.port;

import app.labels.IssueLabel;
import app.labels.TemporalSplit;
import java.util.Collection;
import java.util.List;

/** Where the surviving labels are kept, so a fifteen-minute extraction is paid for once. */
public interface LabelStore {

  long save(Collection<IssueLabel> labels);

  /** Everything stored. The split needs the whole set: a quantile cannot be taken from a page. */
  List<IssueLabel> readAll();

  /**
   * Records which side of the split each label fell on, and the cut that decided it.
   *
   * <p>The assignment goes onto the labels themselves, because it is only meaningful next to the
   * row it describes. The cut instants and the parameters that produced them go into their own
   * document, because they are a property of the run rather than of any label — and because a
   * consumer that cannot read the cut back is a consumer that will end up writing the date into
   * itself, which silently measures the wrong thing the first time the split moves.
   */
  long saveSplit(TemporalSplit.Split split, double trainShare, int minResolved);

  String describe();
}
