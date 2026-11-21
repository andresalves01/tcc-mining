package app.pipeline;

import app.issues.FirstCommit;
import app.issues.SplitRule;
import app.issues.SupportingNumbers;
import app.issues.Universe;
import app.pipeline.port.Progress;
import app.issues.port.StudySource;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The numbers that support the text of the first-commit study, read only: the filter's counts
 * over the audit's base ({@link IssuesFirstCommitAuditTask}), and the worked examples chosen among
 * the issues of the outer training that teach ({@link SupportingNumbers#choose}). The audit's draw
 * gives the outer training and the cap that draw learned. The examples come from its issues whose
 * outer verdict is {@link app.issues.Verdict#TRAIN}: under the revised rule the issues crossing
 * the outer cut teach nothing and are left out, so no example shows a target the model never
 * learns from; under every other rule no issue crosses, and the whole outer training is read. The
 * outer test's issues are never explained, so no target of the outer test is computed here.
 */
public final class IssuesFirstCommitSupportingNumbersTask {

  /**
   * One worked example: the issue as the rules read it, its verdict, and how its target is made.
   */
  public record Example(SupportingNumbers.Pick pick, FirstCommit.Issue issue,
      FirstCommit.Verdict verdict) {}

  /**
   * @param counts the filter's counts over the Fixed issues with a commit that counts
   * @param training the issues of the outer training the examples were chosen among: those that
   *     teach at the outer level
   * @param crossing the outer training issues left out because they cross the outer cut; 0 under
   *     every rule but the revised one
   * @param threshold the cap the examples' waits are cut at
   * @param examples the chosen issues, in the order of their criteria
   * @param rule the split rule that drew the outer training
   * @param waitFenceK the k of the wait fence the cap was learned with
   */
  public record Report(SupportingNumbers.Counts counts, int training, int crossing,
      FirstCommit.Threshold threshold, List<Example> examples, SplitRule rule,
      double waitFenceK) {}

  private static final int PHASES = 5;

  private final StudySource source;
  private final Progress progress;
  private final SplitRule rule;
  private final double waitK;

  public IssuesFirstCommitSupportingNumbersTask(final StudySource source, final Progress progress,
      final SplitRule rule, final double waitK) {
    this.source = source;
    this.progress = progress;
    this.rule = rule;
    this.waitK = waitK;
  }

  public Report run() {
    final IssuesFirstCommitAuditTask.Base b =
        new IssuesFirstCommitAuditTask(source, progress, rule, waitK).base(PHASES);

    progress.phase(4, PHASES, "counts of the filter");
    final Universe universe = b.universe();
    final SupportingNumbers.Counts counts = SupportingNumbers.count(b.issues().values(), id -> {
      final Universe.Unit started = universe.started(id);
      return started == null ? null : started.start();
    });

    progress.phase(5, PHASES, "worked examples, from the outer training alone");
    final IssuesFirstCommitAuditTask.Drawn drawn =
        IssuesFirstCommitAuditTask.draw(b.issues(), FirstCommit.SHARE, b.boundary(), rule, waitK);
    // the cap the draw learned, under this run's rule and k
    final int cap = drawn.threshold().cap();
    // the outer training issues that teach, by verdict: neither an issue of the outer test nor
    // one crossing the outer cut is explained
    final List<String> training = new TreeSet<>(drawn.parts().outer().keySet()).stream()
        .filter(id -> drawn.parts().outerVerdict(id) == app.issues.Verdict.TRAIN)
        .toList();
    final int crossing = drawn.parts().outerCrossing().size();
    final List<SupportingNumbers.Entry> entries = new ArrayList<>(training.size());
    for (final String id : training) {
      final FirstCommit.Issue issue = b.issues().get(id);
      entries.add(new SupportingNumbers.Entry(id, issue.key(),
          FirstCommit.explain(issue, drawn.verdicts().get(id), cap)));
    }
    final List<Example> examples = new ArrayList<>();
    for (final SupportingNumbers.Pick pick : SupportingNumbers.choose(entries)) {
      examples.add(new Example(pick, b.issues().get(pick.entry().id()),
          drawn.verdicts().get(pick.entry().id())));
    }
    return new Report(counts, training.size(), crossing, drawn.threshold(),
        List.copyOf(examples), rule, waitK);
  }
}
