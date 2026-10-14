package app.pullrequests.model;

import app.model.ElasticNetModel;
import app.pullrequests.PriorStudyFeatures;
import app.pullrequests.Row;
import app.pullrequests.RowCache;
import app.pullrequests.Unit;
import java.util.ArrayList;
import java.util.List;

/**
 * The two sides of one fit, prepared exactly as the reserved run and the inner check prepare them.
 *
 * <p>Written for the audit of 28/09/2026, which needs to fit variants of the frozen specifications
 * -- the same cell without some columns -- and so needs the preparation the frozen run used without
 * copying it by hand into a third place. It does not replace the preparation inside
 * {@code PullRequestReservedTask}: that code is what produced the registered result and stays as it was.
 * The audit instead <b>proves</b> this class equivalent to it, by refitting the five frozen
 * specifications here and requiring the predictions to be identical, double for double, to the
 * ones the reserved task returns. If they differ, the audit refuses to go on.
 *
 * @param fitUnits the rows every parameter, history and fill value is learned from
 * @param scoreUnits the rows being scored, which contribute nothing to what is learned
 */
public record Prepared(List<Unit> fitUnits, List<Row> fitRows, List<Unit> scoreUnits,
    List<Row> scoreRows, double[] y, double[] observed) {

  /** The reserved side: the whole outer training side fits, the outer test side is scored. */
  public static Prepared reserved(final List<RowCache.Entry> entries,
      final PriorStudyFeatures priorStudy) {
    return of(entries, priorStudy, true);
  }

  /** The inner selection: the inner training half fits, the inner selection half is scored. */
  public static Prepared inner(final List<RowCache.Entry> entries,
      final PriorStudyFeatures priorStudy) {
    return of(entries, priorStudy, false);
  }

  private static Prepared of(final List<RowCache.Entry> entries, final PriorStudyFeatures priorStudy,
      final boolean reserved) {

    final List<Unit> fitUnits = new ArrayList<>();
    final List<Row> fitRows = new ArrayList<>();
    final List<Unit> scoreUnits = new ArrayList<>();
    final List<Row> scoreRows = new ArrayList<>();
    for (final RowCache.Entry entry : entries) {
      final boolean fit = reserved ? "TRAIN".equals(entry.outer())
          : "TRAIN".equals(entry.outer()) && "TRAIN".equals(entry.inner());
      final boolean score = reserved ? "TEST".equals(entry.outer())
          : "TRAIN".equals(entry.outer()) && "TEST".equals(entry.inner());
      if (fit) {
        fitUnits.add(entry.unit());
        fitRows.add(entry.row());
      } else if (score) {
        scoreUnits.add(entry.unit());
        scoreRows.add(entry.row());
      }
    }
    final History history = History.learnedOn(fitUnits);
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
    final double[] y = new double[fitUnits.size()];
    for (int i = 0; i < y.length; i++) {
      y[i] = DesignMatrix.target(fitUnits.get(i));
    }
    final double[] observed = new double[scoreUnits.size()];
    for (int i = 0; i < observed.length; i++) {
      observed[i] = scoreUnits.get(i).durationHours();
    }
    return new Prepared(List.copyOf(fitUnits), fitRows, List.copyOf(scoreUnits), scoreRows, y,
        observed);
  }

  /** The standardised coefficients of one fit with given parameters, by column. */
  public java.util.Map<String, Double> weights(final Cell cell, final double alpha,
      final double lambda) {
    final DesignMatrix matrix = DesignMatrix.fittedOn(fitRows, cell.attributes());
    final ElasticNetModel model =
        ElasticNetModel.fit(matrix.applyAll(fitRows), y, matrix.columns(), alpha, lambda);
    final java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
    final double[] c = model.coefficients();
    for (int i = 0; i < c.length; i++) {
      out.put(matrix.columns().get(i), c[i]);
    }
    return out;
  }

  /** One fit with given parameters and a prediction per scored row, in hours. Searches nothing. */
  public double[] predict(final Cell cell, final double alpha, final double lambda) {
    final DesignMatrix matrix = DesignMatrix.fittedOn(fitRows, cell.attributes());
    final ElasticNetModel model =
        ElasticNetModel.fit(matrix.applyAll(fitRows), y, matrix.columns(), alpha, lambda);
    final double[][] xs = matrix.applyAll(scoreRows);
    final double[] predicted = new double[xs.length];
    for (int i = 0; i < xs.length; i++) {
      predicted[i] = DesignMatrix.hours(model.predict(xs[i]));
    }
    return predicted;
  }
}
