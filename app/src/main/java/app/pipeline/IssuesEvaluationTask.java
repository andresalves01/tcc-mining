package app.pipeline;

import app.paper5.model.Bootstrap;
import app.paper5.model.Breakdown;
import app.paper5.model.Score;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
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
import java.util.List;
import java.util.Optional;

/**
 * Fits the chosen specifications of one population and reads them every way the plan names.
 * Searches for nothing: every alpha and lambda arrives from the grid.
 *
 * <p>Only {@link Fitting.Mode#INNER} is reachable from a command until the registration exists: it
 * fits on the inner training half and scores the inner selection. <b>The parameters were chosen on
 * that same selection</b>, so these numbers are optimistic -- they compare families, scenarios and
 * blocks, and estimate nothing.
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

  public record Report(Population population, Fitting.Mode mode, int fitRows, int scoredRows,
      Score.Outcome outcome, Score project, Score constant, Score pair,
      List<Evaluated> references, List<Evaluated> evaluated, List<Bootstrap.Comparison> families,
      List<Bootstrap.Comparison> blocks, List<Sensitivity> sensitivities,
      List<Recalibrated> recalibrated) {}

  private final Progress progress;

  public IssuesEvaluationTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final List<Frozen> frozen,
      final Population population, final Fitting.Mode mode) {

    final Fitting.Sides sides = Fitting.sides(entries, mode, population);
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] projectHours = refs.projectHours(sides.scoreUnits());
    final double[] constantHours = refs.constant(observed.length);
    final double[] pairHours = refs.pairHours(sides.scoreUnits());
    final double constantMae = Fitting.mae(constantHours, observed);

    final Score project = Score.reference("referencia: mediana do projeto", projectHours,
        observed, constantMae);
    final Score constant = Score.reference(String.format(
        "referencia: constante = mediana do ajuste (%.1f h)", refs.constantHours()),
        constantHours, observed, constantMae);
    final Score pair = Score.reference(
        "referencia condicionada (so C2): mediana do par executor-projeto", pairHours, observed,
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
    int done = 0;
    for (final Frozen f : mine) {
      progress.item(++done, mine.size(), population + " " + f.cell(), "selecao interna");
      final Fitting.Fitted fitted = Fitting.frozen(Cell.byLabel(f.cell()), sides, f.alpha(),
          f.lambda(), project.maeHours(), constantMae);
      evaluated.add(evaluate(fitted, projects, projectHours, constantHours, pairHours, observed));
    }

    // families against each other at creation
    final List<Evaluated> atCreation = evaluated.stream()
        .filter(e -> e.fitted().cell().scenario() == Scenario.C1).toList();
    final List<Bootstrap.Comparison> families = Bootstrap.allPairs(
        atCreation.stream().map(e -> e.fitted().cell().label()).toList(),
        atCreation.stream().map(e -> Bootstrap.errors(e.fitted().predictedHours(), observed))
            .toList());
    // each C2 reading against C1 of the same family: which block carries the gain (decision 14)
    final List<Bootstrap.Comparison> blocks = new ArrayList<>();
    for (final Evaluated upper : evaluated) {
      final Cell cell = upper.fitted().cell();
      if (cell.scenario() == Scenario.C1) {
        continue;
      }
      find(evaluated, cell.family(), Scenario.C1).ifPresent(lower -> blocks.add(
          Bootstrap.compare(cell.label(), Bootstrap.errors(upper.fitted().predictedHours(),
              observed), lower.fitted().cell().label(),
              Bootstrap.errors(lower.fitted().predictedHours(), observed))));
    }

    // sensitivities, same parameters: the whole commit in A15, and without the moved issues
    final List<Sensitivity> sensitivities = new ArrayList<>();
    for (final Frozen f : mine) {
      final Cell cell = Cell.byLabel(f.cell());
      if (cell.attributes().stream().anyMatch(a -> a.name().equals("A15_change_loc"))) {
        final List<Attribute> whole = new ArrayList<>();
        for (final Attribute a : cell.attributes()) {
          whole.add(a.name().equals("A15_change_loc") ? new Attribute("A15w",
              Attributes.SENSITIVITY_A15W, a.family(), a.block(), a.kind(), a.transform(), true,
              a.inUnion()) : a);
        }
        final Cell variant = new Cell(cell.label() + " [A15 = commit inteiro]", cell.family(),
            cell.scenario(), whole);
        sensitivities.add(new Sensitivity("A15 com o commit inteiro", cell.label(),
            scoreOf(evaluated, cell), Fitting.frozen(variant, sides, f.alpha(), f.lambda(),
                project.maeHours(), constantMae).score()));
      }
    }
    final List<RowCache.Entry> unmoved =
        entries.stream().filter(e -> !e.unit().moved()).toList();
    final Fitting.Sides still = Fitting.sides(unmoved, mode, population);
    final References stillRefs = References.learnedOn(still.fitUnits());
    final double stillConstant = Fitting.mae(stillRefs.constant(still.observed().length),
        still.observed());
    final double stillProject = Fitting.mae(stillRefs.projectHours(still.scoreUnits()),
        still.observed());
    for (final Frozen f : mine) {
      final Cell cell = Cell.byLabel(f.cell());
      if (cell.scenario() != Scenario.C1) {
        continue;
      }
      sensitivities.add(new Sensitivity("sem as issues movidas entre projetos Jira", cell.label(),
          scoreOf(evaluated, cell), Fitting.frozen(cell, still, f.alpha(), f.lambda(),
              stillProject, stillConstant).score()));
    }

    final List<Recalibrated> recalibrated = new ArrayList<>();
    for (final Evaluated e : evaluated) {
      final Fitting.Fitted f = e.fitted();
      final double[] hours = f.recalibration().predictedHours();
      final Fitting.Fitted asIf = new Fitting.Fitted(f.cell(), f.alpha(), f.lambda(), f.columns(),
          f.dropped(), f.contributions(), f.selected(), hours, f.score(), f.step());
      recalibrated.add(new Recalibrated(f.cell().label(), f.recalibration().a(),
          f.recalibration().b(), f.score(), Score.of(f.cell().label() + " [recalibrada]", hours,
              observed, project.maeHours(), constantMae), e.overConstant(),
          ProjectBootstrap.gain(projects, hours, constantHours, observed), e.bands(),
          Breakdown.byTruth(hours, observed), e.body(),
          Breakdown.body(hours, observed, 0.25, 0.90), f.spread(), asIf.spread()));
    }

    return new Report(population, mode, sides.fitUnits().size(), sides.scoreUnits().size(),
        Score.Outcome.of(observed), project, constant, pair, references, List.copyOf(evaluated),
        families, List.copyOf(blocks), List.copyOf(sensitivities), List.copyOf(recalibrated));
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
    final double[] predicted = fitted.predictedHours();
    return new Evaluated(fitted,
        ProjectBootstrap.gain(projects, predicted, projectHours, observed),
        ProjectBootstrap.gain(projects, predicted, constantHours, observed),
        ProjectBootstrap.gain(projects, predicted, pairHours, observed),
        Breakdown.errors(predicted, observed), Breakdown.byTruth(predicted, observed),
        Breakdown.body(predicted, observed, 0.25, 0.90),
        Window.of(predicted, observed, Window.ONE_HOUR, Window.ONE_YEAR));
  }
}
