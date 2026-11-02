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
   */
  public record Report(IssuesInProgressExtractTask.Report extraction, int base,
      List<IssuesFirstCommitAuditTask.Step> funnel, FirstCommit.Threshold threshold,
      FirstCommit.Summary training, Temporal temporal) {}

  /**
   * What the temporal rule adds to the extraction, as the audit counts it; nothing here reads a
   * target of the outer test.
   *
   * @param cuts the instants of the outer and the inner cut
   * @param crossing the outer training issues whose first resolution comes after the outer cut
   * @param innerCrossing the inner fitting issues whose first resolution comes after the inner cut
   * @param newProjects the projects with issues on the outer test alone, and how many there
   */
  public record Temporal(TemporalSplit.Cuts cuts, int crossing, int innerCrossing,
      Map<String, Integer> newProjects) {}

  private static final int PHASES = 6;

  private final StudySource source;
  private final Progress progress;
  private final SplitRule rule;

  /** The extraction under the random rule. */
  public IssuesFirstCommitExtractTask(final StudySource source, final Progress progress) {
    this(source, progress, SplitRule.RANDOM);
  }

  /** The extraction whose sides, cap and target are drawn by {@code rule}, as the audit's. */
  public IssuesFirstCommitExtractTask(final StudySource source, final Progress progress,
      final SplitRule rule) {
    this.source = source;
    this.progress = progress;
    this.rule = rule;
  }

  public Report run() {
    final IssuesFirstCommitAuditTask.Base b =
        new IssuesFirstCommitAuditTask(source, progress).base(PHASES);

    progress.phase(4, PHASES, "selection, split and cap");
    final IssuesFirstCommitAuditTask.Drawn drawn =
        IssuesFirstCommitAuditTask.draw(b.issues(), FirstCommit.SHARE, b.boundary(), rule);
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
    return new Report(withoutPause(extraction), b.units().size(),
        IssuesFirstCommitAuditTask.funnel(b.units().size(), drawn.verdicts()), drawn.threshold(),
        FirstCommit.summary(training),
        rule == SplitRule.TEMPORAL ? temporal(b.issues(), drawn, projects) : null);
  }

  /** The cuts, the issues crossing them and the new projects of a temporal draw. */
  private static Temporal temporal(final Map<String, FirstCommit.Issue> issues,
      final IssuesFirstCommitAuditTask.Drawn drawn, final Map<String, String> projects) {
    final TemporalSplit.Cuts cuts = TemporalSplit.cuts(drawn.candidates());
    final ProjectSplit.Parts parts = drawn.parts();
    return new Temporal(cuts,
        IssuesFirstCommitAuditTask.crossing(issues, parts.outer(), cuts.outer()),
        IssuesFirstCommitAuditTask.crossing(issues, parts.inner(), cuts.inner()),
        IssuesFirstCommitAuditTask.newProjects(projects, parts.outer()));
  }

  /**
   * The extraction without the pause flag ({@link IssuesInProgressExtractTask#PAUSED}): it reads
   * the In Progress workflow, which this population does not start at, and the status is not read
   * (spec decision 14; André, 25/10/2026). No column of the cache carries it.
   */
  static IssuesInProgressExtractTask.Report withoutPause(final IssuesInProgressExtractTask.Report r) {
    final String paused = IssuesInProgressExtractTask.PAUSED;
    final List<String> columns = r.columns().stream().filter(c -> !c.equals(paused)).toList();
    final List<RowCache.Entry> entries = new ArrayList<>(r.entries().size());
    for (final RowCache.Entry e : r.entries()) {
      final Map<String, Double> values = new LinkedHashMap<>(e.row().values());
      values.remove(paused);
      final Set<String> absent = new java.util.HashSet<>(e.row().absent());
      absent.remove(paused);
      entries.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent),
          e.parts()));
    }
    return new IssuesInProgressExtractTask.Report(r.split(), r.travel(), r.executors(),
        List.copyOf(entries), columns, r.team());
  }
}
