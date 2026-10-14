package app.pipeline;

import app.model.Baselines;
import app.model.Bounds;
import app.model.ElasticNetModel;
import app.model.GradientBoostingModel;
import app.model.Metrics;
import app.model.Observation;
import app.model.Observations;
import app.model.Partition;
import app.model.Population;
import app.model.Standardizer;
import app.model.port.ObservationSource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Emits one row per held-out observation, per population, carrying every prediction the
 * comparison needs. Aggregation, per-project means and resampling intervals are left to the
 * analysis scripts: they are easier to audit as a few lines of Python beside the numbers than as
 * more Java here, and the per-observation file makes any of them recomputable without refitting.
 *
 * <p><b>Experiment A</b> is what this task runs: for each population, the whole procedure is
 * repeated — standardisation learned on the fit side, hyperparameters chosen on the inner split,
 * refit on the outer training side, scored once on the held-out side.
 *
 * <p><b>Experiment B</b> is not run separately, because it does not need to be. The row set that
 * experiment A scores for population P is exactly the P0 held-out set filtered by P's rule: the
 * split verdict on each label is the one computed over P0 and stored, and the population filter
 * only removes rows. So B — one model trained on P0, evaluated on the nested subsets — is
 * obtained by taking this file's P0 rows and subsetting them on the interval, with the P0 model's
 * predictions and the P0 references carried along unchanged. The analysis script does that, and
 * the two experiments therefore score identical row sets and differ only in which fit produced
 * the prediction, which is the contrast the design asks for.
 *
 * <p><b>The split is not recomputed per population.</b> Recomputing it would make the populations
 * stop nesting and would leave A and B scoring different rows, which is the one thing experiment B
 * exists to avoid. What recomputing would cost is measured separately, in the population report.
 */
public final class ExperimentsTask {

  private static final double[] ALPHAS = {0.1, 0.25, 0.5, 0.75, 0.95};
  private static final int LAMBDA_STEPS = 24;
  private static final double LAMBDA_FLOOR_RATIO = 1e-4;

  /** Modest on purpose: a tree ensemble is a control here, not a proposal. */
  private static final int[] TREES = {150, 300};
  private static final int[] MAX_NODES = {8, 24};
  private static final double[] SHRINKAGE = {0.05, 0.10, 0.20};

  /** One held-out observation with every prediction, all in days. */
  public record Row(String population, String issueId, String project, String developer,
      double ttcDays, double postCommitDays,
      double predElasticNet, double predBoosting,
      double refConstant, double refProject, double refPair) {}

  public record Chosen(String population, double alpha, double lambda,
      int trees, int maxNodes, double shrinkage,
      int fitRows, int selectionRows, int trainRows, int testRows,
      double innerMaeElasticNet, double innerMaeBoosting) {}

  public record Report(List<Row> rows, List<Chosen> chosen) {}

  private final ObservationSource source;
  private final Progress progress;

  public ExperimentsTask(final ObservationSource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  public Report run(final List<Population> populations, final Bounds bounds) {
    final List<Row> rows = new ArrayList<>();
    final List<Chosen> chosen = new ArrayList<>();
    final Observations fitAll = source.of(Partition.FIT);
    final Observations selAll = source.of(Partition.SELECTION);
    final Observations trainAll = source.of(Partition.TRAIN_ALL);
    final Observations testAll = source.of(Partition.HELD_OUT);

    int step = 0;
    for (final Population population : populations) {
      progress.phase(++step, populations.size(), "population %s", population.name());
      final List<Observation> fit = keep(fitAll, bounds, population);
      final List<Observation> selection = keep(selAll, bounds, population);
      final List<Observation> train = keep(trainAll, bounds, population);
      final List<Observation> test = keep(testAll, bounds, population);
      progress.detail("fit %,d  selection %,d  outer training %,d  test %,d",
          fit.size(), selection.size(), train.size(), test.size());

      final Standardizer inner = Standardizer.fittedOn(
          new Observations(fitAll.columns(), fit, 0));
      final double[][] x = matrix(inner, fit);
      final double[] y = outcome(fit);
      final double[][] xs = matrix(inner, selection);
      final Map<Observation, Integer> where = index(selection);

      progress.detail("sweeping the linear grid on the inner split");
      double bestAlpha = ALPHAS[0];
      double bestLambda = 0;
      double bestMae = Double.MAX_VALUE;
      final double lambdaMax = FitModelTask.largestPenalty(x, y);
      for (final double alpha : ALPHAS) {
        for (int s = 0; s < LAMBDA_STEPS; s++) {
          final double lambda =
              lambdaMax * Math.pow(LAMBDA_FLOOR_RATIO, s / (double) (LAMBDA_STEPS - 1));
          final ElasticNetModel candidate =
              ElasticNetModel.fit(x, y, inner.columns(), alpha, lambda);
          final double mae = Metrics.of(selection,
              row -> candidate.predict(xs[where.get(row)])).maeDays();
          if (mae < bestMae) {
            bestMae = mae;
            bestAlpha = alpha;
            bestLambda = lambda;
          }
        }
      }

      progress.detail("sweeping the tree grid on the inner split");
      int bestTrees = TREES[0];
      int bestNodes = MAX_NODES[0];
      double bestShrink = SHRINKAGE[0];
      double bestGbMae = Double.MAX_VALUE;
      for (final int trees : TREES) {
        for (final int nodes : MAX_NODES) {
          for (final double shrink : SHRINKAGE) {
            final GradientBoostingModel candidate =
                GradientBoostingModel.fit(x, y, inner.columns(), trees, nodes, shrink);
            final double mae = Metrics.of(selection,
                row -> candidate.predict(xs[where.get(row)])).maeDays();
            if (mae < bestGbMae) {
              bestGbMae = mae;
              bestTrees = trees;
              bestNodes = nodes;
              bestShrink = shrink;
            }
          }
        }
      }
      progress.detail("linear alpha=%.2f lambda=%.4g (MAE %.2f) | trees n=%d nodes=%d "
          + "rate=%.2f (MAE %.2f)", bestAlpha, bestLambda, bestMae,
          bestTrees, bestNodes, bestShrink, bestGbMae);

      progress.detail("refitting on the outer training side and scoring once");
      final Standardizer outer = Standardizer.fittedOn(
          new Observations(trainAll.columns(), train, 0));
      final double[][] xt = matrix(outer, train);
      final double[] yt = outcome(train);
      final double[][] xe = matrix(outer, test);
      final Map<Observation, Integer> whereTest = index(test);
      final ElasticNetModel en =
          ElasticNetModel.fit(xt, yt, outer.columns(), bestAlpha, bestLambda);
      final GradientBoostingModel gb =
          GradientBoostingModel.fit(xt, yt, outer.columns(), bestTrees, bestNodes, bestShrink);
      final ToDoubleFunction<Observation> constant = Baselines.constant(train);
      final ToDoubleFunction<Observation> perProject = Baselines.perProject(train);
      final ToDoubleFunction<Observation> perPair = Baselines.perPair(train);

      for (final Observation row : test) {
        final double[] r = xe[whereTest.get(row)];
        rows.add(new Row(population.name(), row.issueId(), row.project(), row.developer(),
            row.ttcDays(), row.postCommitDays(),
            Math.exp(en.predict(r)), Math.exp(gb.predict(r)),
            Math.exp(constant.applyAsDouble(row)),
            Math.exp(perProject.applyAsDouble(row)),
            Math.exp(perPair.applyAsDouble(row))));
      }
      chosen.add(new Chosen(population.name(), bestAlpha, bestLambda,
          bestTrees, bestNodes, bestShrink,
          fit.size(), selection.size(), train.size(), test.size(), bestMae, bestGbMae));
    }
    return new Report(List.copyOf(rows), List.copyOf(chosen));
  }

  private static List<Observation> keep(final Observations all, final Bounds bounds,
      final Population population) {
    return all.rows().stream().filter(bounds::admits).filter(population::admits).toList();
  }

  private static double[][] matrix(final Standardizer s, final List<Observation> rows) {
    final double[][] out = new double[rows.size()][];
    for (int i = 0; i < rows.size(); i++) {
      out[i] = s.apply(rows.get(i));
    }
    return out;
  }

  private static double[] outcome(final List<Observation> rows) {
    final double[] y = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      y[i] = rows.get(i).logTtc();
    }
    return y;
  }

  private static Map<Observation, Integer> index(final List<Observation> rows) {
    final Map<Observation, Integer> at = new IdentityHashMap<>(rows.size() * 2);
    for (int i = 0; i < rows.size(); i++) {
      at.put(rows.get(i), i);
    }
    return at;
  }

}
