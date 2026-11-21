package app.pipeline;

import app.issues.FirstCommit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TemporalSplit;
import app.issues.Universe;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.port.Progress;
import app.study.Row;
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
 * FirstCommit#SHARE}, its split by the run's rule ({@link SplitRule}: {@link ProjectSplit} unless
 * the temporal rule is declared) over the issues of a target of at least one business day, and
 * its cap, learned on the inner fitting's waits alone and then applied, frozen, to every drawn
 * issue. The cache holds the drawn issues only.
 *
 * <p><b>Columns.</b> Every column of the In Progress extraction, read at the first commit that
 * counts: the issue's state undone to that instant, the executor the assignee then, or else the
 * first commit's author, the participants those who commented on or changed the issue before it.
 * The time-travel gate is checked at the same instant. After those, the target and its parts
 * ({@link #TARGET_COLUMNS}), then the first commit's size and its share of the issue's lines
 * (decision 16a). The pause flag of the In Progress extraction is left out: it reads the status.
 */
public final class IssuesFirstCommitExtractTask {

  /** The target in business days: {@link FirstCommit.Count#total}. */
  public static final String TARGET = FirstCommit.TARGET_COLUMN;
  public static final String IMPLEMENTATION = "FC_implementation_days";
  public static final String TAIL = "FC_tail_days";
  public static final String CAPPED = "FC_capped_days";
  public static final String MARKS = "FC_marks";
  public static final String PATCHES = "FC_patches";
  /** The UTC hour the target's dates turn at: the first commit's author's day boundary. */
  public static final String DAY_BOUNDARY = FirstCommit.BOUNDARY_COLUMN;

  /** The target and its parts, written after the extracted columns, in this order. */
  public static final List<String> TARGET_COLUMNS =
      List.of(TARGET, IMPLEMENTATION, TAIL, CAPPED, MARKS, PATCHES, DAY_BOUNDARY);

  /**
   * Every column written after the extracted ones: the target and its parts, then the two
   * attributes of decision 16a ({@link FirstCommit#ATTRIBUTE_COLUMNS}).
   */
  public static final List<String> COLUMNS = java.util.stream.Stream.of(TARGET_COLUMNS,
      FirstCommit.ATTRIBUTE_COLUMNS).flatMap(List::stream).toList();

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
   * @param temporal what the temporal rule adds; null under the random rule
   * @param waitFenceK the k of the wait fence the cap was learned with, {@link
   *     FirstCommit#NO_WAIT_CAP} for none
   */
  public record Report(IssuesInProgressExtractTask.Report extraction, int base,
      List<IssuesFirstCommitAuditTask.Step> funnel, FirstCommit.Threshold threshold,
      FirstCommit.Summary training, Temporal temporal, double waitFenceK) {}

  /**
   * What the temporal rule adds to the extraction, as the audit counts it; nothing here reads a
   * target of the outer test.
   *
   * @param cuts the instants of the outer and the inner cut
   * @param crossing the outer training issues whose first resolution comes after the outer cut
   * @param innerCrossing the inner fitting issues whose first resolution comes after the inner cut
   * @param newProjects the projects with issues on the outer test and none that teaches at the
   *     outer level, and how many issues each has on the test; under the temporal design no issue
   *     crosses, so these are the projects with issues on the outer test alone
   * @param revised what the revised design adds; null under the temporal design
   */
  public record Temporal(TemporalSplit.Cuts cuts, int crossing, int innerCrossing,
      Map<String, Integer> newProjects, Revised revised) {}

  /**
   * What the revised design adds to the extraction: its crossing issues stay on the training side
   * and teach nothing there, so the outer training's target and the new projects are given both
   * ways.
   *
   * @param teaching the target of the outer training issues that teach at the outer level, the
   *     issues crossing the outer cut left out; the whole side's is {@link Report#training}
   * @param newProjectsTrainingSide the projects with issues on the outer test and none on the
   *     outer training side, crossing issues included, and how many issues each has on the test:
   *     the other definition of a new project, beside the one the scoring reads ({@link
   *     Temporal#newProjects})
   */
  public record Revised(FirstCommit.Summary teaching,
      Map<String, Integer> newProjectsTrainingSide) {}

  private static final int PHASES = 6;

  private final StudySource source;
  private final Progress progress;
  private final SplitRule rule;
  private final double waitK;

  /** The extraction whose sides, cap and target are drawn by {@code rule}, as the audit's. */
  public IssuesFirstCommitExtractTask(final StudySource source, final Progress progress,
      final SplitRule rule) {
    this(source, progress, rule, FirstCommit.DEFAULT_WAIT_K);
  }

  /** As above, the wait fence at Q3 + {@code waitK} IQR ({@link FirstCommit#NO_WAIT_CAP}: none). */
  public IssuesFirstCommitExtractTask(final StudySource source, final Progress progress,
      final SplitRule rule, final double waitK) {
    this.source = source;
    this.progress = progress;
    this.rule = rule;
    this.waitK = waitK;
  }

  public Report run() {
    final IssuesFirstCommitAuditTask.Base b =
        new IssuesFirstCommitAuditTask(source, progress, rule, waitK).base(PHASES);

    progress.phase(4, PHASES, "selection, split and cap");
    final IssuesFirstCommitAuditTask.Drawn drawn =
        IssuesFirstCommitAuditTask.draw(b.issues(), FirstCommit.SHARE, b.boundary(), rule, waitK);
    final int cap = drawn.threshold().cap();
    final List<Universe.Unit> units = new ArrayList<>(drawn.parts().outer().size());
    final Map<String, String> projects = new HashMap<>(drawn.parts().outer().size() * 2);
    final Map<String, String> authors = new HashMap<>(drawn.parts().outer().size() * 2);
    final Map<String, Map<String, Double>> target = new HashMap<>(drawn.parts().outer().size() * 2);
    final List<Integer> training = new ArrayList<>();
    final List<Integer> teaching = new ArrayList<>();
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
        if (!drawn.parts().outerCrossing().contains(u.id())) {
          teaching.add(c.total());
        }
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
      row.put(FirstCommit.FIRST_LINES, (double) first.lines());
      row.put(FirstCommit.FIRST_SHARE, v.firstShare());
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
        target, COLUMNS, 6, PHASES);
    return new Report(extraction, b.units().size(),
        IssuesFirstCommitAuditTask.funnel(b.units().size(), drawn.verdicts()), drawn.threshold(),
        FirstCommit.summary(training),
        temporal(drawn, projects, teaching), waitK);
  }

  /**
   * The cuts, the issues crossing them, the new projects, the target of the outer training issues
   * that teach, {@code teaching}, and the new projects by the other definition. A new project is
   * one with no issue that teaches at the outer level, as the scoring reads it: an issue crossing
   * the outer cut is left out of the training side's projects.
   */
  private static Temporal temporal(final IssuesFirstCommitAuditTask.Drawn drawn,
      final Map<String, String> projects, final List<Integer> teaching) {
    final TemporalSplit.Cuts cuts = TemporalSplit.cuts(drawn.candidates());
    final ProjectSplit.Parts parts = drawn.parts();
    final IssuesFirstCommitAuditTask.Crossing crossing = drawn.crossing();
    final Map<String, Part> outerTeaching = new HashMap<>(parts.outer());
    outerTeaching.keySet().removeAll(parts.outerCrossing());
    final Map<String, Integer> newByTeaching =
        IssuesFirstCommitAuditTask.newProjects(projects, outerTeaching);
    final Revised revised = new Revised(FirstCommit.summary(teaching),
        IssuesFirstCommitAuditTask.newProjects(projects, parts.outer()));
    return new Temporal(cuts, crossing.outer(), crossing.inner(), newByTeaching, revised);
  }

}
