package app.pipeline;

import app.issues.FirstCommit;
import app.issues.ProjectSplit;
import app.issues.Universe;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The extraction of the first-commit population ({@link Population#FIRST_COMMIT}): the same
 * population, split, cap and target as the audit ({@link IssuesFirstCommitAuditTask}), and the
 * columns of the In Progress extraction read at the first commit instead of the first In Progress.
 *
 * <p><b>Population and split.</b> The audit's base and selection at the limit of {@link
 * FirstCommit#SHARE}, its split ({@link ProjectSplit}) over the issues of a target of at least one
 * business day, and its cap, learned on the inner fitting's waits alone and then applied, frozen,
 * to every drawn issue. The cache holds the drawn issues only.
 *
 * <p><b>Columns.</b> Every column of the In Progress extraction, read at the first commit that
 * counts: the issue's state undone to that instant, the executor the assignee then, or else the
 * first commit's author, the participants those who commented on or changed the issue before it.
 * The time-travel gate is checked at the same instant. After those, the target and its parts
 * ({@link #TARGET_COLUMNS}).
 */
public final class IssuesFirstCommitExtractTask {

  /** The target in business days: {@link FirstCommit.Count#total}. */
  public static final String TARGET = "FC_target_days";
  public static final String IMPLEMENTATION = "FC_implementation_days";
  public static final String TAIL = "FC_tail_days";
  public static final String CAPPED = "FC_capped_days";
  public static final String MARKS = "FC_marks";
  public static final String PATCHES = "FC_patches";
  /** The UTC hour the target's dates turn at: the first commit's author's day boundary. */
  public static final String DAY_BOUNDARY = "FC_day_boundary_hour";

  /** The columns written after the extracted ones, in this order. */
  public static final List<String> TARGET_COLUMNS =
      List.of(TARGET, IMPLEMENTATION, TAIL, CAPPED, MARKS, PATCHES, DAY_BOUNDARY);

  /**
   * The extraction and what the first-commit population adds to it; of the outer test only the
   * size, in {@code extraction.split()}.
   *
   * @param extraction the columns, the gate and the split; its {@code started} is the issues past
   *     every rule but the target's, its {@code zeroDays} those of a target of 0
   * @param base the issues resolved as Fixed at their first resolution
   * @param funnel what is left after each rule, as the audit prints it
   * @param threshold the cap learned on the inner fitting
   * @param training the target of the outer training
   */
  public record Report(IssuesInProgressExtractTask.Report extraction, int base,
      List<IssuesFirstCommitAuditTask.Step> funnel, FirstCommit.Threshold threshold,
      FirstCommit.Summary training) {}

  private static final int PHASES = 6;

  private final StudySource source;
  private final Progress progress;

  public IssuesFirstCommitExtractTask(final StudySource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  public Report run() {
    final IssuesFirstCommitAuditTask.Base b =
        new IssuesFirstCommitAuditTask(source, progress).base(PHASES);

    progress.phase(4, PHASES, "selection, split and cap");
    final IssuesFirstCommitAuditTask.Drawn drawn =
        IssuesFirstCommitAuditTask.draw(b.issues(), FirstCommit.SHARE, b.boundary());
    final int cap = drawn.threshold().cap();
    final List<Universe.Unit> units = new ArrayList<>(drawn.parts().outer().size());
    final Map<String, String> projects = new HashMap<>(drawn.parts().outer().size() * 2);
    final Map<String, String> authors = new HashMap<>(drawn.parts().outer().size() * 2);
    final Map<String, Map<String, Double>> target = new HashMap<>(drawn.parts().outer().size() * 2);
    final List<Integer> training = new ArrayList<>();
    int zero = 0;
    for (final Universe.Unit u : b.units()) {
      final FirstCommit.Verdict v = drawn.verdicts().get(u.id());
      if (v.failed() == FirstCommit.Rule.ZERO) {
        zero++;
      }
      final Part outer = drawn.parts().outer().get(u.id());
      if (outer == null) {
        continue;
      }
      final FirstCommit.Code first = v.kept().getFirst();
      units.add(new Universe.Unit(u.id(), u.key(), u.project(), u.reporter(), u.created(),
          u.resolved(), u.reason(), u.moved(), first.at(), null));
      projects.put(u.id(), u.project());
      authors.put(u.id(), first.author());
      final FirstCommit.Count c = FirstCommit.count(b.issues().get(u.id()), v, cap);
      if (c.total() < 1) {
        throw new IllegalStateException(u.id() + " was drawn with a target of " + c.total());
      }
      if (outer == Part.TRAIN) {
        training.add(c.total());
        if (c.total() != drawn.training().get(u.id()).total()) {
          throw new IllegalStateException(u.id() + ": the target differs from the audit's");
        }
      }
      final Map<String, Double> row = new LinkedHashMap<>();
      row.put(TARGET, (double) c.total());
      row.put(IMPLEMENTATION, (double) c.implementation());
      row.put(TAIL, (double) c.tail());
      row.put(CAPPED, (double) c.capped());
      row.put(MARKS, (double) c.marks());
      row.put(PATCHES, (double) c.patches());
      row.put(DAY_BOUNDARY, (double) v.hour());
      target.put(u.id(), row);
    }

    progress.phase(5, PHASES, "columns of %,d issues at the first commit", units.size());
    final Set<String> studied = new TreeSet<>(projects.keySet());
    final IssuesInProgressExtractTask.Starter starter = new IssuesInProgressExtractTask.Starter(
        (world, u) -> authors.get(u.id()), "assignee at the first commit",
        "no assignee: the first commit's author", "none");
    final IssuesInProgressExtractTask.Report extraction = IssuesInProgressExtractTask.columns(
        source, progress, b.corpus(), b.universe(), units, studied, starter,
        Population.FIRST_COMMIT, drawn.parts(),
        IssuesInProgressExtractTask.summary(units.size() + zero, projects, drawn.parts()),
        target, TARGET_COLUMNS, 6, PHASES);
    return new Report(extraction, b.units().size(),
        IssuesFirstCommitAuditTask.funnel(b.units().size(), drawn.verdicts()), drawn.threshold(),
        FirstCommit.summary(training));
  }
}
