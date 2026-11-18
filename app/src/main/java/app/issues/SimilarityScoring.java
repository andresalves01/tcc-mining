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
 * The study's scoring, in one of two {@link Scope}s. The test, {@link
 * Scope#RESERVED}: every model refitted on the outer training set with the parameters the
 * selection step wrote, and the outer test scored once. The inner report, {@link Scope#INNER}
 * (ticket 16): the same models refitted on the inner fitting rows at those parameters and the
 * inner validation scored, the very validation they were chosen on. Chooses nothing. Below, the
 * outer training and the outer test are the inner fitting and the inner validation in the inner
 * scope.
 *
 * <p><b>Models</b>, fourteen, the same in both scopes, in this order: the two references learned
 * on the fitting side -- the global median and the project median; the six Elastic Net models of
 * {@link ElasticNetSelection#cells(Map,
 * Augmentation.Variant)}, in the scope's variant, each model with the similarity medians reading
 * them at the Jaccard n, embedding n and cluster k chosen for it: the own start model, Al-Zubaidi
 * and Kikas at the start, the main reading of the own retrospective model, the same with the
 * comments and the participants, a ceiling and not a competitor, and Kikas's retrospective one;
 * and the six random forests beside them ({@link #forest(String)}), a robustness check. A
 * similarity median is an attribute of the models that read it and is never scored alone, but by
 * the analogy estimate: when the parameters freeze its choice ({@link AnalogyEstimate.Frozen}, the
 * first-commit study's temporal split), it is a fifteenth model beside the references, its column
 * read as it is with no model around it.
 *
 * <p><b>Refit.</b> The alpha is kept and the lambda is multiplied by the ratio of the outer
 * training rows to the inner fitting rows ({@link
 * Fitting#finalLambda}), one fit by {@link Fitting#frozen}, no recalibration. The new columns get
 * their missing indicators as in selection; a declared column missing on every outer training row
 * is removed, with the reason recorded, as selection removes one missing on every inner fitting
 * row.
 *
 * <p><b>Forests.</b> Each on the rows, columns, missing indicators and removals of its Elastic Net
 * twin (every column it kept, weighted or not), at the point of the grid the selection chose
 * ({@link Forest#frozen}): the same count of columns drawn at each split and the same leaf. In
 * the inner report only, per forest, the permutation importance of each design column on the
 * scored issues is kept; the outer test reads none, which would read its labels column by
 * column.
 *
 * <p><b>Relevance</b> ({@link #relevance}). Per Elastic Net model, each standardised column's
 * coefficient in the refit, with its 95% interval from resampling whole projects of the fitting
 * side: on each of the same draws the comparisons read, the model refitted as above on the drawn
 * projects' fitting rows. The same in both scopes; on the outer test it reads the outer training
 * set alone.
 *
 * <p><b>Readings.</b> Per model: the error, Spearman and pairwise ordering ({@link Score}), the
 * error's distribution with the slowest tenth left out ({@link Breakdown#errors}) and the error by
 * band of the true business days, the bands at the quartiles of the observed business days of the
 * cut fitting rows ({@link BusinessDays#bands}, ticket 24; {@link Report#bandTops}).
 * Paired comparisons, gap in mean absolute error with the interval from resampling whole projects
 * ({@link ProjectBootstrap#compare}), for exactly the pairs of {@link #pairs}. Beside them, a side
 * reading (ticket 15): the pairs of {@link #WITH_COMMITS}, of the main reading of the
 * retrospective model, again on the scored issues with at least one implementation commit only,
 * those with the retrospective Java lines ({@link Retrospective#LINES}) observed, and, for
 * contrast, on those without one. The ordering
 * intervals (ticket 16, a declared measure, stated before the outer test was opened): for every
 * pair of {@link #orderingPairs}, the paired difference in Spearman and in pairwise ordering with
 * its interval from the same draws of whole projects ({@link ProjectBootstrap#ordering}).
 *
 * <p><b>Tail cut</b> (ticket 23, item 50; {@link TailCut}). Every model, forest and reference is
 * refitted on the fitting rows at or below the fence of the k the selection chose, {@code Q3 + k
 * IQR} of the business days of the scope's fitting side (the inner fitting set, or the outer
 * training set), never of the scored side; the histories, columns of the matrix, are read as they
 * are. Each scope is read twice ({@link #readings}): on the usual flow, the scored issues at or
 * below the fence at {@link TailCut#USUAL_FLOW_K} of the same fitting side, the main reading;
 * and on every scored issue, the complementary one. Each reading has its own figures, every one
 * of those above.
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

  /**
   * The analogy estimate ({@link AnalogyEstimate}), the fifteenth model of the first-commit
   * study's temporal split, scored beside the references when the parameters freeze its choice.
   */
  public static final String ANALOGY = "analogy_estimate";

  /**
   * The models the analogy estimate is compared with beyond the two references, which {@link
   * #pairs} draws for every model: the three start models and their forests, the models an SPSP
   * would use at the start, as the exploratory comparison drew them.
   */
  public static final List<String> ANALOGY_AGAINST = List.of(
      ElasticNetSelection.BEFORE_PRESSURE, ElasticNetSelection.ALZUBAIDI_START,
      ElasticNetSelection.KIKAS_START, forest(ElasticNetSelection.BEFORE_PRESSURE),
      forest(ElasticNetSelection.ALZUBAIDI_START), forest(ElasticNetSelection.KIKAS_START));

  private static final String RETROSPECTIVE = ElasticNetSelection.RETROSPECTIVE;

  private static final String BEFORE_PRESSURE = ElasticNetSelection.BEFORE_PRESSURE;

  private static final String KIKAS_RETROSPECTIVE = ElasticNetSelection.KIKAS_RETROSPECTIVE;

  private static final String WITH_DISCUSSION = ElasticNetSelection.WITH_DISCUSSION;

  /**
   * The agreed comparisons of two Elastic Net models, {@code a} against {@code b}, in their order;
   * the forests compare in the same pairs. The own start model against Al-Zubaidi and against
   * Kikas at the start, the own attributes against the literature's; the main reading of the
   * retrospective model against the own start model it builds on, what the structure of the
   * change observed up to the resolution adds to the start; the ceiling against the main reading,
   * what the comments and the participants add; the ceiling against Kikas's retrospective model,
   * both counting comments, the fair literature comparison; the main reading against Kikas's,
   * Kikas's counting comments and the main reading not.
   */
  public static final List<String[]> AGREED = List.of(
      new String[] {BEFORE_PRESSURE, ElasticNetSelection.ALZUBAIDI_START},
      new String[] {BEFORE_PRESSURE, ElasticNetSelection.KIKAS_START},
      new String[] {RETROSPECTIVE, BEFORE_PRESSURE},
      new String[] {WITH_DISCUSSION, RETROSPECTIVE},
      new String[] {WITH_DISCUSSION, KIKAS_RETROSPECTIVE},
      new String[] {RETROSPECTIVE, KIKAS_RETROSPECTIVE});

  /**
   * The comparisons of the side reading on the scored issues with an implementation commit: the
   * main reading of the retrospective model against the own start model it builds on, Elastic
   * Net and forest.
   */
  public static final List<String[]> WITH_COMMITS = List.of(
      new String[] {ElasticNetSelection.RETROSPECTIVE, ElasticNetSelection.BEFORE_PRESSURE},
      new String[] {forest(ElasticNetSelection.RETROSPECTIVE),
          forest(ElasticNetSelection.BEFORE_PRESSURE)});

  private static final Population P = Population.IN_PROGRESS;

  /**
   * Where the models are refitted and what is scored: the mode of the sides and the variant of
   * the split-dependent columns, always together.
   */
  public enum Scope {
    /**
     * The inner report (ticket 16): fitted on the inner fitting rows at the alpha and lambda the
     * selection chose there, the lambda as it is (the rows it was chosen on are the rows fitted,
     * so no ratio scales it), the inner variant read, the inner validation scored. The settings
     * were chosen on that same validation: every figure is optimistic.
     */
    INNER(Fitting.Mode.INNER, Augmentation.Variant.INNER, "inner fitting"),
    /**
     * The test: fitted on the outer training rows, the lambda times the ratio of rows ({@link
     * Fitting#finalLambda}), the outer variant read, the outer test scored once.
     */
    RESERVED(Fitting.Mode.RESERVED, Augmentation.Variant.OUTER, "outer training"),
    /**
     * The same-model calibration (the revised design's ticket 06), a sensitivity after the
     * outer test was opened that chooses nothing: the inner models, fitted as {@link #INNER}
     * fits them, the lambda as chosen, the inner variant read, predict the whole inner
     * validation and then the outer test, from one fit ({@link #sameModel}). The fences, the
     * references and the analogy estimate's fallback are the inner fitting's; no forest's
     * permutation importance, which would read the outer test's labels. Read through {@link
     * #sameModel} alone.
     */
    SAME_MODEL(Fitting.Mode.INNER_MODEL_ON_TEST, Augmentation.Variant.INNER, "inner fitting");

    private final Fitting.Mode mode;
    private final Augmentation.Variant variant;
    private final String fittingSet;

    Scope(final Fitting.Mode mode, final Augmentation.Variant variant, final String fittingSet) {
      this.mode = mode;
      this.variant = variant;
      this.fittingSet = fittingSet;
    }

    public Fitting.Mode mode() {
      return mode;
    }

    public Augmentation.Variant variant() {
      return variant;
    }

    /** The rows fitted on, in words, as a removal's reason and the diagnostics name them. */
    public String fittingSet() {
      return fittingSet;
    }

    /**
     * Whether the forests' permutation importance is read on the scored issues: in the inner
     * report only. On the outer test it would read the test's labels column by column.
     */
    public boolean importance() {
      return this == INNER;
    }

    /**
     * Whether the lambda is fitted as the selection chose it: on the inner fitting rows, those
     * it was chosen on. Only the outer training's refit scales it by the ratio of rows.
     */
    public boolean lambdaAsChosen() {
      return this != RESERVED;
    }
  }

  /**
   * The inner models' predictions of the inner validation and of the outer test, from one fit
   * (the revised design's ticket 06): what the same-model intervals read, so the factors and the
   * predictions they are applied to cannot come from two fits. Each part is a {@link Report} of
   * {@link Scope#SAME_MODEL}, with no comparison drawn; every model's {@code fitted} and {@code
   * forest} are the one fit, the same objects in the three, their own predictions of every
   * issue scored, validation and test together.
   *
   * @param calibration the usual-flow inner validation, at the inner fitting's fence: what {@link
   *     #calibration} reads, bit for bit
   * @param usualTest the outer test issues at or below the outer training's usual-flow fence, the
   *     issues of the test step's usual flow, so the two designs are read on the same issues
   * @param allTest every outer test issue
   */
  public record SameModel(Report calibration, Report usualTest, Report allTest) {}

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
   * The tail cut a report was fitted under, its fences taken on the fitting side of the scope (the
   * inner fitting set, or the outer training set), never on the scored side.
   *
   * @param k the fitting cut's k, one of {@link TailCut#GRID}
   * @param fence the fitting cut's fence, in business days; infinite for no cut
   * @param usualFence the usual flow's fence, at {@link TailCut#USUAL_FLOW_K}
   * @param uncutFitRows the fitting rows before the cut
   * @param usualRows the scored issues of the usual flow
   * @param allRows every scored issue
   */
  public record Fences(double k, double fence, double usualFence, int uncutFitRows,
      int usualRows, int allRows) {}

  /**
   * One standardised column of an Elastic Net model: its coefficient in the refit, whether the
   * estimator kept it, and the 95% interval of its coefficient from resampling whole projects of
   * the fitting side, the model refitted on each draw (a column constant on a draw's rows carries
   * nothing there, zero).
   */
  public record Coefficient(String column, double coefficient, boolean selected, double low,
      double high) {}

  /**
   * One Elastic Net model's relevance: per standardised column of its refit, in the design's
   * order, its {@link Coefficient}.
   *
   * @param fitRows the fitting rows of the refit, whose projects the draws resample
   * @param projects how many projects those rows hold
   */
  public record Relevance(String model, int fitRows, int projects,
      List<Coefficient> coefficients) {}

  /**
   * What a parameters file freezes beyond the fourteen models' choices, as the test reads it: the
   * analogy estimate's choice, and the count of trees every forest is refitted with.
   *
   * @param analogy the analogy estimate's frozen choice; null for none, the fourteen alone
   * @param trees the trees per forest, {@link Forest#TREES} when the file gives none
   */
  public record Design(AnalogyEstimate.Frozen analogy, int trees) {

    /** Refuses a count of trees below one. */
    public Design {
      if (trees < 1) {
        throw new IllegalArgumentException("a forest of " + trees + " trees");
      }
    }

    /** {@code analogy}, and the forests at {@link Forest#TREES}. */
    public static Design of(final AnalogyEstimate.Frozen analogy) {
      return new Design(analogy, Forest.TREES);
    }
  }

  /** The fourteen models, the forests at {@link Forest#TREES}: every file of the random split. */
  private static final Design FOURTEEN = Design.of(null);

  /** Both readings of one fit: the usual flow, the main one, and every scored issue. */
  public record Readings(Report main, Report complementary) {}

  /**
   * @param reading which scored issues it reads; every figure below is of those
   * @param fences the tail cut the models were fitted under
   * @param bandTops the limits of the error bands: the first quartile, the median and the third
   *     quartile of the observed business days of the cut fitting rows ({@link
   *     BusinessDays#bands}, ticket 24)
   * @param fitRows the fitting rows at or below the cut's fence, every model's and reference's
   * @param innerFitRows the rows the given lambdas were chosen on, those at or below the inner
   *     fitting set's own fence at the same k, which the final lambda scales by in the reserved
   *     scope
   * @param unseenProjects scored projects with no fitting row, where the project median is the
   *     global one
   * @param withCommitRows the scored issues with at least one implementation commit
   * @param withCommits the side reading: the pairs of {@link #WITH_COMMITS} on those issues only
   * @param implementationCommit per scored issue, whether it has an implementation commit
   * @param withoutCommits the same pairs on the scored issues without one, for contrast
   * @param orderings the ordering intervals (ticket 16, a declared measure) of {@link
   *     #orderingPairs}
   * @param withoutNewProjects whether the issues of new projects were left out of the scored ones
   *     ({@link #withoutNewProjects})
   * @param newProjects the new projects: those with issues on the scored side and none on the
   *     fitting side before the tail cut, in order of name
   * @param newProjectRows the issues of the reading that belong to a new project, counted before
   *     any is left out
   * @param analogyFallbacks the scored issues the analogy estimate gave its fallback; 0 without it
   */
  public record Report(Scope scope, TailCut.Reading reading, Fences fences, double[] bandTops,
      int fitRows, int scoredRows, int innerFitRows,
      List<IssueUnit> scoredUnits, double[] observed, List<Scored> models,
      List<ProjectBootstrap.Gap> comparisons, Set<String> unseenProjects, int unseenRows,
      int withCommitRows, List<ProjectBootstrap.Gap> withCommits, boolean[] implementationCommit,
      List<ProjectBootstrap.Gap> withoutCommits, List<ProjectBootstrap.Ordering> orderings,
      boolean withoutNewProjects, Set<String> newProjects, int newProjectRows,
      int analogyFallbacks) {}

  private SimilarityScoring() {}

  /** The name of the forest beside an Elastic Net model. */
  public static String forest(final String model) {
    return model + "_forest";
  }

  /**
   * Refits on the fitting rows at or below the fence of {@code k} and scores both readings, the
   * usual flow and every scored issue, each with its own figures (ticket 23), on the threads
   * {@link Parallel} asks for.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope) {
    return readings(entries, choices, forests, k, scope,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * {@link #readings(List, Map, Map, double, Scope)} on the threads given. Each reading refits:
   * the fits are the same bits, being deterministic on the same rows, and every figure of a
   * reading, the forests' permutation importance included, is read on its own issues.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final int fitThreads, final int drawThreads) {
    return readings(entries, choices, forests, k, scope, fitThreads, drawThreads, P);
  }

  /**
   * {@link #readings(List, Map, Map, double, Scope)} on the split and the attribute sets of
   * {@code population}, on the threads {@link Parallel} asks for.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final Population population) {
    return readings(entries, choices, forests, k, scope,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS), population);
  }

  /**
   * {@link #readings(List, Map, Map, double, Scope, int, int)} on the split and the attribute
   * sets of {@code population}: its split's verdicts give the sides, its sets the models.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final int fitThreads, final int drawThreads, final Population population) {
    return readings(entries, choices, forests, k, scope, fitThreads, drawThreads, population,
        FOURTEEN);
  }

  /**
   * {@link #readings(List, Map, Map, double, Scope, Population)} with what the parameters froze
   * beyond the fourteen's choices, on the threads {@link Parallel} asks for.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final Population population, final Design design) {
    return readings(entries, choices, forests, k, scope,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS), population, design);
  }

  /**
   * {@link #readings(List, Map, Map, double, Scope, int, int, Population)} with what the
   * parameters froze beyond the fourteen's choices ({@link Design}): the analogy estimate, if any,
   * beside the references, its column in the scope's variant; where an issue has none, the global
   * median learned on the same fitting rows as the declared reference; every forest at the given
   * count of trees. Nothing of it is chosen here.
   */
  public static Readings readings(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final int fitThreads, final int drawThreads, final Population population,
      final Design design) {
    return both(entries, choices, forests, k, scope, fitThreads, drawThreads, population, design,
        false);
  }

  /**
   * Both readings of {@link #readings(List, Map, Map, double, Scope, int, int, Population,
   * Design)} without the issues of new projects, the projects with issues on the
   * scored side and none on the fitting side: the same fits, every figure read again on the other
   * issues alone, the intervals resampling their projects.
   */
  public static Readings withoutNewProjects(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final int fitThreads, final int drawThreads, final Population population,
      final Design design) {
    return both(entries, choices, forests, k, scope, fitThreads, drawThreads, population, design,
        true);
  }

  /** The usual flow and every scored issue, the issues of new projects left out or not. */
  private static Readings both(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final int fitThreads, final int drawThreads, final Population population,
      final Design design, final boolean withoutNew) {
    return new Readings(
        score(entries, choices, forests, k, scope, TailCut.Reading.USUAL_FLOW, true, fitThreads,
            drawThreads, population, design, withoutNew),
        score(entries, choices, forests, k, scope, TailCut.Reading.ALL, true, fitThreads,
            drawThreads, population, design, withoutNew));
  }

  /**
   * {@link #withoutNewProjects(List, Map, Map, double, Scope, int, int, Population,
   * Design)} on the threads {@link Parallel} asks for.
   */
  public static Readings withoutNewProjects(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final Population population, final Design design) {
    return withoutNewProjects(entries, choices, forests, k, scope,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS), population, design);
  }

  /**
   * The inner calibration the outer test's prediction intervals read (ticket 24, item 53 viii):
   * every model refitted as the inner report fits it, on the inner fitting rows at or below the
   * fence of {@code k}, and its predictions of the usual-flow inner validation issues, the main
   * reading of the inner report; no comparison is drawn. Of the outer test nothing is read: the
   * inner scope fits and scores the outer training rows only.
   */
  public static Report calibration(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k) {
    return score(entries, choices, forests, k, Scope.INNER, TailCut.Reading.USUAL_FLOW, false,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS), P, FOURTEEN, false);
  }

  /** {@link #calibration(List, Map, Map, double)} on the split and sets of {@code population}. */
  public static Report calibration(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Population population) {
    return calibration(entries, choices, forests, k, population, FOURTEEN);
  }

  /**
   * {@link #calibration(List, Map, Map, double, Population)} with what the parameters froze
   * beyond the fourteen's choices ({@link Design}): the analogy estimate, if any, read in its inner
   * column with the inner fitting rows' global median as its fallback, and the forests at the
   * given count of trees.
   */
  public static Report calibration(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Population population,
      final Design design) {
    return score(entries, choices, forests, k, Scope.INNER, TailCut.Reading.USUAL_FLOW, false,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS), population, design, false);
  }

  /**
   * Refits, with no tail cut, and scores every scored issue: the scoring before ticket 23.
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
      final Map<String, Forest.Choice> forests, final Scope scope) {
    return run(entries, choices, forests, scope, Parallel.threads(Parallel.ELASTIC_NET_THREADS),
        Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * Refits and scores, the models refitted each alone on up to {@code fitThreads} threads and the
   * draws of every interval evaluated each alone on up to {@code drawThreads}, all read back in
   * their order, so the report is the same bits whatever the numbers.
   *
   * @param scope where the models are refitted and what is scored: {@link Scope#RESERVED} for
   *     the test, {@link Scope#INNER} for the inner report; nothing else of the scoring differs
   */
  public static Report run(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final Scope scope, final int fitThreads,
      final int drawThreads) {
    return score(entries, choices, forests, TailCut.NO_CUT, scope, TailCut.Reading.ALL, true,
        fitThreads, drawThreads, P, FOURTEEN, false);
  }

  /**
   * Refits on the fitting rows at or below the fence of {@code k}, both fences taken on the
   * scope's fitting side before the cut, and scores the reading's issues.
   *
   * @param compared whether the paired comparisons, the ordering intervals and the side reading
   *     are drawn; without them their lists are empty
   * @param design the analogy estimate's frozen choice, if any, and the forests' tree count
   * @param withoutNew whether the issues of new projects are left out of the scored ones
   */
  private static Report score(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final TailCut.Reading reading, final boolean compared, final int fitThreads,
      final int drawThreads, final Population population, final Design design,
      final boolean withoutNew) {
    if (scope == Scope.SAME_MODEL) {
      throw new IllegalArgumentException("the same-model scope scores the inner validation and "
          + "the outer test from one fit, read through sameModel alone");
    }
    final Prepared p = prepared(entries, choices, forests, k, scope, population);
    final Fitting.Sides read = reading == TailCut.Reading.ALL ? p.cut()
        : TailCut.usualFlow(p.cut(), p.usualFence());
    final int newProjectRows = (int) read.scoreUnits().stream()
        .filter(u -> p.newProjects().contains(u.project())).count();
    final Fitting.Sides sides = withoutNew ? seenOnly(read, p.trained()) : read;
    final Fences fences = new Fences(k, p.fence(), p.usualFence(), p.whole().fitUnits().size(),
        TailCut.usualFlow(p.cut(), p.usualFence()).scoreUnits().size(),
        p.cut().scoreUnits().size());
    final Fit fit = fit(p, sides, choices, forests, scope, design, fitThreads);
    return report(scope, reading, fences, p.innerFitRows(), sides, fit, compared, drawThreads,
        withoutNew, p.newProjects(), newProjectRows);
  }

  /**
   * What every reading of one scope shares, before any model is fitted: the cells, the sides, the
   * fences of the scope's fitting side, the cut sides, the projects of the fitting side before
   * the cut, the new projects, and the rows the lambdas were chosen on.
   *
   * @param trained the projects with a fitting row before the tail cut
   * @param newProjects the projects with scored issues and none in {@code trained}, by name
   */
  private record Prepared(List<Cell> cells, Fitting.Sides whole, double fence, double usualFence,
      Fitting.Sides cut, Set<String> trained, Set<String> newProjects, int innerFitRows) {}

  private static Prepared prepared(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Scope scope,
      final Population population) {
    if (!new LinkedHashSet<>(ElasticNetSelection.MODELS).equals(choices.keySet())) {
      throw new IllegalArgumentException("the parameters hold alpha and lambda for "
          + choices.keySet() + "; the models are " + ElasticNetSelection.MODELS);
    }
    if (!new LinkedHashSet<>(ElasticNetSelection.MODELS).equals(forests.keySet())) {
      throw new IllegalArgumentException("the parameters hold a forest for " + forests.keySet()
          + "; the models are " + ElasticNetSelection.MODELS);
    }
    BusinessDays.counted(entries);
    final List<Cell> cells = ElasticNetSelection.cells(ElasticNetSelection.combinations(choices),
        scope.variant(), population);
    final Fitting.Sides whole = ElasticNetSelection.flagged(
        Fitting.sides(entries, scope.mode(), population), cells);
    final double fence = TailCut.fence(whole.fitUnits(), k);
    final double usualFence = TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K);
    final Fitting.Sides cut = TailCut.cut(whole, fence);
    // the new projects: issues on the scored side, none on the fitting side before the cut
    final Set<String> trained = new HashSet<>();
    whole.fitUnits().forEach(u -> trained.add(u.project()));
    final Set<String> newProjects = new TreeSet<>();
    whole.scoreUnits().stream().map(IssueUnit::project).filter(p -> !trained.contains(p))
        .forEach(newProjects::add);
    // the rows the lambdas were chosen on: the inner fitting set cut at its own fence of this k
    return new Prepared(cells, whole, fence, usualFence, cut, trained,
        java.util.Collections.unmodifiableSet(newProjects), innerFitRows(entries, k, population));
  }

  /**
   * Every model fitted once on the cut fitting rows of {@code sides}, and its predictions of
   * {@code sides}' scored issues.
   *
   * @param tops the error bands' limits, of the cut fitting rows only (ticket 24)
   * @param fellBack per scored issue, whether the analogy estimate gave it its fallback; all
   *     false without it
   */
  private record Fit(double[] tops, List<Scored> models, int analogyFallbacks,
      boolean[] fellBack) {}

  private static Fit fit(final Prepared p, final Fitting.Sides sides,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final Scope scope, final Design design,
      final int fitThreads) {
    final AnalogyEstimate.Frozen analogy = design.analogy();
    final int fitRows = sides.fitUnits().size();
    final double[] observed = sides.observed();
    final double[] tops = TailCut.quartiles(
        sides.fitUnits().stream().mapToDouble(IssueUnit::duration).toArray());

    final References refs = References.learnedOn(sides.fitUnits());
    final double[] globalMedian = refs.constant(observed.length);
    final double[] projectMedian = refs.projectMedian(sides.scoreUnits());
    final double constantMae = Fitting.mae(globalMedian, observed);
    final double projectMae = Fitting.mae(projectMedian, observed);

    final List<Scored> models = new ArrayList<>();
    models.add(given(GLOBAL, globalMedian, observed, projectMae, constantMae, tops));
    models.add(given(PROJECT, projectMedian, observed, projectMae, constantMae, tops));
    int analogyFallbacks = 0;
    boolean[] fellBack = new boolean[observed.length];
    if (analogy != null) {
      // the fallback is the declared global median, learned on the same fitting rows
      final AnalogyEstimate.Estimate e = AnalogyEstimate.estimate(sides.scoreRows(),
          analogy.column() + scope.variant().suffix(), refs.constant(1)[0]);
      analogyFallbacks = e.fallbacks();
      fellBack = e.fellBack();
      models.add(new Scored(ANALOGY, "analogy", e.values(),
          Score.of(ANALOGY, e.values(), observed, projectMae, constantMae),
          Breakdown.errors(e.values(), observed), BusinessDays.bands(e.values(), observed, tops),
          null, List.of(), null));
    }

    final List<Callable<Scored>> refits = new ArrayList<>(p.cells().size());
    for (final Cell cell : p.cells()) {
      refits.add(() -> {
        final ElasticNetSelection.Choice c = choices.get(cell.label());
        final ElasticNetSelection.Kept kept =
            ElasticNetSelection.kept(cell, sides.fitRows(), scope.fittingSet());
        final double lambda = scope.lambdaAsChosen() ? c.lambda()
            : Fitting.finalLambda(c.lambda(), fitRows, p.innerFitRows());
        final Fitting.Fitted f = Fitting.frozen(kept.cell(), sides, c.alpha(), lambda, projectMae,
            constantMae);
        return new Scored(cell.label(), "elastic-net", f.predicted(), f.score(),
            Breakdown.errors(f.predicted(), observed),
            BusinessDays.bands(f.predicted(), observed, tops), f, kept.removed(), null);
      });
    }
    models.addAll(Parallel.map(fitThreads, refits));

    final List<Callable<Scored>> trees = new ArrayList<>(p.cells().size());
    for (final Cell cell : p.cells()) {
      trees.add(() -> {
        final Forest.Choice c = forests.get(cell.label());
        final ElasticNetSelection.Kept kept =
            ElasticNetSelection.kept(cell, sides.fitRows(), scope.fittingSet());
        final String name = forest(cell.label());
        final Forest.Frozen f = Forest.frozen(new Cell(name, cell.family(), cell.scenario(),
            kept.cell().attributes()), sides, c, design.trees(), projectMae, constantMae,
            scope.importance());
        return new Scored(name, "forest", f.predicted(), f.score(),
            Breakdown.errors(f.predicted(), observed),
            BusinessDays.bands(f.predicted(), observed, tops), null, kept.removed(), f);
      });
    }
    models.addAll(Parallel.map(fitThreads, trees));
    return new Fit(tops, List.copyOf(models), analogyFallbacks, fellBack);
  }

  /**
   * The report of {@code fit}'s models on {@code sides}' scored issues, one prediction of each per
   * issue: the comparisons, ordering intervals and side reading when {@code compared}, the
   * projects with no fitting row.
   */
  private static Report report(final Scope scope, final TailCut.Reading reading,
      final Fences fences, final int innerFitRows, final Fitting.Sides sides, final Fit fit,
      final boolean compared, final int drawThreads, final boolean withoutNew,
      final Set<String> newProjects, final int newProjectRows) {
    final List<Scored> models = fit.models();
    final double[] observed = sides.observed();
    final List<String> projects = sides.projects();
    final Map<String, double[]> errors = new HashMap<>();
    for (final Scored m : models) {
      errors.put(m.name(), Bootstrap.errors(m.predicted(), observed));
    }
    final List<String> names = models.stream().map(Scored::name).toList();
    final List<ProjectBootstrap.Gap> comparisons = new ArrayList<>();
    for (final String[] pair : compared ? pairs(names) : List.<String[]>of()) {
      comparisons.add(ProjectBootstrap.compare(projects, pair[0], errors.get(pair[0]), pair[1],
          errors.get(pair[1]), drawThreads));
    }
    final Map<String, double[]> predicted = new HashMap<>();
    models.forEach(m -> predicted.put(m.name(), m.predicted()));
    final List<ProjectBootstrap.Ordering> orderings = new ArrayList<>();
    for (final String[] pair : compared ? orderingPairs(names) : List.<String[]>of()) {
      orderings.add(ProjectBootstrap.ordering(projects, pair[0], predicted.get(pair[0]), pair[1],
          predicted.get(pair[1]), observed, drawThreads));
    }
    final boolean[] commit = new boolean[observed.length];
    final List<Integer> withCommit = new ArrayList<>();
    final List<Integer> withoutCommit = new ArrayList<>();
    for (int i = 0; i < sides.scoreRows().size(); i++) {
      commit[i] = sides.scoreRows().get(i).values().containsKey(Retrospective.LINES);
      (commit[i] ? withCommit : withoutCommit).add(i);
    }
    final List<ProjectBootstrap.Gap> side = compared
        ? side(withCommit, projects, errors, drawThreads) : List.of();
    final List<ProjectBootstrap.Gap> contrast = compared
        ? side(withoutCommit, projects, errors, drawThreads) : List.of();

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
    return new Report(scope, reading, fences, fit.tops(), sides.fitUnits().size(),
        observed.length, innerFitRows, List.copyOf(sides.scoreUnits()), observed,
        List.copyOf(models), List.copyOf(comparisons), unseen, unseenRows, withCommit.size(),
        side, commit, contrast, List.copyOf(orderings), withoutNew, newProjects, newProjectRows,
        fit.analogyFallbacks());
  }

  /**
   * The same-model calibration (the revised design's ticket 06), on the threads {@link Parallel}
   * asks for the draws: see {@link #sameModel(List, Map, Map, double, Population, Design,
   * boolean, int)}.
   */
  public static SameModel sameModel(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Population population,
      final Design design, final boolean withoutNew) {
    return sameModel(entries, choices, forests, k, population, design, withoutNew,
        Parallel.threads(Parallel.ELASTIC_NET_THREADS));
  }

  /**
   * The same-model calibration (the revised design's ticket 06), a sensitivity after the outer
   * test was opened that chooses nothing: every model fitted once, as {@link #calibration} fits
   * it (the inner fitting rows that teach, at or below the inner fitting's fence of {@code k},
   * the lambda as chosen, the inner variant), predicting the whole inner validation and the
   * outer test, {@link Scope#SAME_MODEL}. Three readings of that one fit: the usual-flow inner
   * validation, at the inner fitting's usual-flow fence, the calibration; the outer test issues at
   * or below the outer training's usual-flow fence, the test step's usual flow; every outer test
   * issue. No label of the inner validation or of the outer test enters the fit.
   *
   * @param withoutNew whether the outer test is read without the issues of new projects, those
   *     with no inner fitting row before the tail cut: the projects the inner model never saw;
   *     the calibration is the whole usual-flow inner validation either way
   */
  public static SameModel sameModel(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices,
      final Map<String, Forest.Choice> forests, final double k, final Population population,
      final Design design, final boolean withoutNew, final int fitThreads) {
    final Prepared p = prepared(entries, choices, forests, k, Scope.SAME_MODEL, population);
    // the test step's usual flow: the outer training's fence, so both designs read the same issues
    final double outerUsualFence = TailCut.fence(
        Fitting.sides(entries, Fitting.Mode.RESERVED, population).fitUnits(),
        TailCut.USUAL_FLOW_K);
    final Set<String> test = new HashSet<>();
    for (final RowCache.Entry e : entries) {
      if (Verdict.scored(Augmentation.Variant.OUTER, e, population)) {
        test.add(e.unit().issueId());
      }
    }
    final Fit fit = fit(p, p.cut(), choices, forests, Scope.SAME_MODEL, design, fitThreads);
    final List<IssueUnit> scored = p.cut().scoreUnits();
    final double[] observed = p.cut().observed();
    final List<Integer> validation = new ArrayList<>();
    final List<Integer> usualValidation = new ArrayList<>();
    final List<Integer> allTest = new ArrayList<>();
    final List<Integer> usualTest = new ArrayList<>();
    for (int i = 0; i < scored.size(); i++) {
      if (!test.contains(scored.get(i).issueId())) {
        validation.add(i);
        if (observed[i] <= p.usualFence()) {
          usualValidation.add(i);
        }
      } else {
        allTest.add(i);
        if (observed[i] <= outerUsualFence) {
          usualTest.add(i);
        }
      }
    }
    final int fitted = p.whole().fitUnits().size();
    final Report calibration = slice(p, fit, usualValidation, TailCut.Reading.USUAL_FLOW,
        new Fences(k, p.fence(), p.usualFence(), fitted, usualValidation.size(),
            validation.size()), validation, false);
    final Fences testFences = new Fences(k, p.fence(), outerUsualFence, fitted, usualTest.size(),
        allTest.size());
    return new SameModel(calibration,
        slice(p, fit, usualTest, TailCut.Reading.USUAL_FLOW, testFences, allTest, withoutNew),
        slice(p, fit, allTest, TailCut.Reading.ALL, testFences, allTest, withoutNew));
  }

  /**
   * One reading of the same-model fit: its scored issues {@code rows}, without those of new
   * projects when {@code withoutNew}, every model's predictions of them as the fit gave them, every
   * figure read on them alone; no comparison is drawn.
   *
   * @param part the scored issues of the part the reading is of, the inner validation or the
   *     outer test, whose projects with no fitting row are its new projects
   */
  private static Report slice(final Prepared p, final Fit fit, final List<Integer> rows,
      final TailCut.Reading reading, final Fences fences, final List<Integer> part,
      final boolean withoutNew) {
    final List<IssueUnit> scored = p.cut().scoreUnits();
    final Set<String> newProjects = new TreeSet<>();
    part.stream().map(i -> scored.get(i).project()).filter(x -> !p.trained().contains(x))
        .forEach(newProjects::add);
    final int newProjectRows = (int) rows.stream()
        .filter(i -> newProjects.contains(scored.get(i).project())).count();
    final List<Integer> kept = withoutNew
        ? rows.stream().filter(i -> !newProjects.contains(scored.get(i).project())).toList()
        : rows;
    final List<IssueUnit> units = new ArrayList<>(kept.size());
    final List<app.study.Row> scoreRows = new ArrayList<>(kept.size());
    for (final int i : kept) {
      units.add(scored.get(i));
      scoreRows.add(p.cut().scoreRows().get(i));
    }
    final double[] observed = units.stream().mapToDouble(IssueUnit::duration).toArray();
    final Fitting.Sides sides = new Fitting.Sides(p.cut().fitUnits(), p.cut().fitRows(),
        List.copyOf(units), List.copyOf(scoreRows), observed);
    // the references are the fit's first two models, the global median, then the project's
    final double constantMae = Fitting.mae(rows(fit.models().get(0).predicted(), kept),
        observed);
    final double projectMae = Fitting.mae(rows(fit.models().get(1).predicted(), kept), observed);
    final List<Scored> models = new ArrayList<>(fit.models().size());
    for (final Scored m : fit.models()) {
      final double[] predicted = rows(m.predicted(), kept);
      models.add(new Scored(m.name(), m.kind(), predicted,
          Score.of(m.name(), predicted, observed, projectMae, constantMae),
          Breakdown.errors(predicted, observed), BusinessDays.bands(predicted, observed,
              fit.tops()), m.fitted(), m.removed(), m.forest()));
    }
    int fallbacks = 0;
    for (final int i : kept) {
      fallbacks += fit.fellBack()[i] ? 1 : 0;
    }
    return report(Scope.SAME_MODEL, reading, fences, p.innerFitRows(), sides,
        new Fit(fit.tops(), List.copyOf(models), fallbacks, null), false, 1, withoutNew,
        java.util.Collections.unmodifiableSet(newProjects), newProjectRows);
  }

  /**
   * The sides with only the scored issues of the projects {@code trained}; the fitting side as it
   * is.
   */
  private static Fitting.Sides seenOnly(final Fitting.Sides sides, final Set<String> trained) {
    final List<IssueUnit> units = new ArrayList<>();
    final List<app.study.Row> rows = new ArrayList<>();
    for (int i = 0; i < sides.scoreUnits().size(); i++) {
      if (trained.contains(sides.scoreUnits().get(i).project())) {
        units.add(sides.scoreUnits().get(i));
        rows.add(sides.scoreRows().get(i));
      }
    }
    final double[] observed = units.stream().mapToDouble(IssueUnit::duration).toArray();
    return new Fitting.Sides(sides.fitUnits(), sides.fitRows(), List.copyOf(units),
        List.copyOf(rows), observed);
  }

  /**
   * The relevance section ({@link Relevance}), on the threads {@link Parallel} asks for: per
   * Elastic Net model, in {@link ElasticNetSelection#MODELS}' order, each standardised column's
   * coefficient in the refit of {@link #readings} at {@code k} and its 95% interval from
   * resampling whole projects of the scope's fitting side.
   */
  public static List<Relevance> relevance(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices, final double k, final Scope scope) {
    return relevance(entries, choices, k, scope, Parallel.threads(Parallel.BOOTSTRAP_THREADS));
  }

  /**
   * {@link #relevance(List, Map, double, Scope)} with each model's draws evaluated on up to
   * {@code drawThreads} threads, the same bits on any number.
   *
   * <p>The refit is the one the scores read: the fitting rows at or below the fence of {@code k},
   * taken on the scope's fitting side, the declared columns missing on every one of them
   * removed, the alpha given and the lambda as the scores fit it. Each draw takes the projects of
   * the fitting rows with replacement, from the same {@link ProjectBootstrap#DRAWS} draws and seed
   * every interval reads, keeps every fitting row of each project drawn (a project drawn twice
   * counted twice), and refits the model on them as the refit was made: the design matrix
   * learned on the drawn rows -- the medians that fill a gap and the means and spreads that
   * standardise --, the same alpha, and the lambda times the drawn rows over the fitting rows,
   * since the estimator sums the squared error over rows, as the outer refit scales it. A column
   * constant on a draw's rows carries nothing there and counts as zero. Reads no scored issue.
   */
  public static List<Relevance> relevance(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices, final double k, final Scope scope,
      final int drawThreads) {
    return relevance(entries, choices, k, scope, drawThreads, P);
  }

  /** {@link #relevance(List, Map, double, Scope)} on the split and sets of {@code population}. */
  public static List<Relevance> relevance(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices, final double k, final Scope scope,
      final Population population) {
    return relevance(entries, choices, k, scope, Parallel.threads(Parallel.BOOTSTRAP_THREADS),
        population);
  }

  /**
   * {@link #relevance(List, Map, double, Scope, int)} on the split and the attribute sets of
   * {@code population}.
   */
  public static List<Relevance> relevance(final List<RowCache.Entry> entries,
      final Map<String, ElasticNetSelection.Choice> choices, final double k, final Scope scope,
      final int drawThreads, final Population population) {
    if (scope == Scope.SAME_MODEL) {
      throw new IllegalArgumentException("the same-model scope has no relevance of its own: its "
          + "fit is the inner report's");
    }
    if (!new LinkedHashSet<>(ElasticNetSelection.MODELS).equals(choices.keySet())) {
      throw new IllegalArgumentException("the parameters hold alpha and lambda for "
          + choices.keySet() + "; the models are " + ElasticNetSelection.MODELS);
    }
    BusinessDays.counted(entries);
    final List<Cell> cells = ElasticNetSelection.cells(ElasticNetSelection.combinations(choices),
        scope.variant(), population);
    final Fitting.Sides whole = ElasticNetSelection.flagged(
        Fitting.sides(entries, scope.mode(), population), cells);
    final Fitting.Sides cut = TailCut.cut(whole, TailCut.fence(whole.fitUnits(), k));
    final int fitRows = cut.fitUnits().size();
    final int innerFitRows = innerFitRows(entries, k, population);
    final List<String> projects = cut.fitUnits().stream().map(IssueUnit::project).toList();
    final int projectCount = new HashSet<>(projects).size();
    final List<Relevance> out = new ArrayList<>(cells.size());
    for (final Cell cell : cells) {
      final ElasticNetSelection.Choice c = choices.get(cell.label());
      final ElasticNetSelection.Kept kept =
          ElasticNetSelection.kept(cell, cut.fitRows(), scope.fittingSet());
      final double lambda = scope.lambdaAsChosen() ? c.lambda()
          : Fitting.finalLambda(c.lambda(), fitRows, innerFitRows);
      // the refit the scores read, on the fitting rows alone: the same coefficients bit for bit
      final Map<String, Double> point = Fitting.coefficients(kept.cell(), cut.fitRows(),
          cut.fitUnits(), c.alpha(), lambda);
      final List<String> columns = List.copyOf(point.keySet());
      final double[][] spread = ProjectBootstrap.intervals(projects, columns.size(), sample -> {
        final List<app.study.Row> rows = new ArrayList<>(sample.size());
        final List<IssueUnit> units = new ArrayList<>(sample.size());
        for (final int i : sample) {
          rows.add(cut.fitRows().get(i));
          units.add(cut.fitUnits().get(i));
        }
        final Map<String, Double> w = Fitting.coefficients(kept.cell(), rows, units, c.alpha(),
            lambda * sample.size() / fitRows);
        final double[] v = new double[columns.size()];
        for (int j = 0; j < v.length; j++) {
          v[j] = w.getOrDefault(columns.get(j), 0.0);
        }
        return v;
      }, drawThreads);
      // kept by the estimator as the scores' refit reads it: against the declared threshold
      double largest = 0;
      for (final double w : point.values()) {
        largest = Math.max(largest, Math.abs(w));
      }
      final List<Coefficient> coefficients = new ArrayList<>(columns.size());
      for (int j = 0; j < columns.size(); j++) {
        final double w = point.get(columns.get(j));
        coefficients.add(new Coefficient(columns.get(j), w, largest > 0
            && Math.abs(w) / largest >= app.model.ElasticNetModel.SELECTION_THRESHOLD,
            spread[j][0], spread[j][1]));
      }
      out.add(new Relevance(cell.label(), fitRows, projectCount, List.copyOf(coefficients)));
    }
    return List.copyOf(out);
  }

  /**
   * The rows the given lambdas were chosen on: the inner fitting set cut at its own fence of
   * {@code k}, which the final lambda scales by in the reserved scope.
   */
  private static int innerFitRows(final List<RowCache.Entry> entries, final double k,
      final Population population) {
    final List<IssueUnit> innerFit =
        Fitting.sides(entries, Fitting.Mode.INNER, population).fitUnits();
    final double innerFence = TailCut.fence(innerFit, k);
    return (int) innerFit.stream().filter(u -> u.duration() <= innerFence).count();
  }

  /** The pairs of {@link #WITH_COMMITS} on the given scored rows only, resampling their projects. */
  private static List<ProjectBootstrap.Gap> side(final List<Integer> rows,
      final List<String> projects, final Map<String, double[]> errors, final int drawThreads) {
    final List<ProjectBootstrap.Gap> out = new ArrayList<>();
    if (!rows.isEmpty()) {
      final List<String> sideProjects = rows.stream().map(projects::get).toList();
      for (final String[] pair : WITH_COMMITS) {
        out.add(ProjectBootstrap.compare(sideProjects, pair[0], rows(errors.get(pair[0]), rows),
            pair[1], rows(errors.get(pair[1]), rows), drawThreads));
      }
    }
    return List.copyOf(out);
  }

  /**
   * The pairs whose ordering is compared (ticket 16): every pair of {@link #pairs} but those
   * against the global median, which predicts one number for every issue and orders nothing.
   */
  public static List<String[]> orderingPairs(final List<String> models) {
    return pairs(models).stream().filter(p -> !p[1].equals(GLOBAL)).toList();
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
   * and Elastic Net models: the pairs of {@link #AGREED}; then each of them against the global
   * median, and each but the global median against the project median, so the two references are
   * compared once. Then, of the forests, in the order of {@code models}: the forests of the same
   * agreed pairs, in the same order; each forest against the global median; each forest against
   * the project median. Last, with the analogy estimate among {@code models}, it against each of
   * {@link #ANALOGY_AGAINST}; against the two references it is drawn above, as every model is. A
   * pair is drawn only when both its models are among {@code models}.
   */
  public static List<String[]> pairs(final List<String> models) {
    final List<String> forests = new ArrayList<>();
    for (final String m : ElasticNetSelection.MODELS) {
      forests.add(forest(m));
    }
    final List<String> linear = models.stream().filter(m -> !forests.contains(m)).toList();
    final List<String> trees = models.stream().filter(forests::contains).toList();
    final List<String[]> out = new ArrayList<>();
    for (final String[] pair : AGREED) {
      if (linear.containsAll(List.of(pair))) {
        out.add(pair);
      }
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
    for (final String[] pair : AGREED) {
      if (trees.containsAll(List.of(forest(pair[0]), forest(pair[1])))) {
        out.add(new String[] {forest(pair[0]), forest(pair[1])});
      }
    }
    for (final String f : trees) {
      out.add(new String[] {f, GLOBAL});
    }
    for (final String f : trees) {
      out.add(new String[] {f, PROJECT});
    }
    if (models.contains(ANALOGY)) {
      for (final String b : ANALOGY_AGAINST) {
        if (models.contains(b)) {
          out.add(new String[] {ANALOGY, b});
        }
      }
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

  private static Scored given(final String name, final double[] predicted,
      final double[] observed, final double projectMae, final double constantMae,
      final double[] tops) {
    return new Scored(name, "reference", predicted,
        Score.of(name, predicted, observed, projectMae, constantMae),
        Breakdown.errors(predicted, observed), BusinessDays.bands(predicted, observed, tops), null,
        List.of(), null);
  }
}
