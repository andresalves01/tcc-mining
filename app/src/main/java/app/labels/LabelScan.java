package app.labels;

import java.util.List;

/**
 * What one pass over the corpus produced: the surviving labels and the table that explains what
 * did not survive.
 *
 * <p>The two travel together on purpose. A set of labels alone cannot be audited — it looks the
 * same whether it retained nine tenths of the corpus or a twentieth — and the sequential table is
 * what makes the difference visible.
 */
public record LabelScan(List<IssueLabel> labels, List<FunnelStage> stages, long methodsExtracted) {

  public double methodCoverage() {
    final FunnelStage last = stages.get(stages.size() - 1);
    return last.denominator() == 0 ? 0 : (double) last.retained() / last.denominator();
  }
}
