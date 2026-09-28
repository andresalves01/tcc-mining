package app.pipeline;

import app.model.ElasticNetModel;
import app.paper5.Artigo4Features;
import app.paper5.Row;
import app.paper5.RowCache;
import app.paper5.Unit;
import app.paper5.model.Baseline;
import app.paper5.model.Bootstrap;
import app.paper5.model.Cell;
import app.paper5.model.DesignMatrix;
import app.paper5.model.History;
import app.paper5.model.Score;
import java.util.ArrayList;
import java.util.List;

/**
 * The five frozen specifications, measured on the inner selection, without searching for anything.
 *
 * <p>Exists because the full grid spends fifty minutes sweeping seventy-two parameter pairs per cell
 * to rediscover values that are already written down. Once the registration fixed alpha and lambda,
 * answering a new question about those five specifications costs <b>five fits</b>, not one thousand
 * six hundred and fifty-six.
 *
 * <p>It is the development side, not the reserved one: it fits on the inner training half and scores
 * the inner selection half, exactly as {@code paper5-fit-inner} does. The reserved partition is
 * refused here by the same rule that refuses it everywhere except one command.
 *
 * <p>Its purpose is to answer the two questions added on 27/09/2026 -- how often the ordering is
 * right pair by pair, and whether the table's order survives resampling -- before the reserved rows
 * are opened, so the answers cannot be accused of having been chosen after seeing them.
 */
public final class Paper5CheckTask {

  public record Measured(Paper5ReservedTask.Frozen frozen, Score score, int columns,
      double[] predictedHours, List<Alone> alone) {}

  /**
   * What one column orders on its own, which is a different question from what it weighs.
   *
   * <p>The coefficients of the fit answer "what does the model use", and they come from minimising
   * squared error on the log scale -- not from ordering. This answers "how well does this column
   * order by itself": sort the scored rows by it and count the pairs it gets right.
   *
   * <p>For ordering, a one-variable model <b>is</b> the variable: any fit on a single column is
   * monotone in it, so it ranks the rows identically. That makes this table the answer to both
   * questions at once -- which columns carry the ordering, and what a one-variable model would reach.
   *
   * @param accuracy pairs ordered correctly when the rows are sorted by this column ascending.
   *     Below half means the column orders in reverse -- more of it goes with a faster pull request
   *     -- which is information, not a failure, so the raw figure is reported rather than folded
   */
  public record Alone(String column, double accuracy) {

    /** How far from the coin, in points, whichever direction it points. */
    public double power() {
      return Math.abs(accuracy - 0.5) * 100;
    }
  }

  public record Report(Score baseline, Score constant, Score.Outcome outcome,
      List<Measured> measured, List<Bootstrap.Comparison> comparisons,
      int fitRows, int selectionRows, double[] observedHours) {}

  /**
   * The parameters the sweep chose for the strict profile, for reading the other side of the line.
   *
   * <p><b>Not frozen and not part of the registration</b>, which fixed one profile only. They exist
   * so the same two questions can be asked of the reading that admits nothing but dated evidence --
   * the reading where the size of the change, which carries most of the ordering, is absent. That
   * comparison is the point of the whole study, and answering it on the development side costs
   * nothing and commits nothing.
   */
  private static final List<Paper5ReservedTask.Frozen> STRICT = List.of(
      new Paper5ReservedTask.Frozen(app.paper5.Family.COMBINADO_MAIS_ARTIGO4, 0.10, 2846),
      new Paper5ReservedTask.Frozen(app.paper5.Family.CONTRIBUICAO_PROPRIA, 0.50, 2846),
      new Paper5ReservedTask.Frozen(app.paper5.Family.CHOUCHEN_CORE_10, 0.50, 765.8),
      new Paper5ReservedTask.Frozen(app.paper5.Family.COMBINED_PRE, 0.95, 1706),
      new Paper5ReservedTask.Frozen(app.paper5.Family.YU_PRE_COMPAT, 0.95, 1450));

  private final app.pipeline.port.Progress progress;

  public Paper5CheckTask(final app.pipeline.port.Progress progress) {
    this.progress = progress;
  }

  /** Which reading to measure, and with which parameters. */
  public static List<Paper5ReservedTask.Frozen> parametersFor(final app.paper5.Profile profile) {
    return profile == app.paper5.Profile.STRICT_T0 ? STRICT : Paper5ReservedTask.FROZEN;
  }

  public Report run(final List<RowCache.Entry> entries, final Artigo4Features artigo4) {
    return run(entries, artigo4, Paper5ReservedTask.PROFILE);
  }

  public Report run(final List<RowCache.Entry> entries, final Artigo4Features artigo4,
      final app.paper5.Profile profile) {
    final List<Unit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<Unit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (final RowCache.Entry entry : entries) {
      if (!"TRAIN".equals(entry.outer())) {
        continue;
      }
      if ("TRAIN".equals(entry.inner())) {
        fitUnits.add(entry.unit());
        fitRows.add(entry.row());
      } else if ("TEST".equals(entry.inner())) {
        scoreUnits.add(entry.unit());
        scoreRows.add(entry.row());
      }
    }

    final double[] observed = new double[scoreUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = scoreUnits.get(i).durationHours();
    }
    final Baseline baseline = Baseline.learnedOn(fitUnits);
    final double[] baselineHours = new double[scoreUnits.size()];
    for (int i = 0; i < baselineHours.length; i++) {
      baselineHours[i] = baseline.predictHours(scoreUnits.get(i));
    }
    final Score baselineScore = Score.reference("referencia: mediana do projeto",
        baselineHours, observed, baseline.constantHours());
    final double[] constantHours = new double[scoreUnits.size()];
    java.util.Arrays.fill(constantHours, baseline.constantHours());
    final Score constantScore = Score.reference(
        String.format("calibracao: constante = mediana do treino (%.1f h)",
            baseline.constantHours()),
        constantHours, observed, baseline.constantHours());

    final History history = History.learnedOn(fitUnits);
    for (int i = 0; i < fitRows.size(); i++) {
      fitRows.set(i, history.augment(fitRows.get(i), fitUnits.get(i)));
    }
    for (int i = 0; i < scoreRows.size(); i++) {
      scoreRows.set(i, history.augment(scoreRows.get(i), scoreUnits.get(i)));
    }
    if (artigo4 != null) {
      for (int i = 0; i < fitRows.size(); i++) {
        fitRows.set(i, artigo4.augment(fitRows.get(i), fitUnits.get(i).pullRequestId()));
      }
      for (int i = 0; i < scoreRows.size(); i++) {
        scoreRows.set(i, artigo4.augment(scoreRows.get(i), scoreUnits.get(i).pullRequestId()));
      }
    }
    final double[] y = new double[fitUnits.size()];
    for (int i = 0; i < y.length; i++) {
      y[i] = DesignMatrix.target(fitUnits.get(i));
    }

    final List<Measured> measured = new ArrayList<>();
    int done = 0;
    final List<Paper5ReservedTask.Frozen> parameters = parametersFor(profile);
    for (final Paper5ReservedTask.Frozen frozen : parameters) {
      final Cell cell = Cell.of(frozen.family(), profile);
      progress.item(++done, parameters.size(), cell.label(), "um ajuste so");

      final DesignMatrix matrix = DesignMatrix.fittedOn(fitRows, cell.attributes());
      final ElasticNetModel model = ElasticNetModel.fit(matrix.applyAll(fitRows), y,
          matrix.columns(), frozen.alpha(), frozen.lambda());
      final double[][] xs = matrix.applyAll(scoreRows);
      final double[] predicted = new double[xs.length];
      for (int i = 0; i < xs.length; i++) {
        predicted[i] = DesignMatrix.hours(model.predict(xs[i]));
      }
      final List<Alone> alone = new ArrayList<>(matrix.columns().size());
      for (int c = 0; c < matrix.columns().size(); c++) {
        final double[] column = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
          column[i] = xs[i][c];
        }
        alone.add(new Alone(matrix.columns().get(c),
            Score.pairwiseAccuracy(column, observed)));
      }
      measured.add(new Measured(frozen,
          Score.of(cell.label(), predicted, observed, baselineScore.maeHours(),
              constantScore.maeHours()),
          matrix.columns().size(), predicted, List.copyOf(alone)));
    }

    final List<Measured> ordered = new ArrayList<>(measured);
    ordered.sort(java.util.Comparator.comparingDouble(m -> m.score().maeHours()));
    final List<String> labels =
        ordered.stream().map(m -> m.frozen().family().toString()).toList();
    final List<double[]> errors = ordered.stream()
        .map(m -> Bootstrap.errors(m.predictedHours(), observed)).toList();

    return new Report(baselineScore, constantScore, Score.Outcome.of(observed),
        List.copyOf(ordered), Bootstrap.allPairs(labels, errors),
        fitUnits.size(), scoreUnits.size(), observed);
  }
}
