package app.pipeline;

import app.model.ElasticNetModel;
import app.pullrequests.Attribute;
import app.pullrequests.Family;
import app.pullrequests.Profile;
import app.pullrequests.Row;
import app.pullrequests.RowCache;
import app.pullrequests.Unit;
import app.pullrequests.model.Baseline;
import app.pullrequests.model.Cell;
import app.pullrequests.model.DesignMatrix;
import app.pullrequests.model.ExtraTrees;
import app.pullrequests.model.Score;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fits every cell of the grid against the inner selection, and never against the reserved side.
 *
 * <p>There is no code path from here to the outer test partition. The rows it receives are the
 * outer training side only, split again in time by the same rule, and the reserved side is not
 * loaded, not counted and not scored. That is a property of this class, not a convention its caller
 * is trusted to keep -- the same discipline {@code FitInnerCommand} documents for the previous
 * paper.
 *
 * <p>The grid is three families by three profiles, plus the literature profile read a second time
 * without {@code area_hotness_90d}. That construct is absent on 71,4% of the reserved side because
 * the corpus stopped mining commits around mid-2018, so the reading without it is the one that can
 * be interpreted and the reading with it is what the difference costs.
 */
public final class PullRequestFitTask {

  /** Declared before anything was run, and small, because the inner split is not free to overfit. */
  private static final double[] ALPHAS = {0.10, 0.50, 0.95};

  /**
   * How many penalties the sweep tries, log-spaced below the largest one that matters.
   *
   * <p>The <b>shape</b> of the grid is declared here; its <b>scale</b> comes from the data, and it
   * has to. The estimator applies lambda exactly as given, without dividing by the number of rows,
   * so a fixed grid that penalises usefully on two thousand rows is indistinguishable from no
   * penalty at all on ten thousand -- {@code ElasticNetModel.fit} says so in as many words. A fixed
   * list would therefore not be a conservative choice, it would be an inert one.
   *
   * <p>This is the same derivation the previous paper's sweep uses, on purpose: two studies in one
   * repository choosing regularisation by two different rules would make any difference between
   * them partly an artefact of that.
   */
  private static final int LAMBDA_STEPS = 24;

  /** How far below the largest penalty the grid reaches. */
  private static final double LAMBDA_FLOOR_RATIO = 1e-4;

  /** One seed for the whole study, recorded beside every result. */
  public static final long SEED = 20260927L;

  /** Named so a caller can tell an empty fit side from every other kind of failure. */
  public static final class NothingToFitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NothingToFitException(final String detail) {
      super("nothing to fit: " + detail);
    }
  }

  /**
   * One fitted cell.
   *
   * @param columns the columns that actually entered the matrix, after one-hot expansion and after
   *     the constant ones were dropped. Recorded because "seventeen attributes" and "thirty-one
   *     columns" are different facts and a reader needs the second one
   * @param settings the chosen hyperparameters, in the words the estimator uses
   */
  /**
   * @param predictedHours one prediction per scored row, kept so two cells can be compared on the
   *     same rows. Without it a difference of one hour between two specifications cannot be told
   *     from noise, and the table would invite reading a ranking into it anyway
   */
  public record Fitted(Cell cell, String algorithm, Score score, List<String> columns,
      String settings, int selectedColumns, List<Contribution> contributions,
      double[] predictedHours) {}

  /**
   * What one column of the design matrix did, for the report to print beside the cell.
   *
   * <p>Two different questions, deliberately side by side. The <b>coefficient</b> is what the fitted
   * model uses: the matrix is standardised, so the magnitudes are comparable across columns and the
   * sign says the direction. A coefficient near zero under a strong penalty means the model set the
   * column aside, which is a statement about this specification and not about the column.
   *
   * <p>The <b>own rank correlation</b> is what the column carries alone, against the same target, on
   * the fit side. A column can matter alone and be dropped by the model because another column
   * already carries it, and it can have almost no association alone and still earn a coefficient in
   * combination. Reporting only one of the two invites the wrong conclusion in both directions.
   *
   * <p>Extra Trees has no coefficients, so its cells carry an empty list rather than a number that
   * would have to be invented.
   */
  public record Contribution(String column, double coefficient, double ownSpearman,
      boolean selected) {}

  /** What the report has to say about the three history columns, since they are not in the cache. */
  public record HistoryCoverage(String column, int absentOnFit, int absentOnSelection) {}

  public record Report(
      Score baseline,
      Score constant,
      Score oracleConstant,
      Score.Outcome outcome,
      List<Fitted> fits,
      int fitRows,
      int selectionRows,
      int baselineFellBack,
      List<String> dropped,
      List<String> unfitted,
      List<HistoryCoverage> historyCoverage,
      double[] observedHours) {}

  private final Progress progress;

  public PullRequestFitTask(final Progress progress) {
    this.progress = progress;
  }

  /**
   * @param entries the outer training side only, each carrying its inner partition
   */
  public Report run(final List<RowCache.Entry> entries) {
    return run(entries, null);
  }

  /**
   * @param priorStudy the previous paper's own predictors, or {@code null} when the export was not
   *     given. Null is not the same as empty: an empty loader would mark every column absent on
   *     every row, which is a claim about the data, while null means the grid runs without that
   *     family's columns at all and the report says so
   */
  public Report run(final List<RowCache.Entry> entries, final app.pullrequests.PriorStudyFeatures priorStudy) {
    final List<Unit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<Unit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();

    for (final RowCache.Entry entry : entries) {
      // The reserved side is refused here rather than filtered by the caller. A row on the far
      // side of the outer cut has no business in this stage under any inner verdict.
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
    if (fitUnits.isEmpty() || scoreUnits.isEmpty()) {
      throw new NothingToFitException(fitUnits.size() + " to fit and " + scoreUnits.size()
          + " to select on -- this stage reads the inner split of the training side");
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

    // The constant reference first, because everything else is read against it. Among constants the
    // median minimises absolute error, so this is the best a single number learned on the fit side
    // can do -- and on this data it beats the mandatory per-project baseline.
    final double[] constantHours = new double[scoreUnits.size()];
    java.util.Arrays.fill(constantHours, baseline.constantHours());
    double constantSum = 0;
    for (int i = 0; i < observed.length; i++) {
      constantSum += Math.abs(constantHours[i] - observed[i]);
    }
    final double constantMae = constantSum / observed.length;

    final Score baselineScore = Score.reference(
        "referencia obrigatoria: mediana do projeto", baselineHours, observed, constantMae);
    final Score constantScore = Score.reference(
        String.format("referencia: constante = mediana do treino (%.1f h)", baseline.constantHours()),
        constantHours, observed, constantMae);

    // The floor no constant can go below: the median of the rows being scored. Forbidden as a
    // baseline by section 9.1 -- it consults the outcomes it is predicting -- and reported anyway,
    // because without it a reader cannot tell how much of a gain is prediction and how much is the
    // mandatory reference being weak.
    final Score.Outcome outcome = Score.Outcome.of(observed);
    final double[] oracleHours = new double[observed.length];
    java.util.Arrays.fill(oracleHours, outcome.medianHours());
    final Score oracleScore = Score.reference(
        String.format("piso (oraculo, proibido como baseline): mediana do proprio alvo (%.1f h)",
            outcome.medianHours()), oracleHours, observed, constantMae);

    // The three history columns are learned from the fit side and added to both sides. They are
    // not extracted and not cached: they depend on which rows are on the fit side, so caching them
    // would freeze one split into the file.
    final app.pullrequests.model.History history = app.pullrequests.model.History.learnedOn(fitUnits);
    for (int i = 0; i < fitRows.size(); i++) {
      fitRows.set(i, history.augment(fitRows.get(i), fitUnits.get(i)));
    }
    for (int i = 0; i < scoreRows.size(); i++) {
      scoreRows.set(i, history.augment(scoreRows.get(i), scoreUnits.get(i)));
    }
    if (priorStudy != null) {
      for (int i = 0; i < fitRows.size(); i++) {
        fitRows.set(i, priorStudy.augment(fitRows.get(i), fitUnits.get(i).pullRequestId()));
      }
      for (int i = 0; i < scoreRows.size(); i++) {
        scoreRows.set(i, priorStudy.augment(scoreRows.get(i), scoreUnits.get(i).pullRequestId()));
      }
    }

    final List<Cell> cells = grid();
    final List<Fitted> fits = new ArrayList<>(cells.size() * 2);
    final List<String> dropped = new ArrayList<>();
    final List<String> unfitted = new ArrayList<>();
    int done = 0;
    for (final Cell cell : cells) {
      progress.item(++done, cells.size(), cell.label(), "ajustando");
      final List<Attribute> attributes = cell.attributes();
      final DesignMatrix matrix = DesignMatrix.fittedOn(fitRows, attributes);
      matrix.dropped().forEach(name -> dropped.add(cell.label() + ": " + name));

      final double[][] x = matrix.applyAll(fitRows);
      final double[][] xs = matrix.applyAll(scoreRows);
      final double[] y = new double[fitUnits.size()];
      for (int i = 0; i < y.length; i++) {
        y[i] = DesignMatrix.target(fitUnits.get(i));
      }

      // Each column's own association with the target, measured once per cell and on the fit side.
      // Rank correlation is invariant under log1p, so this is the same number the hours would give.
      final double[] ownSpearman = new double[matrix.columns().size()];
      for (int c = 0; c < ownSpearman.length; c++) {
        final double[] column = new double[x.length];
        for (int i = 0; i < x.length; i++) {
          column[i] = x[i][c];
        }
        ownSpearman[c] = app.model.Metrics.spearman(column, y);
      }

      fits.add(elasticNet(cell, matrix, x, y, xs, observed, baselineScore.maeHours(), constantMae,
          ownSpearman));
      final Fitted trees = extraTrees(cell, matrix, x, y, xs, observed,
          baselineScore.maeHours(), constantMae, unfitted);
      if (trees != null) {
        fits.add(trees);
      }
    }
    final List<HistoryCoverage> coverage = new ArrayList<>();
    for (final String column : app.pullrequests.model.History.COLUMNS) {
      int onFit = 0;
      int onSelection = 0;
      for (final app.pullrequests.Row row : fitRows) {
        if (row.isAbsent(column)) {
          onFit++;
        }
      }
      for (final app.pullrequests.Row row : scoreRows) {
        if (row.isAbsent(column)) {
          onSelection++;
        }
      }
      coverage.add(new HistoryCoverage(column, onFit, onSelection));
    }
    return new Report(baselineScore, constantScore, oracleScore, outcome,
        List.copyOf(fits), fitUnits.size(), scoreUnits.size(),
        baseline.fellBackOn(scoreUnits), List.copyOf(dropped), List.copyOf(unfitted),
        List.copyOf(coverage), observed);
  }

  /**
   * Five families, three profiles, and the literature profile a second time without the construct
   * whose coverage collapses after the cut -- but only where that construct is a member.
   *
   * <p>The own-contribution family yields three identical cells, one per profile, because all three
   * of its constructs are clean in {@code t0}. They are fitted and printed anyway rather than
   * special-cased: the repetition is itself the statement that this family owes nothing to the
   * evidence profile, and a grid with a hole in it is harder to check than one with a flat row.
   */
  public static List<Cell> grid() {
    final List<Cell> cells = new ArrayList<>();
    for (final Family family : Family.values()) {
      for (final Profile profile : Profile.values()) {
        cells.add(Cell.of(family, profile));
      }
      final Cell full = Cell.of(family, Profile.LITERATURE_COMPATIBLE);
      if (full.attributes().stream().anyMatch(a -> a.name().equals(Cell.HOTNESS))) {
        cells.add(Cell.without(family, Profile.LITERATURE_COMPATIBLE, Cell.HOTNESS));
      }
    }
    return List.copyOf(cells);
  }

  private Fitted elasticNet(final Cell cell, final DesignMatrix matrix, final double[][] x,
      final double[] y, final double[][] xs, final double[] observed, final double baselineMae,
      final double constantMae, final double[] ownSpearman) {

    ElasticNetModel best = null;
    Score bestScore = null;
    final double lambdaMax = largestPenalty(x, y);
    for (final double alpha : ALPHAS) {
      for (int step = 0; step < LAMBDA_STEPS; step++) {
        final double lambda = lambdaMax
            * Math.pow(LAMBDA_FLOOR_RATIO, (double) step / (LAMBDA_STEPS - 1));
        final ElasticNetModel model = ElasticNetModel.fit(x, y, matrix.columns(), alpha, lambda);
        final Score score = scoreOf(cell, "Elastic Net", xs, observed, baselineMae, constantMae,
            model::predict);
        if (bestScore == null || score.maeHours() < bestScore.maeHours()) {
          best = model;
          bestScore = score;
        }
      }
    }
    final double[] weights = best.coefficients();
    final List<Contribution> contributions = new ArrayList<>(weights.length);
    for (int i = 0; i < weights.length; i++) {
      contributions.add(new Contribution(matrix.columns().get(i), weights[i], ownSpearman[i],
          best.selected(i)));
    }
    final double[] predicted = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      predicted[i] = DesignMatrix.hours(best.predict(xs[i]));
    }
    return new Fitted(cell, "Elastic Net", bestScore, matrix.columns(),
        String.format("alpha=%.2f lambda=%.4g", best.alpha(), best.lambda()),
        best.selectedCount(), List.copyOf(contributions), predicted);
  }

  private Fitted extraTrees(final Cell cell, final DesignMatrix matrix, final double[][] x,
      final double[] y, final double[][] xs, final double[] observed, final double baselineMae,
      final double constantMae,
      final List<String> unfitted) {

    ExtraTrees best = null;
    Score bestScore = null;
    for (final ExtraTrees.Settings settings : ExtraTrees.Settings.grid(SEED)) {
      final ExtraTrees model;
      try {
        model = ExtraTrees.fit(x, y, matrix.columns(), settings);
      } catch (final IllegalArgumentException e) {
        // Tribuo 4.3.2 fails on some node shapes -- see ExtraTrees.Settings.grid. Recorded by
        // name and skipped, never silently: a grid point that could not be evaluated is a fact
        // about the run, and hiding it would make the sweep look narrower than it was declared.
        unfitted.add(cell.label() + " | " + settings + " -> " + e.getMessage());
        continue;
      }
      final Score score =
          scoreOf(cell, "Extra Trees", xs, observed, baselineMae, constantMae, model::predict);
      if (bestScore == null || score.maeHours() < bestScore.maeHours()) {
        best = model;
        bestScore = score;
      }
    }
    if (best == null) {
      return null;
    }
    final double[] predicted = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      predicted[i] = DesignMatrix.hours(best.predict(xs[i]));
    }
    return new Fitted(cell, "Extra Trees", bestScore, matrix.columns(),
        best.settings().toString(), matrix.columns().size(), List.of(), predicted);
  }

  /** One scoring path for both estimators, so the two can never differ by how they were measured. */
  private static Score scoreOf(final Cell cell, final String algorithm, final double[][] xs,
      final double[] observed, final double baselineMae, final double constantMae,
      final java.util.function.ToDoubleFunction<double[]> predict) {

    final double[] hours = new double[xs.length];
    for (int i = 0; i < hours.length; i++) {
      hours[i] = DesignMatrix.hours(predict.applyAsDouble(xs[i]));
    }
    return Score.of(cell.label() + " | " + algorithm, hours, observed, baselineMae, constantMae);
  }

  /**
   * The largest penalty that still leaves any coefficient alive, which anchors the grid.
   *
   * <p>Above it every weight is zero and the sweep would be measuring the intercept. The same
   * function the previous paper's sweep uses.
   */
  private static double largestPenalty(final double[][] x, final double[] y) {
    double mean = 0;
    for (final double value : y) {
      mean += value;
    }
    mean /= y.length;
    double largest = 0;
    for (int c = 0; c < x[0].length; c++) {
      double dot = 0;
      for (int r = 0; r < x.length; r++) {
        dot += x[r][c] * (y[r] - mean);
      }
      largest = Math.max(largest, Math.abs(dot));
    }
    return largest;
  }

  /** What the report has to record next to every result, per section 1.8 of the complement. */
  public static Map<String, String> provenance(final int minResolved, final double trainShare) {
    final Map<String, String> facts = new LinkedHashMap<>();
    facts.put("populacao", minResolved <= 0 ? "sem filtro de historico herdado"
        : "MIN_RESOLVED=" + minResolved);
    facts.put("corte", "quantil " + trainShare + " da criacao, aplicado duas vezes");
    facts.put("alvo", "log1p(TTC em horas), invertido com expm1 e truncado em zero");
    facts.put("semente", String.valueOf(SEED));
    facts.put("grade Elastic Net", "alpha " + java.util.Arrays.toString(ALPHAS)
        + " x " + LAMBDA_STEPS + " lambdas log-espacados ate " + LAMBDA_FLOOR_RATIO
        + " do maior que importa, derivado dos dados");
    facts.put("grade Extra Trees", ExtraTrees.Settings.grid(SEED).size()
        + " combinacoes, 300 arvores, declaradas antes de rodar");
    // A family added after seeing a number has to say so wherever the number is printed, or a later
    // reader cannot tell a pre-registered comparison from one the data suggested.
    facts.put("familia post hoc", "CONTRIBUICAO_PROPRIA acrescentada em 27/09/2026, depois da"
        + " medicao por coluna. Seus tres perfis coincidem: todo construto dela e limpo em t0");
    facts.put("reservado", "nao tocado: esta tarefa so recebe o lado de treino");
    return facts;
  }
}
