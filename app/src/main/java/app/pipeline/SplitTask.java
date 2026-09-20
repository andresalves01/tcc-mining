package app.pipeline;

import app.labels.IssueLabel;
import app.labels.TemporalSplit;
import app.labels.port.LabelStore;
import java.util.List;

/**
 * Cuts the labels into past and future, and records where each one fell.
 *
 * <p>Reads the labels back from storage rather than re-mining them, so that the split is a cheap,
 * repeatable decision over a fixed set. That matters for the one property the design depends on:
 * the test set must be opened once, and a split that could quietly change between runs would make
 * "once" unverifiable.
 */
public final class SplitTask {

  /** Thrown when there are no labels to cut, so the split would describe nothing. */
  public static final class LabelsEmptyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LabelsEmptyException(final String store) {
      super(store + " holds no labels -- run funnel first");
    }
  }

  public record Report(TemporalSplit.Split split, long written, List<IssueLabel> labels) {}

  private final LabelStore store;

  public SplitTask(final LabelStore store) {
    this.store = store;
  }

  public Report run(final double trainShare, final int minResolved) {
    final List<IssueLabel> labels = store.readAll();
    if (labels.isEmpty()) {
      throw new LabelsEmptyException(store.describe());
    }
    final TemporalSplit.Split split = TemporalSplit.of(labels, trainShare, minResolved);
    return new Report(split, store.saveSplit(split, trainShare, minResolved), labels);
  }
}
