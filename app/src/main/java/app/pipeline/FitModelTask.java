package app.pipeline;

import app.model.Baselines;
import app.model.Columns;
import app.model.DeveloperOrdering;
import app.model.ElasticNetModel;
import app.model.Metrics;
import app.model.Observation;
import app.model.Observations;
import app.model.Partition;
import app.model.Standardizer;
import app.model.port.ObservationSource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Fits the Elastic Net on the training side and scores it against the references the study names.
 *
 * <p>Coefficients are estimated before the inner cut and the two penalty parameters are chosen
 * after it. <b>The held-out test set is never read</b> — the source this task is given has no
 * constant for it.
 *
 * <p>The parameter grid is derived from the data rather than taken from convention, because the
 * library applies the penalty without normalising by the number of rows: a value that regularises
 * two thousand observations does nothing to twenty-five thousand.
 */
public final class FitModelTask {

  /** Mixing values swept; the endpoints are avoided because the library declines to be pure. */
  private static final double[] ALPHAS = {0.1, 0.25, 0.5, 0.75, 0.95};

  private static final int LAMBDA_STEPS = 24;

  /** How far below the largest penalty the grid reaches, log-spaced. */
  private static final double LAMBDA_FLOOR_RATIO = 1e-4;

  /** How alike two tasks must be, per matched column, before their developers are compared. */
  private static final double SIZE_TOLERANCE = 0.5;

  public record Scored(String name, Metrics metrics, DeveloperOrdering.Result ordering) {}

  public record Report(
      int fitRows, int selectionRows, int excludedEmptyTeamFit, int excludedEmptyTeamSelection,
      List<String> columns, List<String> droppedColumns,
      double alpha, double lambda, double[] coefficients, double intercept, int selectedColumns,
      double pairCoverage,
      List<Scored> comparison, Map<String, Scored> ablation) {}

  private final ObservationSource source;
  private final Progress progress;

  public FitModelTask(final ObservationSource source, final Progress progress) {
    this.source = source;
    this.progress = progress;
  }

  /** Thrown rather than fitting nothing, which would report an intercept as a finding. */
  public static final class NoObservationsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NoObservationsException(final String source) {
      super(source + " yielded no observations -- run funnel, split and features first");
    }
  }

  public Report run() {
    progress.phase(1, 4, "reading the two training partitions from %s", source.describe());
    final Observations fit = source.of(Partition.FIT);
    final Observations selection = source.of(Partition.SELECTION);
    if (fit.isEmpty() || selection.isEmpty()) {
      throw new NoObservationsException(source.describe());
    }
    progress.detail("%,d to fit, %,d to select on (%,d and %,d set aside for an empty team)",
        fit.rows().size(), selection.rows().size(),
        fit.excludedEmptyTeam(), selection.excludedEmptyTeam());

    progress.phase(2, 4, "standardising on the fit side only");
    final Standardizer standardizer = Standardizer.fittedOn(fit);
    final double[][] x = matrix(standardizer, fit.rows());
    final double[] y = outcome(fit.rows());
    final double[][] xs = matrix(standardizer, selection.rows());
    if (!standardizer.dropped().isEmpty()) {
      progress.detail("constant on the fit side, dropped: %s",
          String.join(", ", standardizer.dropped()));
    }

    progress.phase(3, 4, "sweeping the penalty grid, derived from the data");
    // Indexed once. Looking a row up by identity inside the sweep would be quadratic per
    // candidate, and there are a hundred and twenty candidates.
    final Map<Observation, Integer> where = index(selection.rows());
    final double lambdaMax = largestPenalty(x, y);
    ElasticNetModel best = null;
    double bestMae = Double.MAX_VALUE;
    for (final double alpha : ALPHAS) {
      for (int step = 0; step < LAMBDA_STEPS; step++) {
        final double lambda = lambdaMax
            * Math.pow(LAMBDA_FLOOR_RATIO, step / (double) (LAMBDA_STEPS - 1));
        final ElasticNetModel candidate =
            ElasticNetModel.fit(x, y, standardizer.columns(), alpha, lambda);
        final Metrics metrics =
            Metrics.of(selection.rows(), row -> candidate.predict(xs[where.get(row)]));
        if (metrics.maeDays() < bestMae) {
          bestMae = metrics.maeDays();
          best = candidate;
        }
      }
    }
    progress.detail("chose alpha=%.2f lambda=%.4g, %d of %d columns selected",
        best.alpha(), best.lambda(), best.selectedCount(), standardizer.columns().size());

    progress.phase(4, 4, "scoring against the references the study names");
    final ElasticNetModel chosen = best;
    final ToDoubleFunction<Observation> model = row -> chosen.predict(xs[where.get(row)]);

    final List<Scored> comparison = new ArrayList<>();
    comparison.add(score("chute constante", selection.rows(), Baselines.constant(fit.rows())));
    comparison.add(score("mediana do projeto", selection.rows(), Baselines.perProject(fit.rows())));
    comparison.add(score("REFERENCIA 3.6: mediana do par", selection.rows(),
        Baselines.perPair(fit.rows())));
    comparison.add(score("modelo completo", selection.rows(), model));

    final Map<String, Scored> ablation = new LinkedHashMap<>();
    ablation.put("so a tarefa", ablate(standardizer, fit, selection, chosen, Columns.Group.TAREFA));
    ablation.put("so o desenvolvedor",
        ablate(standardizer, fit, selection, chosen, Columns.Group.DEV));
    ablation.put("sem a carga", ablate(standardizer, fit, selection, chosen,
        Columns.Group.TAREFA, Columns.Group.DEV, Columns.Group.EQUIPE, Columns.Group.CRIACAO));
    ablation.put("sem a equipe", ablate(standardizer, fit, selection, chosen,
        Columns.Group.TAREFA, Columns.Group.DEV, Columns.Group.CARGA, Columns.Group.CRIACAO));
    ablation.put("conhecido na criacao", ablate(standardizer, fit, selection, chosen,
        Columns.Group.DEV, Columns.Group.CARGA, Columns.Group.EQUIPE, Columns.Group.CRIACAO));

    return new Report(fit.rows().size(), selection.rows().size(),
        fit.excludedEmptyTeam(), selection.excludedEmptyTeam(),
        standardizer.columns(), standardizer.dropped(),
        chosen.alpha(), chosen.lambda(), chosen.coefficients(), chosen.intercept(),
        chosen.selectedCount(), Baselines.pairCoverage(fit.rows(), selection.rows()),
        comparison, ablation);
  }

  private static Scored score(final String name, final List<Observation> rows,
      final ToDoubleFunction<Observation> prediction) {

    return new Scored(name, Metrics.of(rows, prediction),
        DeveloperOrdering.of(rows, prediction, Columns.comparableTask(), SIZE_TOLERANCE));
  }

  /** Refits on a subset of the columns, with the same protocol, to see what each group carries. */
  private Scored ablate(final Standardizer standardizer, final Observations fit,
      final Observations selection, final ElasticNetModel chosen, final Columns.Group... groups) {

    final int[] keep = indicesOf(standardizer.columns(), groups);
    if (keep.length == 0) {
      return new Scored("sem colunas", null, null);
    }
    final double[][] x = subset(matrix(standardizer, fit.rows()), keep);
    final double[][] xs = subset(matrix(standardizer, selection.rows()), keep);
    final List<String> names = new ArrayList<>();
    for (final int i : keep) {
      names.add(standardizer.columns().get(i));
    }
    final ElasticNetModel model = ElasticNetModel.fit(x, outcome(fit.rows()), names,
        chosen.alpha(), chosen.lambda());
    final Map<Observation, Integer> where = index(selection.rows());
    return score(names.size() + " colunas", selection.rows(),
        row -> model.predict(xs[where.get(row)]));
  }

  private static int[] indicesOf(final List<String> surviving, final Columns.Group... groups) {
    final List<String> wanted = new ArrayList<>();
    for (final int i : Columns.indicesOf(groups)) {
      wanted.add(Columns.ALL.get(i).name());
    }
    final List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < surviving.size(); i++) {
      if (wanted.contains(surviving.get(i))) {
        indices.add(i);
      }
    }
    return indices.stream().mapToInt(Integer::intValue).toArray();
  }

  private static double[][] subset(final double[][] rows, final int[] keep) {
    final double[][] out = new double[rows.length][keep.length];
    for (int r = 0; r < rows.length; r++) {
      for (int c = 0; c < keep.length; c++) {
        out[r][c] = rows[r][keep[c]];
      }
    }
    return out;
  }

  /** Row to position, by identity: the rows are records, so equals would compare arrays by hash. */
  private static Map<Observation, Integer> index(final List<Observation> rows) {
    final Map<Observation, Integer> where = new IdentityHashMap<>(rows.size() * 2);
    for (int i = 0; i < rows.size(); i++) {
      where.put(rows.get(i), i);
    }
    return where;
  }

  private static double[][] matrix(final Standardizer standardizer, final List<Observation> rows) {
    final double[][] out = new double[rows.size()][];
    for (int i = 0; i < rows.size(); i++) {
      out[i] = standardizer.apply(rows.get(i));
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

  /**
   * The smallest penalty that would silence every column, which anchors the top of the grid.
   *
   * <p>On standardised columns this is the largest absolute correlation with the centred outcome.
   * The library does not divide the penalty by the row count, so this scales with the sample and
   * cannot be replaced by a fixed number.
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
}
