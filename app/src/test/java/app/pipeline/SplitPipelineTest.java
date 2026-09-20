package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.labels.IssueLabel;
import app.labels.TemporalSplit;
import app.labels.port.LabelStore;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the split use case against an in-memory store, with no MongoDB. */
class SplitPipelineTest {

  /** A store that was never filled, which is what running split before funnel looks like. */
  private static final class EmptyStore implements LabelStore {

    @Override
    public long save(final Collection<IssueLabel> labels) {
      return 0;
    }

    @Override
    public List<IssueLabel> readAll() {
      return List.of();
    }

    @Override
    public long saveSplit(
        final TemporalSplit.Split split, final double trainShare, final int minResolved) {
      throw new AssertionError("nothing may be written when there is nothing to split");
    }

    @Override
    public String describe() {
      return "target.issue_label";
    }
  }

  @Test
  @DisplayName("splitting an empty store refuses by name instead of throwing a raw exception")
  void refusesToSplitNothing() {
    // The cut is computed from a quantile of issue creation, so an empty input would produce a
    // split_meta recording a date derived from no data -- worse than a refusal, because later
    // stages read that date back and trust it.
    final SplitTask task = new SplitTask(new EmptyStore());

    final SplitTask.LabelsEmptyException refusal =
        assertThrows(SplitTask.LabelsEmptyException.class, () -> task.run(0.70, 5));

    assertTrue(refusal.getMessage().contains("target.issue_label"),
        "the refusal names the store it found empty");
    assertTrue(refusal.getMessage().contains("funnel"),
        "a refusal that does not name the missing stage leaves the operator guessing");
  }
}
