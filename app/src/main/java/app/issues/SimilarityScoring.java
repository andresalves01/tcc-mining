package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.study.model.Bootstrap;
import app.study.model.Breakdown;
import app.study.model.Score;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The test of the description-similarity chain, outer mode only: every model refitted on the
 * outer training set with the parameters the two selection steps wrote, and the outer test scored
 * once. Chooses nothing.
 *
 * <p><b>Models</b>, in this order: the three references learned on the outer training set -- the
 * global median, the project median and the executor-project median, as the registered chain
 * learns them; the three direct predictors, each the similarity median at its chosen value read
 * as the prediction in hours, the outer training set's global median where it is missing; and the
 * seven Elastic Net models of {@link ElasticNetSelection#cells}, in the outer variant.
 *
 * <p><b>Refit.</b> As the registered reserved run does it: the alpha given, the lambda given
 * times the ratio of the outer training rows to the inner fitting rows ({@link
 * Fitting#finalLambda}), one fit by {@link Fitting#frozen}, no recalibration. The new columns get
 * their missing indicators as in selection; a declared column missing on every outer training row
 * is removed, with the reason recorded, as selection removes one missing on every inner fitting
 * row.
 *
 * <p><b>Readings.</b> Per model: the error in hours and days, Spearman and pairwise ordering
 * ({@link Score}), the error by band of the true duration with the slowest tenth apart ({@link
 * Breakdown#byTruth}). Paired comparisons, gap in mean absolute error with the interval from
 * resampling whole projects ({@link ProjectBootstrap#compare}), for exactly the pairs of {@link
 * #pairs}.
 */
public final class SimilarityScoring {

  /**
   * Two runs on the same inputs and parameters agree on every prediction to this many hours. The
   * fit is deterministic, so a rerun on the same machine reproduces it exactly; the tolerance is
   * what another machine's floating point may move a prediction by.
   */
  public static final double RERUN_TOLERANCE_HOURS = 1e-6;

  public static final String GLOBAL = "global_median";
  public static final String PROJECT = "project_median";
  public static final String PAIR = "executor_project_median";

  private static final Population P = Population.IN_PROGRESS;

  private static final String FITTING_SET = "outer training";

  /**
   * One model scored on the outer test.
   *
   * @param kind {@code reference}, {@code direct} or {@code elastic-net}
   * @param fitted the refit, for an Elastic Net model only; null otherwise
   * @param removed the declared columns left out, for an Elastic Net model; empty otherwise
   */
  public record Scored(String name, String kind, double[] predictedHours, Score score,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Fitting.Fitted fitted,
      List<ElasticNetSelection.Removed> removed) {}

  /**
   * A direct predictor's column and its coverage.
   *
   * @param observed outer test rows where the column has a value; the others read the fallback
   * @param maeObservedHours the error on those rows alone; NaN when there is none
   */
  public record Direct(String name, String column, int observed, double maeObservedHours) {}

  /**
   * @param innerFitRows the rows the given lambdas were chosen on, which the final lambda scales
   *     by
   * @param fallbackHours the outer training set's global median, a direct predictor's missing value
   * @param unseenProjects outer test projects with no outer training row, where the project
   *     median is the global one
   */
  public record Report(int fitRows, int scoredRows, int innerFitRows, double fallbackHours,
      List<IssueUnit> scoredUnits, double[] observed, List<Scored> models, List<Direct> directs,
      List<ProjectBootstrap.Gap> comparisons, Set<String> unseenProjects, int unseenRows) {}

  private SimilarityScoring() {}

  /** A direct predictor's name: the method's, {@code jaccard_median} giving {@code jaccard_direct}. */
  public static String direct(final String method) {
    return method.replace("_median", "") + "_direct";
  }

  /**
   * Refits and scores.
   *
   * @param chosen per similarity method, the candidate value the direct predictors chose
   * @param choices per Elastic Net model, its alpha and lambda chosen on the inner validation;
   *     exactly the models of {@link ElasticNetSelection#cells}, or the run refuses
   */
  public static Report run(final List<RowCache.Entry> entries, final Map<String, Integer> chosen,
      final Map<String, ElasticNetSelection.Choice> choices) {
    final List<Cell> cells = ElasticNetSelection.cells(chosen, Augmentation.Variant.OUTER);
    final Set<String> names = new LinkedHashSet<>(cells.stream().map(Cell::label).toList());
    if (!names.equals(choices.keySet())) {
      throw new IllegalArgumentException("the parameters hold alpha and lambda for "
          + choices.keySet() + "; the models are " + names);
    }
    final Fitting.Sides sides = ElasticNetSelection.flagged(
        Fitting.sides(entries, Fitting.Mode.RESERVED, P), cells);
    final int innerFitRows = Fitting.sides(entries, Fitting.Mode.INNER, P).fitUnits().size();
    final int fitRows = sides.fitUnits().size();
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();

    final References refs = References.learnedOn(sides.fitUnits());
    final double[] globalHours = refs.constant(observed.length);
    final double[] projectHours = refs.projectHours(sides.scoreUnits());
    final double constantMae = Fitting.mae(globalHours, observed);
    final double projectMae = Fitting.mae(projectHours, observed);

    final List<Scored> models = new ArrayList<>();
    models.add(given(GLOBAL, "reference", globalHours, observed, projectMae, constantMae));
    models.add(given(PROJECT, "reference", projectHours, observed, projectMae, constantMae));
    models.add(given(PAIR, "reference", refs.pairHours(sides.scoreUnits()), observed, projectMae,
        constantMae));

    final List<Direct> directs = new ArrayList<>();
    for (final String method : ElasticNetSelection.METHODS) {
      final int value = chosen.get(method);
      final String column = switch (method) {
        case "jaccard_median" -> Augmentation.jaccard(value, Augmentation.Variant.OUTER);
        case "embedding_median" -> Augmentation.embedding(value, Augmentation.Variant.OUTER);
        default -> Augmentation.cluster(value, Augmentation.Variant.OUTER);
      };
      final double[] predicted = new double[observed.length];
      int seen = 0;
      double seenError = 0;
      for (int i = 0; i < predicted.length; i++) {
        final Double v = sides.scoreRows().get(i).values().get(column);
        if (v != null && !Double.isFinite(v)) {
          throw new IllegalArgumentException(column + " of "
              + sides.scoreUnits().get(i).issueId() + " is " + v + ", not a duration");
        }
        predicted[i] = v == null ? refs.constantHours() : v;
        if (v != null) {
          seen++;
          seenError += Math.abs(v - observed[i]);
        }
      }
      directs.add(new Direct(direct(method), column, seen,
          seen == 0 ? Double.NaN : seenError / seen));
      models.add(given(direct(method), "direct", predicted, observed, projectMae, constantMae));
    }

    for (final Cell cell : cells) {
      final ElasticNetSelection.Choice c = choices.get(cell.label());
      final ElasticNetSelection.Kept kept =
          ElasticNetSelection.kept(cell, sides.fitRows(), FITTING_SET);
      final Fitting.Fitted f = Fitting.frozen(kept.cell(), sides, c.alpha(),
          Fitting.finalLambda(c.lambda(), fitRows, innerFitRows), projectMae, constantMae);
      models.add(new Scored(cell.label(), "elastic-net", f.predictedHours(), f.score(),
          Breakdown.errors(f.predictedHours(), observed),
          Breakdown.byTruth(f.predictedHours(), observed), f, kept.removed()));
    }

    final Map<String, double[]> errors = new HashMap<>();
    for (final Scored m : models) {
      errors.put(m.name(), Bootstrap.errors(m.predictedHours(), observed));
    }
    final List<ProjectBootstrap.Gap> comparisons = new ArrayList<>();
    for (final String[] pair : pairs(models.stream().map(Scored::name).toList())) {
      comparisons.add(ProjectBootstrap.compare(projects, pair[0], errors.get(pair[0]), pair[1],
          errors.get(pair[1])));
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
    return new Report(fitRows, observed.length, innerFitRows, refs.constantHours(),
        List.copyOf(sides.scoreUnits()), observed, List.copyOf(models), List.copyOf(directs),
        List.copyOf(comparisons), unseen, unseenRows);
  }

  /**
   * The paired comparisons, {@code a} against {@code b}, and only these: revised nine against the
   * previous specification; revised twelve against revised nine; the embedding direct predictor
   * against Jaccard's; the cluster direct predictor against the embedding one; then every model
   * against the global median, and every model but the global median against the project median,
   * so the two references are compared once.
   */
  public static List<String[]> pairs(final List<String> models) {
    final List<String[]> out = new ArrayList<>(List.of(
        new String[] {"revised_nine", "previous_own_start"},
        new String[] {"revised_twelve", "revised_nine"},
        new String[] {direct("embedding_median"), direct("jaccard_median")},
        new String[] {direct("cluster_median"), direct("embedding_median")}));
    for (final String m : models) {
      if (!m.equals(GLOBAL)) {
        out.add(new String[] {m, GLOBAL});
      }
    }
    for (final String m : models) {
      if (!m.equals(GLOBAL) && !m.equals(PROJECT)) {
        out.add(new String[] {m, PROJECT});
      }
    }
    return List.copyOf(out);
  }

  /**
   * The predictions file: a header, then one line per scored issue -- its id, key, project and
   * observed duration in hours, then each model's prediction in hours, as Java prints a double.
   */
  public static List<String> predictions(final Report r) {
    final List<String> out = new ArrayList<>(r.scoredRows() + 1);
    final StringBuilder header = new StringBuilder("id\tkey\tproject\tobserved_h");
    r.models().forEach(m -> header.append('\t').append(m.name()));
    out.add(header.toString());
    for (int i = 0; i < r.scoredRows(); i++) {
      final IssueUnit u = r.scoredUnits().get(i);
      final StringBuilder line = new StringBuilder();
      line.append(u.issueId()).append('\t').append(u.key()).append('\t').append(u.project())
          .append('\t').append(r.observed()[i]);
      for (final Scored m : r.models()) {
        line.append('\t').append(m.predictedHours()[i]);
      }
      out.add(line.toString());
    }
    return out;
  }

  /**
   * The largest difference, in hours, between two predictions files of the same issues and
   * models, line by line; refuses when the header, the issues or their order differ.
   */
  public static double maxDifference(final List<String> before, final List<String> after) {
    if (before.size() != after.size() || before.isEmpty()
        || !before.get(0).equals(after.get(0))) {
      throw new IllegalArgumentException("the two predictions files are not of the same issues "
          + "and models");
    }
    double largest = 0;
    for (int line = 1; line < before.size(); line++) {
      final String[] a = before.get(line).split("\t", -1);
      final String[] b = after.get(line).split("\t", -1);
      if (a.length != b.length || !a[0].equals(b[0])) {
        throw new IllegalArgumentException("line " + line + " is not of the same issue: " + a[0]
            + " and " + b[0]);
      }
      for (int f = 4; f < a.length; f++) {
        largest = Math.max(largest, Math.abs(Double.parseDouble(a[f])
            - Double.parseDouble(b[f])));
      }
    }
    return largest;
  }

  private static Scored given(final String name, final String kind, final double[] hours,
      final double[] observed, final double projectMae, final double constantMae) {
    return new Scored(name, kind, hours, Score.of(name, hours, observed, projectMae, constantMae),
        Breakdown.errors(hours, observed), Breakdown.byTruth(hours, observed), null, List.of());
  }
}
