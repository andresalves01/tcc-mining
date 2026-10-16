package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.model.ProjectBootstrap;
import app.issues.model.References;
import app.model.Parallel;
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
import java.util.concurrent.Callable;

/**
 * The test of the description-similarity chain, outer mode only: every model refitted on the
 * outer training set with the parameters the selection step wrote, and the outer test scored
 * once. Chooses nothing.
 *
 * <p><b>Models</b>, seventeen, in this order: the three references learned on the outer training
 * set -- the global median, the project median and the executor-project median, as the registered
 * chain learns them; the seven Elastic Net models of {@link ElasticNetSelection#cells(Map,
 * Augmentation.Variant)}, in the outer variant, each model with the similarity medians reading
 * them at the Jaccard n, embedding n and cluster k chosen for it, the retrospective one last
 * (ticket 15); and the seven random forests beside them ({@link #forest(String)}), a robustness
 * check (ticket 13). A similarity median is an attribute of the models that read it and is never
 * scored alone (ticket 11).
 *
 * <p><b>Refit.</b> As the registered reserved run does it: the alpha given, the lambda given
 * times the ratio of the outer training rows to the inner fitting rows ({@link
 * Fitting#finalLambda}), one fit by {@link Fitting#frozen}, no recalibration. The new columns get
 * their missing indicators as in selection; a declared column missing on every outer training row
 * is removed, with the reason recorded, as selection removes one missing on every inner fitting
 * row.
 *
 * <p><b>Forests.</b> Each on the rows, columns, missing indicators and removals of its Elastic Net
 * twin (every column it kept, weighted or not), at the point of the grid the selection chose
 * ({@link Forest#frozen}): the same count of columns drawn at each split and the same leaf. Per
 * forest, the permutation importance of each design column on the outer test is kept, a
 * diagnostic.
 *
 * <p><b>Readings.</b> Per model: the error, Spearman and pairwise ordering ({@link Score}), the
 * error's distribution with the slowest tenth left out ({@link Breakdown#errors}) and the error by
 * band of the true business days, 1, 2-3, 4-10 and 11 or more ({@link BusinessDays#bands}).
 * Paired comparisons, gap in mean absolute error with the interval from resampling whole projects
 * ({@link ProjectBootstrap#compare}), for exactly the pairs of {@link #pairs}. Beside them, a side
 * reading (ticket 15): the pairs of {@link #WITH_COMMITS} again on the scored issues with at least
 * one implementation commit only, those with the retrospective Java lines ({@link
 * Retrospective#LINES}) observed.
 *
 * <p><b>Unit</b> (ticket 14). Every duration, prediction and error is in whole business days
 * ({@link BusinessDays}), the outcome each unit carries ({@link IssueUnit#duration}); the shared
 * names of the fitting code are unit-neutral (ticket 17). The entries must be
 * {@link BusinessDays#population}'s, the issues of at least one business day: an entry whose
 * business days are not set refuses the run.
 */
public final class SimilarityScoring {

  /**
   * Two runs on the same inputs and parameters agree on every prediction to this many business
   * days. The fit is deterministic, so a rerun on the same machine reproduces it exactly; the
   * tolerance is what another machine's floating point may move a prediction by.
   */
  public static final double RERUN_TOLERANCE = 1e-6;

  public static final String GLOBAL = "global_median";
  public static final String PROJECT = "project_median";
  public static final String PAIR = "executor_project_median";

  private static final String RETROSPECTIVE = ElasticNetSelection.RETROSPECTIVE;

  private static final String WITH_SIMILARITY = "own_with_similarity";

  /**
   * The comparisons of the side reading on the scored issues with an implementation commit: the
   * retrospective model against the own specification with similarity, Elastic Net and forest.
   */
  public static final List<String[]> WITH_COMMITS = List.of(
      new String[] {RETROSPECTIVE, WITH_SIMILARITY},
      new String[] {forest(RETROSPECTIVE), forest(WITH_SIMILARITY)});

  private static final Population P = Population.IN_PROGRESS;

  private static final String FITTING_SET = "outer training";

  /**
   * One model scored on the outer test.
   *
   * @param kind {@code reference}, {@code elastic-net} or {@code forest}
   * @param fitted the refit, for an Elastic Net model only; null otherwise
   * @param removed the declared columns left out, for an Elastic Net model or a forest; empty
   *     otherwise
   * @param forest the refit, for a forest only; null otherwise
   */
  public record Scored(String name, String kind, double[] predicted, Score score,
      Breakdown.Errors errors, List<Breakdown.Band> bands, Fitting.Fitted fitted,
      List<ElasticNetSelection.Removed> removed, Forest.Frozen forest) {}

  /**
   * @param innerFitRows the rows the given lambdas were chosen on, which the final lambda scales
   *     by
   * @param unseenProjects outer test projects with no outer training row, where the project
   *     median is the global one
   * @param withCommitRows the scored issues with at least one implementation commit
   * @param withCommits the side reading: the pairs of {@link #WITH_COMMITS} on those issues only
   */
  public record Report(int fitRows, int scoredRows, int innerFitRows,
      List<IssueUnit> scoredUnits, double[] observed, List<Scored> models,
      List<ProjectBootstrap.Gap> comparisons, Set<String> unseenProjects, int unseenRows,
      int withCommitRows, List<ProjectBootstrap.Gap> withCommits) {}

  private SimilarityScoring() {}

  /** The name of the forest beside an Elastic Net model. */
  public static String forest(final String model) {
    return model + "_forest";
  }

  /**
   * Refits and scores.
   *
   * @param choices per Elastic Net model, its alpha and lambda chosen on the inner validation and,
   *     for a model with the similarity medians, its combination; exactly the models of {@link
   *     ElasticNetSelection#MODELS}, a combination for exactly those of {@link
   *     ElasticNetSelection#WITH_SIMILARITY}, or the run refuses
   * @param forests per Elastic Net model, its forest's column share and leaf chosen on the inner
   *     validation; exactly the models of {@link ElasticNetSelection#MODELS}, or the run refuses
   */
  public static Report run(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests) {
    return run(entries, choices, forests, Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * Refits and scores, the models refitted each alone on up to {@code fitThreads} threads and the
   * draws of every interval evaluated each alone on up to {@code drawThreads}, all read back in
   * their order, so the report is the same bits whatever the numbers.
   */
  public static Report run(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final int fitThreads, final int drawThreads) {
    if (!new LinkedHashSet<>(ElasticNetSelection.MODELS).equals(choices.keySet())) {
      throw new IllegalArgumentException("the parameters hold alpha and lambda for "
          + choices.keySet() + "; the models are " + ElasticNetSelection.MODELS);
    }
    if (!new LinkedHashSet<>(ElasticNetSelection.MODELS).equals(forests.keySet())) {
      throw new IllegalArgumentException("the parameters hold a forest for " + forests.keySet()
          + "; the models are " + ElasticNetSelection.MODELS);
    }
    BusinessDays.counted(entries);
    final List<Cell> cells =
        ElasticNetSelection.cells(ElasticNetSelection.combinations(choices),
            Augmentation.Variant.OUTER);
    final Fitting.Sides sides = ElasticNetSelection.flagged(
        Fitting.sides(entries, Fitting.Mode.RESERVED, P), cells);
    final int innerFitRows = Fitting.sides(entries, Fitting.Mode.INNER, P).fitUnits().size();
    final int fitRows = sides.fitUnits().size();
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();

    final References refs = References.learnedOn(sides.fitUnits());
    final double[] globalMedian = refs.constant(observed.length);
    final double[] projectMedian = refs.projectMedian(sides.scoreUnits());
    final double constantMae = Fitting.mae(globalMedian, observed);
    final double projectMae = Fitting.mae(projectMedian, observed);

    final List<Scored> models = new ArrayList<>();
    models.add(given(GLOBAL, "reference", globalMedian, observed, projectMae, constantMae));
    models.add(given(PROJECT, "reference", projectMedian, observed, projectMae, constantMae));
    models.add(given(PAIR, "reference", refs.pairMedian(sides.scoreUnits()), observed, projectMae,
        constantMae));

    final List<Callable<Scored>> refits = new ArrayList<>(cells.size());
    for (final Cell cell : cells) {
      refits.add(() -> {
        final ElasticNetSelection.Choice c = choices.get(cell.label());
        final ElasticNetSelection.Kept kept =
            ElasticNetSelection.kept(cell, sides.fitRows(), FITTING_SET);
        final Fitting.Fitted f = Fitting.frozen(kept.cell(), sides, c.alpha(),
            Fitting.finalLambda(c.lambda(), fitRows, innerFitRows), projectMae, constantMae);
        return new Scored(cell.label(), "elastic-net", f.predicted(), f.score(),
            Breakdown.errors(f.predicted(), observed),
            BusinessDays.bands(f.predicted(), observed), f, kept.removed(), null);
      });
    }
    models.addAll(Parallel.map(fitThreads, refits));

    final List<Callable<Scored>> trees = new ArrayList<>(cells.size());
    for (final Cell cell : cells) {
      trees.add(() -> {
        final Forest.Choice c = forests.get(cell.label());
        final ElasticNetSelection.Kept kept =
            ElasticNetSelection.kept(cell, sides.fitRows(), FITTING_SET);
        final String name = forest(cell.label());
        final Forest.Frozen f = Forest.frozen(new Cell(name, cell.family(), cell.scenario(),
            kept.cell().attributes()), sides, c, projectMae, constantMae);
        return new Scored(name, "forest", f.predicted(), f.score(),
            Breakdown.errors(f.predicted(), observed),
            BusinessDays.bands(f.predicted(), observed), null, kept.removed(), f);
      });
    }
    models.addAll(Parallel.map(fitThreads, trees));

    final Map<String, double[]> errors = new HashMap<>();
    for (final Scored m : models) {
      errors.put(m.name(), Bootstrap.errors(m.predicted(), observed));
    }
    final List<ProjectBootstrap.Gap> comparisons = new ArrayList<>();
    for (final String[] pair : pairs(models.stream().map(Scored::name).toList())) {
      comparisons.add(ProjectBootstrap.compare(projects, pair[0], errors.get(pair[0]), pair[1],
          errors.get(pair[1]), drawThreads));
    }
    final List<Integer> withCommit = new ArrayList<>();
    for (int i = 0; i < sides.scoreRows().size(); i++) {
      if (sides.scoreRows().get(i).values().containsKey(Retrospective.LINES)) {
        withCommit.add(i);
      }
    }
    final List<ProjectBootstrap.Gap> side = new ArrayList<>();
    if (!withCommit.isEmpty()) {
      final List<String> sideProjects = withCommit.stream().map(projects::get).toList();
      for (final String[] pair : WITH_COMMITS) {
        side.add(ProjectBootstrap.compare(sideProjects, pair[0],
            rows(errors.get(pair[0]), withCommit), pair[1], rows(errors.get(pair[1]), withCommit),
            drawThreads));
      }
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
    return new Report(fitRows, observed.length, innerFitRows, List.copyOf(sides.scoreUnits()),
        observed, List.copyOf(models), List.copyOf(comparisons), unseen, unseenRows,
        withCommit.size(), List.copyOf(side));
  }

  private static double[] rows(final double[] values, final List<Integer> rows) {
    final double[] out = new double[rows.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = values[rows.get(k)];
    }
    return out;
  }

  /**
   * The paired comparisons, {@code a} against {@code b}, and only these. First, of the references
   * and Elastic Net models: the own specification with similarity against the same without the
   * similarity medians, what similarity adds beside the other attributes; the retrospective model
   * against the own specification with similarity, what the eight attributes observed up to the
   * resolution add (ticket 15); then each of them against the global median, and each but the
   * global median against the project median, so the two references are compared once. Then, of
   * the forests (ticket 13), in the order of {@code models}: each forest against its Elastic Net;
   * the forest of the own specification with similarity against the forest without; the
   * retrospective forest against the forest with similarity; each forest against the global
   * median; each forest against the project median.
   */
  public static List<String[]> pairs(final List<String> models) {
    final List<String> forests = new ArrayList<>();
    for (final String m : ElasticNetSelection.MODELS) {
      forests.add(forest(m));
    }
    final List<String> linear = models.stream().filter(m -> !forests.contains(m)).toList();
    final List<String> trees = models.stream().filter(forests::contains).toList();
    final List<String[]> out = new ArrayList<>(List.<String[]>of(
        new String[] {"own_with_similarity", "own_without_similarity"}));
    if (linear.containsAll(List.of(RETROSPECTIVE, WITH_SIMILARITY))) {
      out.add(WITH_COMMITS.get(0));
    }
    for (final String m : linear) {
      if (!m.equals(GLOBAL)) {
        out.add(new String[] {m, GLOBAL});
      }
    }
    for (final String m : linear) {
      if (!m.equals(GLOBAL) && !m.equals(PROJECT)) {
        out.add(new String[] {m, PROJECT});
      }
    }
    if (trees.isEmpty()) {
      return List.copyOf(out);
    }
    for (final String f : trees) {
      out.add(new String[] {f, f.substring(0, f.length() - forest("").length())});
    }
    if (trees.containsAll(List.of(forest("own_with_similarity"),
        forest("own_without_similarity")))) {
      out.add(new String[] {forest("own_with_similarity"), forest("own_without_similarity")});
    }
    if (trees.containsAll(List.of(forest(RETROSPECTIVE), forest(WITH_SIMILARITY)))) {
      out.add(WITH_COMMITS.get(1));
    }
    for (final String f : trees) {
      out.add(new String[] {f, GLOBAL});
    }
    for (final String f : trees) {
      out.add(new String[] {f, PROJECT});
    }
    return List.copyOf(out);
  }

  /**
   * The predictions file: a header, then one line per scored issue -- its id, key, project and
   * observed business days, then each model's prediction in business days, as Java prints a
   * double.
   */
  public static List<String> predictions(final Report r) {
    final List<String> out = new ArrayList<>(r.scoredRows() + 1);
    final StringBuilder header = new StringBuilder("id\tkey\tproject\tobserved_business_days");
    r.models().forEach(m -> header.append('\t').append(m.name()));
    out.add(header.toString());
    for (int i = 0; i < r.scoredRows(); i++) {
      final IssueUnit u = r.scoredUnits().get(i);
      final StringBuilder line = new StringBuilder();
      line.append(u.issueId()).append('\t').append(u.key()).append('\t').append(u.project())
          .append('\t').append(r.observed()[i]);
      for (final Scored m : r.models()) {
        line.append('\t').append(m.predicted()[i]);
      }
      out.add(line.toString());
    }
    return out;
  }

  /**
   * The largest difference, in business days, between two predictions files of the same issues and
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

  private static Scored given(final String name, final String kind, final double[] predicted,
      final double[] observed, final double projectMae, final double constantMae) {
    return new Scored(name, kind, predicted,
        Score.of(name, predicted, observed, projectMae, constantMae),
        Breakdown.errors(predicted, observed), BusinessDays.bands(predicted, observed), null,
        List.of(), null);
  }
}
