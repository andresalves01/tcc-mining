package app.pipeline;

import app.paper5.model.Bootstrap;
import app.paper5.model.Breakdown;
import app.paper5.model.Score;
import app.paper6.Profile;
import app.paper6.RowCache;
import app.paper6.model.Cell;
import app.paper6.model.Fitting;
import app.paper6.model.ProjectBootstrap;
import app.paper6.model.References;
import app.paper6.model.Window;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.List;

/**
 * Fits the frozen specifications and reads them every way the registration names. Searches for
 * nothing: every alpha and lambda arrives as a literal.
 *
 * <p>One class for the inner check and the reserved run, so the two cannot differ in how they
 * measure. The only difference is {@link Fitting.Mode}: under {@code INNER} it fits on the inner
 * training half and scores the inner selection; under {@code RESERVED} it refits on the whole
 * training side and scores the reserved rows. Only {@code paper6-reservado} passes
 * {@code RESERVED}.
 */
public final class Paper6EvaluationTask {

  /** A specification the registration froze: a cell of the grid and its two parameters. */
  public record Frozen(String cell, double alpha, double lambda) {}

  /**
   * Everything reported about one specification.
   *
   * @param overProject the gain over the project median, with an interval that resamples projects
   * @param overConstant the same, over the constant
   * @param overPair the same, over the pair median -- meaningful only where the person is given
   */
  public record Evaluated(Fitting.Fitted fitted, ProjectBootstrap.Interval overProject,
      ProjectBootstrap.Interval overConstant, ProjectBootstrap.Interval overPair,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Breakdown.Body body, Window window) {}

  /** A column read alone: how well it orders the scored rows by itself. */
  public record Alone(String cell, String column, double pairwise) {}

  public record Report(Fitting.Mode mode, int fitRows, int scoredRows, Score.Outcome outcome,
      Score project, Score constant, Score pair, List<Evaluated> references,
      List<Evaluated> evaluated, List<Bootstrap.Comparison> principal,
      List<Bootstrap.Comparison> rungs, List<Alone> alone) {}

  private final Progress progress;

  public Paper6EvaluationTask(final Progress progress) {
    this.progress = progress;
  }

  public Report run(final List<RowCache.Entry> entries, final List<Frozen> frozen,
      final Fitting.Mode mode) {

    final Fitting.Sides sides = Fitting.sides(entries, mode);
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] projectHours = refs.projectHours(sides.scoreUnits());
    final double[] constantHours = refs.constant(observed.length);
    final double[] pairHours = refs.pairHours(sides.scoreUnits());
    final double constantMae = Fitting.mae(constantHours, observed);

    final Score project = Score.reference("referencia obrigatoria: mediana do projeto",
        projectHours, observed, constantMae);
    final Score constant = Score.reference(String.format(
        "referencia: constante = mediana do ajuste (%.1f h)", refs.constantHours()),
        constantHours, observed, constantMae);
    final Score pair = Score.reference(
        "referencia condicionada: mediana do par autor-projeto", pairHours, observed, constantMae);

    final List<Evaluated> references = List.of(
        evaluate(asFitted(project, projectHours), projects, projectHours, constantHours,
            pairHours, observed),
        evaluate(asFitted(constant, constantHours), projects, projectHours, constantHours,
            pairHours, observed),
        evaluate(asFitted(pair, pairHours), projects, projectHours, constantHours, pairHours,
            observed));

    final List<Evaluated> evaluated = new ArrayList<>();
    int done = 0;
    for (final Frozen f : frozen) {
      progress.item(++done, frozen.size(), f.cell(), mode == Fitting.Mode.RESERVED
          ? "reajustando no treino inteiro e pontuando o reservado" : "selecao interna");
      final Fitting.Fitted fitted = Fitting.frozen(Cell.byLabel(f.cell()), sides, f.alpha(),
          f.lambda(), project.maeHours(), constantMae);
      evaluated.add(evaluate(fitted, projects, projectHours, constantHours, pairHours, observed));
    }

    // Model against model on the same rows: the six families under the principal profile, and
    // each rung against the one below it for the two families that climb all four.
    final List<Evaluated> principal = evaluated.stream()
        .filter(e -> e.fitted().cell().profile() == Profile.T0_RECONSTRUIDO)
        .sorted((a, b) -> Double.compare(a.fitted().score().maeHours(),
            b.fitted().score().maeHours()))
        .toList();
    final List<Bootstrap.Comparison> principalPairs = Bootstrap.allPairs(
        principal.stream().map(e -> e.fitted().cell().label()).toList(),
        principal.stream().map(e -> Bootstrap.errors(e.fitted().predictedHours(), observed))
            .toList());
    final List<Bootstrap.Comparison> rungs = new ArrayList<>();
    for (final Evaluated upper : evaluated) {
      if (upper.fitted().cell().family() == null) {
        continue;
      }
      Evaluated lower = null;
      for (final Evaluated candidate : evaluated) {
        final Cell c = candidate.fitted().cell();
        if (c.family() == upper.fitted().cell().family() && c.profile() != null
            && c.profile().ordinal() < upper.fitted().cell().profile().ordinal()
            && (lower == null || c.profile().ordinal() > lower.fitted().cell().profile().ordinal())) {
          lower = candidate;
        }
      }
      if (lower != null) {
        rungs.add(Bootstrap.compare(upper.fitted().cell().label(),
            Bootstrap.errors(upper.fitted().predictedHours(), observed),
            lower.fitted().cell().label(),
            Bootstrap.errors(lower.fitted().predictedHours(), observed)));
      }
    }

    // Each column of the widest ex ante union, alone: sort the rows by it and count pairs.
    final List<Alone> alone = new ArrayList<>();
    for (final Evaluated e : evaluated) {
      final Cell cell = e.fitted().cell();
      if (cell.profile() != Profile.T0_RECONSTRUIDO
          || cell.family() != app.paper6.Family.COMBINADO_TOTAL) {
        continue;
      }
      for (final app.paper6.Attribute attribute : cell.attributes()) {
        final double[] values = new double[sides.scoreRows().size()];
        for (int i = 0; i < values.length; i++) {
          values[i] = sides.scoreRows().get(i).values()
              .getOrDefault(attribute.name(), Double.NaN);
        }
        alone.add(new Alone(cell.label(), attribute.name(),
            Score.pairwiseAccuracy(fillNaN(values), observed)));
      }
    }

    return new Report(mode, sides.fitUnits().size(), sides.scoreUnits().size(),
        Score.Outcome.of(observed), project, constant, pair, references, List.copyOf(evaluated),
        principalPairs, List.copyOf(rungs), List.copyOf(alone));
  }

  private static Fitting.Fitted asFitted(final Score score, final double[] hours) {
    return new Fitting.Fitted(new Cell(score.label(), null, null, List.of()), Double.NaN,
        Double.NaN, List.of(), List.of(), List.of(), 0, hours, score);
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

  /** An absent value sorts as the lowest, which is how a reader would rank "nothing recorded". */
  private static double[] fillNaN(final double[] values) {
    final double[] out = values.clone();
    for (int i = 0; i < out.length; i++) {
      if (Double.isNaN(out[i])) {
        out[i] = -1;
      }
    }
    return out;
  }
}
