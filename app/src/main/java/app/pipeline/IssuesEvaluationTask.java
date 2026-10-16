package app.pipeline;

import app.study.model.Bootstrap;
import app.study.model.Breakdown;
import app.study.model.Score;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.Scenario;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.issues.model.Window;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fits the chosen specifications of one population and reads them every way the plan names.
 * Searches for nothing: every alpha and lambda arrives from the grid.
 *
 * <p>{@link Fitting.Mode#INNER} fits on the inner training half and scores the inner selection.
 * <b>The parameters were chosen on that same selection</b>, so those numbers are optimistic -- they
 * compare families, scenarios and blocks, and estimate nothing.
 *
 * <p>{@link Fitting.Mode#RESERVED} is the one run {@code REGISTRO-PREVIO.md} lays out, reached only
 * from {@code issues-reservado}: every cell is refitted on the whole outer training side with its
 * alpha and the lambda of decision 20, the references are learned on that same side, and the
 * outer test is scored once. No recalibration is computed (decision 22).
 */
public final class IssuesEvaluationTask {

  /** A chosen specification: a cell of the grid and its two parameters, for one population. */
  public record Frozen(Population population, String cell, double alpha, double lambda) {}

  /**
   * Everything reported about one specification.
   *
   * @param overPair the gain over the executor–project median: meaningful only in C2 cells
   */
  public record Evaluated(Fitting.Fitted fitted, ProjectBootstrap.Interval overProject,
      ProjectBootstrap.Interval overConstant, ProjectBootstrap.Interval overPair,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Breakdown.Body body, Window window) {}

  /** A sensitivity: the same cell and parameters under a changed reading. */
  public record Sensitivity(String what, String cell, Score base, Score changed) {}

  /**
   * The log recalibration beside the model it recalibrates (see {@link Fitting.Recalibration}):
   * measured to be judged after seeing, never used in place of the model.
   */
  public record Recalibrated(String cell, double a, double b, Score base, Score recalibrated,
      ProjectBootstrap.Interval baseOverConstant, ProjectBootstrap.Interval overConstant,
      List<Breakdown.Band> baseBands, List<Breakdown.Band> bands, Breakdown.Body baseBody,
      Breakdown.Body body, double[] baseSpread, double[] spread) {}

  /**
   * Decision 17: of the two references, the main one is the one that errs less on the inner
   * selection of the population -- a rule, so another corpus chooses by itself. A tie, which these
   * numbers never reach, goes to the constant, the simpler of the two.
   */
  public record MainReference(double constantMae, double projectMae,
      double pairMae) {

    /** The main study's rule: two candidates. */
    public MainReference(final double constantMae, final double projectMae) {
      this(constantMae, projectMae, Double.NaN);
    }

    public boolean constant() {
      return constantMae <= projectMae
          && (Double.isNaN(pairMae) || constantMae <= pairMae);
    }

    /**
     * The one that errs least; ties go to the simpler one. In the secondary study the pair median
     * is a third candidate (P12), because the executor is known at t0 there.
     */
    public String name() {
      if (constant()) {
        return "constante";
      }
      return Double.isNaN(pairMae) || projectMae <= pairMae ? "project median"
          : "executor-project pair median";
    }
  }

  /** P16: the secondary study's rows that went back from In Progress to Open. */
  static boolean paused(final RowCache.Entry e) {
    final Double v = e.row().values().get(IssuesInProgressExtractTask.PAUSED);
    return v != null && v == 1.0;
  }

  /**
   * @param innerFitRows the rows the chosen lambdas were fitted on, which decision 20 scales by
   * @param familiesByProjects the tie criterion; {@code families}, by rows, is the narrower reading
   * @param unseenProjects projects of the scored side with no fitting row: there the project median
   *     is the constant
   */
  public record Report(Population population, Fitting.Mode mode, int fitRows, int scoredRows,
      int innerFitRows, Score.Outcome outcome, Score project, Score constant, Score pair,
      MainReference main, List<Evaluated> references, List<Evaluated> evaluated,
      List<Bootstrap.Comparison> families, List<ProjectBootstrap.Gap> familiesByProjects,
      List<Bootstrap.Comparison> blocks, List<ProjectBootstrap.Gap> blocksByProjects,
      List<Sensitivity> sensitivities, List<Recalibrated> recalibrated,
      Set<String> unseenProjects, int unseenRows, List<IssueUnit> scoredUnits,
      double[] observed) {}

  private final Progress progress;

  public IssuesEvaluationTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final List<Frozen> frozen,
      final Population population, final Fitting.Mode mode) {

    final Fitting.Sides sides = Fitting.sides(entries, mode, population);
    // the inner cut decides the main reference and is what the chosen lambdas were fitted on;
    // under RESERVED it is read again from the training side, which holds all of it
    final Fitting.Sides inner = mode == Fitting.Mode.INNER ? sides
        : Fitting.sides(entries, Fitting.Mode.INNER, population);
    final References innerRefs = References.learnedOn(inner.fitUnits());
    final boolean started = population == Population.IN_PROGRESS;
    final MainReference main = new MainReference(
        Fitting.mae(innerRefs.constant(inner.observed().length), inner.observed()),
        Fitting.mae(innerRefs.projectMedian(inner.scoreUnits()), inner.observed()),
        started ? Fitting.mae(innerRefs.pairMedian(inner.scoreUnits()), inner.observed())
            : Double.NaN);
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] projectHours = refs.projectMedian(sides.scoreUnits());
    final double[] constantHours = refs.constant(observed.length);
    final double[] pairHours = refs.pairMedian(sides.scoreUnits());
    final double constantMae = Fitting.mae(constantHours, observed);

    final Score project = Score.reference("reference: project median", projectHours,
        observed, constantMae);
    final Score constant = Score.reference(String.format(
        "reference: constant = fit median (%.1f h)", refs.constantMedian()),
        constantHours, observed, constantMae);
    final Score pair = Score.reference(started
        ? "reference: executor-project pair median (executor at t0)"
        : "conditioned reference (C2 only): executor-project pair median", pairHours, observed,
        constantMae);
    final List<Evaluated> references = List.of(
        evaluate(asFitted(project, projectHours), projects, projectHours, constantHours,
            pairHours, observed),
        evaluate(asFitted(constant, constantHours), projects, projectHours, constantHours,
            pairHours, observed),
        evaluate(asFitted(pair, pairHours), projects, projectHours, constantHours, pairHours,
            observed));

    final List<Evaluated> evaluated = new ArrayList<>();
    final List<Frozen> mine = frozen.stream().filter(f -> f.population() == population).toList();
    // decision 20: under RESERVED the penalty is the chosen one times the ratio of rows
    final Map<String, Double> lambdas = new HashMap<>();
    for (final Frozen f : mine) {
      lambdas.put(f.cell(), mode == Fitting.Mode.INNER ? f.lambda()
          : Fitting.finalLambda(f.lambda(), sides.fitUnits().size(), inner.fitUnits().size()));
    }
    int done = 0;
    for (final Frozen f : mine) {
      progress.item(++done, mine.size(), population + " " + f.cell(),
          mode == Fitting.Mode.INNER ? "inner selection" : "refit on the outer training side");
      final Fitting.Fitted fitted = Fitting.frozen(Cell.byLabel(f.cell(), population), sides, f.alpha(),
          lambdas.get(f.cell()), project.mae(), constantMae);
      evaluated.add(evaluate(fitted, projects, projectHours, constantHours, pairHours, observed));
    }

    // families against each other at creation
    final List<Evaluated> atCreation = evaluated.stream()
        .filter(e -> e.fitted().cell().scenario() == Scenario.C1).toList();
    final List<Bootstrap.Comparison> families = Bootstrap.allPairs(
        atCreation.stream().map(e -> e.fitted().cell().label()).toList(),
        atCreation.stream().map(e -> Bootstrap.errors(e.fitted().predicted(), observed))
            .toList());
    final List<ProjectBootstrap.Gap> familiesByProjects = new ArrayList<>();
    for (int i = 0; i < atCreation.size(); i++) {
      for (int j = i + 1; j < atCreation.size(); j++) {
        familiesByProjects.add(gap(projects, atCreation.get(i), atCreation.get(j), observed));
      }
    }
    // each C2 reading against C1 of the same family: which block carries the gain (decision 14)
    final List<Bootstrap.Comparison> blocks = new ArrayList<>();
    final List<ProjectBootstrap.Gap> blocksByProjects = new ArrayList<>();
    for (final Evaluated upper : evaluated) {
      final Cell cell = upper.fitted().cell();
      if (cell.scenario() == Scenario.C1) {
        continue;
      }
      find(evaluated, cell.family(), Scenario.C1).ifPresent(lower -> {
        blocks.add(Bootstrap.compare(cell.label(), Bootstrap.errors(
            upper.fitted().predicted(), observed), lower.fitted().cell().label(),
            Bootstrap.errors(lower.fitted().predicted(), observed)));
        blocksByProjects.add(gap(projects, upper, lower, observed));
      });
    }

    // sensitivities, same parameters: the whole commit in A15, and without the moved issues
    final List<Sensitivity> sensitivities = new ArrayList<>();
    for (final Frozen f : mine) {
      final Cell cell = Cell.byLabel(f.cell(), population);
      if (cell.attributes().stream().anyMatch(a -> a.name().equals("A15_change_loc"))) {
        final List<Attribute> whole = new ArrayList<>();
        for (final Attribute a : cell.attributes()) {
          whole.add(a.name().equals("A15_change_loc") ? new Attribute("A15w",
              Attributes.SENSITIVITY_A15W, a.family(), a.block(), a.kind(), a.transform(), true,
              a.inUnion()) : a);
        }
        final Cell variant = new Cell(cell.label() + " [A15 = whole commit]", cell.family(),
            cell.scenario(), whole);
        sensitivities.add(new Sensitivity("A15 with the whole commit", cell.label(),
            scoreOf(evaluated, cell), Fitting.frozen(variant, sides, f.alpha(),
                lambdas.get(f.cell()), project.mae(), constantMae).score()));
      }
    }
    // the main study reads its C1 cells without the moved issues; the secondary study reads every
    // cell without the paused ones (P16)
    final List<RowCache.Entry> unmoved = entries.stream()
        .filter(e -> started ? !paused(e) : !e.unit().moved()).toList();
    final Fitting.Sides still = Fitting.sides(unmoved, mode, population);
    final References stillRefs = References.learnedOn(still.fitUnits());
    final double stillConstant = Fitting.mae(stillRefs.constant(still.observed().length),
        still.observed());
    final double stillProject = Fitting.mae(stillRefs.projectMedian(still.scoreUnits()),
        still.observed());
    for (final Frozen f : mine) {
      final Cell cell = Cell.byLabel(f.cell(), population);
      if (!started && cell.scenario() != Scenario.C1) {
        continue;
      }
      sensitivities.add(new Sensitivity(started
          ? "without the issues that go back from In Progress to Open"
          : "without the issues moved between Jira projects", cell.label(),
          scoreOf(evaluated, cell), Fitting.frozen(cell, still, f.alpha(),
              lambdas.get(f.cell()), stillProject, stillConstant).score()));
    }

    final List<Recalibrated> recalibrated = new ArrayList<>();
    // decision 22: the reserved predictions are the model's own, and nothing recalibrates them
    // it was asked for the main study's inner selection only
    for (final Evaluated e : mode == Fitting.Mode.INNER && !started ? evaluated
        : List.<Evaluated>of()) {
      final Fitting.Fitted f = e.fitted();
      final double[] hours = f.recalibration().predicted();
      final Fitting.Fitted asIf = new Fitting.Fitted(f.cell(), f.alpha(), f.lambda(), f.columns(),
          f.dropped(), f.contributions(), f.selected(), hours, f.score(), f.step());
      recalibrated.add(new Recalibrated(f.cell().label(), f.recalibration().a(),
          f.recalibration().b(), f.score(), Score.of(f.cell().label() + " [recalibrada]", hours,
              observed, project.mae(), constantMae), e.overConstant(),
          ProjectBootstrap.gain(projects, hours, constantHours, observed), e.bands(),
          Breakdown.byTruth(hours, observed), e.body(),
          Breakdown.body(hours, observed, 0.25, 0.90), f.spread(), asIf.spread()));
    }

    final Set<String> seen = new HashSet<>();
    sides.fitUnits().forEach(u -> seen.add(u.project()));
    final Set<String> unseen = new TreeSet<>();
    int unseenRows = 0;
    for (final IssueUnit u : sides.scoreUnits()) {
      if (!seen.contains(u.project())) {
        unseen.add(u.project());
        unseenRows++;
      }
    }

    return new Report(population, mode, sides.fitUnits().size(), sides.scoreUnits().size(),
        inner.fitUnits().size(), Score.Outcome.of(observed), project, constant, pair, main,
        references, List.copyOf(evaluated), families, List.copyOf(familiesByProjects),
        List.copyOf(blocks), List.copyOf(blocksByProjects), List.copyOf(sensitivities),
        List.copyOf(recalibrated), unseen, unseenRows, sides.scoreUnits(), observed);
  }

  private static ProjectBootstrap.Gap gap(final List<String> projects, final Evaluated a,
      final Evaluated b, final double[] observed) {
    return ProjectBootstrap.compare(projects, a.fitted().cell().label(),
        Bootstrap.errors(a.fitted().predicted(), observed), b.fitted().cell().label(),
        Bootstrap.errors(b.fitted().predicted(), observed));
  }

  private static double[] pick(final double[] values, final List<Integer> rows) {
    final double[] out = new double[rows.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = values[rows.get(k)];
    }
    return out;
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static Optional<Evaluated> find(final List<Evaluated> evaluated, final Family family,
      final Scenario scenario) {
    return evaluated.stream().filter(e -> e.fitted().cell().family() == family
        && e.fitted().cell().scenario() == scenario).findFirst();
  }

  private static Score scoreOf(final List<Evaluated> evaluated, final Cell cell) {
    return evaluated.stream().filter(e -> e.fitted().cell().label().equals(cell.label()))
        .findFirst().orElseThrow().fitted().score();
  }

  private static Fitting.Fitted asFitted(final Score score, final double[] hours) {
    return new Fitting.Fitted(new Cell(score.label(), null, null, List.of()), Double.NaN,
        Double.NaN, List.of(), List.of(), List.of(), 0, hours, score, -1);
  }

  private static Evaluated evaluate(final Fitting.Fitted fitted, final List<String> projects,
      final double[] projectHours, final double[] constantHours, final double[] pairHours,
      final double[] observed) {
    final double[] predicted = fitted.predicted();
    return new Evaluated(fitted,
        ProjectBootstrap.gain(projects, predicted, projectHours, observed),
        ProjectBootstrap.gain(projects, predicted, constantHours, observed),
        ProjectBootstrap.gain(projects, predicted, pairHours, observed),
        Breakdown.errors(predicted, observed), Breakdown.byTruth(predicted, observed),
        Breakdown.body(predicted, observed, 0.25, 0.90),
        Window.of(predicted, observed, Window.ONE_HOUR, Window.ONE_YEAR));
  }
}
