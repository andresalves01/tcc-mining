package app.pipeline;

import app.model.ElasticNetModel;
import app.paper5.Artigo4Features;
import app.paper5.Attribute;
import app.paper5.Family;
import app.paper5.Profile;
import app.paper5.Row;
import app.paper5.RowCache;
import app.paper5.Unit;
import app.paper5.model.Baseline;
import app.paper5.model.Cell;
import app.paper5.model.DesignMatrix;
import app.paper5.model.History;
import app.paper5.model.Score;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the reserved partition once, against the specification frozen before it was opened.
 *
 * <p><b>This class searches for nothing.</b> There is no grid here, no sweep and no comparison of
 * variants: every alpha and every lambda is a literal below, copied from {@code REGISTRO-PREVIO.md},
 * which was committed before this file existed. A loop that tried two values would turn the reserved
 * partition into a second development set, which is the one thing separating it was for.
 *
 * <p>The order of operations is itself part of the registration and is enforced by the order of the
 * statements: each specification is <b>refitted on the whole training side</b> with its frozen
 * parameters, its coefficients are recorded, and only then are the reserved rows scored. The
 * coefficients are a result in their own right and do not depend on anything the reserved rows say.
 *
 * <p>Refitting rather than reusing the development model is deliberate and was registered: the
 * parameters were chosen without seeing the reserved side, so fitting on 6.212 rows instead of 4.252
 * adds data without adding knowledge of the outcome.
 */
public final class Paper5ReservedTask {

  /** Everything that was frozen, as literals. Changing one of these invalidates the result. */
  public record Frozen(Family family, double alpha, double lambda) {}

  /**
   * The five specifications and their parameters, from section 5 of the registration.
   *
   * <p>All five are read at the one profile the registration fixed: everything admitted as known or
   * approximable at opening, which is every class except the commit count.
   */
  public static final Profile PROFILE = Profile.T0_TEXT_PROXY;

  public static final List<Frozen> FROZEN = List.of(
      new Frozen(Family.COMBINADO_MAIS_ARTIGO4, 0.10, 77.45),
      new Frozen(Family.CONTRIBUICAO_PROPRIA, 0.50, 15.61),
      new Frozen(Family.CHOUCHEN_CORE_10, 0.95, 0.3800),
      new Frozen(Family.COMBINED_PRE, 0.95, 173.5),
      new Frozen(Family.YU_PRE_COMPAT, 0.95, 576.8));

  /** One coefficient of one refitted model, in the standardised scale, with its sign. */
  public record Weight(String column, double coefficient) {}

  /**
   * One specification: what it weighed on the training side, and how it did on the reserved one.
   *
   * @param predictedHours one prediction per reserved row, kept so two specifications can be
   *     compared on the same rows. Without it the table's order is just an order, and a gap of one
   *     hour in two hundred would be read as a ranking it may not support
   */
  public record Result(Family family, double alpha, double lambda, List<String> columns,
      List<Weight> weights, Score score, double[] predictedHours) {}

  public record Report(
      Score baseline,
      Score constant,
      Score.Outcome outcome,
      List<Result> results,
      List<app.paper5.model.Bootstrap.Comparison> comparisons,
      int trainingRows,
      int reservedRows,
      int baselineFellBack,
      List<String> dropped) {}

  private final app.pipeline.port.Progress progress;

  public Paper5ReservedTask(final app.pipeline.port.Progress progress) {
    this.progress = progress;
  }

  /**
   * @param entries every row of the population, both sides of the outer cut. This is the only task
   *     in the study that is allowed to read the far side, and it is allowed once
   */
  public Report run(final List<RowCache.Entry> entries, final Artigo4Features artigo4) {
    final List<Unit> trainUnits = new ArrayList<>();
    final List<Row> trainRows = new ArrayList<>();
    final List<Unit> reservedUnits = new ArrayList<>();
    final List<Row> reservedRows = new ArrayList<>();

    for (final RowCache.Entry entry : entries) {
      // The inner cut is ignored here on purpose: it existed to hold out a selection set while the
      // specification was being chosen, and the specification is now frozen. Both of its halves are
      // training data for this one run, which is what the registration says.
      if ("TRAIN".equals(entry.outer())) {
        trainUnits.add(entry.unit());
        trainRows.add(entry.row());
      } else if ("TEST".equals(entry.outer())) {
        reservedUnits.add(entry.unit());
        reservedRows.add(entry.row());
      }
    }
    if (trainUnits.isEmpty() || reservedUnits.isEmpty()) {
      throw new IllegalStateException(
          "esperava as duas metades e recebi " + trainUnits.size() + " e " + reservedUnits.size());
    }

    final double[] observed = new double[reservedUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = reservedUnits.get(i).durationHours();
    }

    // The primary reference: each project's own historical median, learned on the training side.
    final Baseline baseline = Baseline.learnedOn(trainUnits);
    final double[] baselineHours = new double[reservedUnits.size()];
    for (int i = 0; i < baselineHours.length; i++) {
      baselineHours[i] = baseline.predictHours(reservedUnits.get(i));
    }
    final Score baselineScore =
        Score.reference("referencia principal: mediana do projeto", baselineHours, observed,
            baseline.constantHours());

    // The calibration line: one number for everything, the median of the training side.
    final double[] constantHours = new double[reservedUnits.size()];
    java.util.Arrays.fill(constantHours, baseline.constantHours());
    final Score constantScore = Score.reference(
        String.format("calibracao: constante = mediana do treino (%.1f h)",
            baseline.constantHours()),
        constantHours, observed, baseline.constantHours());

    // The two columns computed at fit time rather than cached, because they depend on which rows
    // are the fitting side -- and that side is now the whole training partition.
    final History history = History.learnedOn(trainUnits);
    for (int i = 0; i < trainRows.size(); i++) {
      trainRows.set(i, history.augment(trainRows.get(i), trainUnits.get(i)));
    }
    for (int i = 0; i < reservedRows.size(); i++) {
      reservedRows.set(i, history.augment(reservedRows.get(i), reservedUnits.get(i)));
    }
    if (artigo4 != null) {
      for (int i = 0; i < trainRows.size(); i++) {
        trainRows.set(i, artigo4.augment(trainRows.get(i), trainUnits.get(i).pullRequestId()));
      }
      for (int i = 0; i < reservedRows.size(); i++) {
        reservedRows.set(i,
            artigo4.augment(reservedRows.get(i), reservedUnits.get(i).pullRequestId()));
      }
    }

    final double[] y = new double[trainUnits.size()];
    for (int i = 0; i < y.length; i++) {
      y[i] = DesignMatrix.target(trainUnits.get(i));
    }

    final List<Result> results = new ArrayList<>(FROZEN.size());
    final List<String> dropped = new ArrayList<>();
    int done = 0;
    for (final Frozen frozen : FROZEN) {
      final Cell cell = Cell.of(frozen.family(), PROFILE);
      progress.item(++done, FROZEN.size(), cell.label(), "reajustando no treino inteiro");

      final List<Attribute> attributes = cell.attributes();
      final DesignMatrix matrix = DesignMatrix.fittedOn(trainRows, attributes);
      matrix.dropped().forEach(name -> dropped.add(cell.label() + ": " + name));

      final ElasticNetModel model = ElasticNetModel.fit(matrix.applyAll(trainRows), y,
          matrix.columns(), frozen.alpha(), frozen.lambda());

      final double[] coefficients = model.coefficients();
      final List<Weight> weights = new ArrayList<>(coefficients.length);
      for (int i = 0; i < coefficients.length; i++) {
        weights.add(new Weight(matrix.columns().get(i), coefficients[i]));
      }

      progress.item(done, FROZEN.size(), cell.label(), "pontuando o reservado");
      final double[][] xs = matrix.applyAll(reservedRows);
      final double[] predicted = new double[xs.length];
      for (int i = 0; i < xs.length; i++) {
        predicted[i] = DesignMatrix.hours(model.predict(xs[i]));
      }
      results.add(new Result(frozen.family(), frozen.alpha(), frozen.lambda(), matrix.columns(),
          List.copyOf(weights),
          Score.of(cell.label(), predicted, observed, baselineScore.maeHours(),
              constantScore.maeHours()),
          predicted));
    }

    // The same noise test the registration promises, on the rows that will be reported. Ordered by
    // error first, so the comparison reads down the table the reader is looking at.
    final List<Result> ordered = new ArrayList<>(results);
    ordered.sort(java.util.Comparator.comparingDouble(r -> r.score().maeHours()));
    final List<app.paper5.model.Bootstrap.Comparison> comparisons =
        app.paper5.model.Bootstrap.allPairs(
            ordered.stream().map(r -> r.family().toString()).toList(),
            ordered.stream()
                .map(r -> app.paper5.model.Bootstrap.errors(r.predictedHours(), observed))
                .toList());

    return new Report(baselineScore, constantScore, Score.Outcome.of(observed),
        List.copyOf(ordered), comparisons, trainUnits.size(), reservedUnits.size(),
        baseline.fellBackOn(reservedUnits), List.copyOf(dropped));
  }

  /** The registration's own facts, printed beside the numbers so the two travel together. */
  public static Map<String, String> frozenSpecification() {
    final Map<String, String> facts = new LinkedHashMap<>();
    facts.put("registro previo", "REGISTRO-PREVIO.md, commitado antes deste arquivo existir");
    facts.put("perfil", PROFILE + " -- tudo o que se admite conhecido ou aproximavel na abertura");
    facts.put("algoritmo", "Elastic Net, sem busca: alpha e lambda sao literais do registro");
    facts.put("ajuste", "reajustado no lado de treino inteiro, com os parametros congelados");
    facts.put("referencia principal", "mediana historica de cada projeto");
    facts.put("calibracao", "um numero so: a mediana do treino");
    facts.put("reservado", "pontuado UMA vez, por esta execucao");
    return facts;
  }
}
