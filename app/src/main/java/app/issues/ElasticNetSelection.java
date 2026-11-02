package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.model.References;
import app.model.Parallel;
import app.study.Construct;
import app.study.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The study's Elastic Net models, and the choice on the inner validation only of each one's alpha
 * and lambda by its declared sweep, and, for the three models reading the similarity medians, of
 * the Jaccard n, embedding n and cluster k
 * they read.
 *
 * <p><b>Models.</b> Six attribute sets, each a {@link Cell}, each with a random forest beside it
 * on the same grid: the study measures each set's attributes, not its source's method. At the
 * start, three. The own start model ({@link #BEFORE_PRESSURE}), the study's own contribution:
 * description length and team size, the extracted {@code A01} and {@code A03}; the team's
 * median and spread of lines of code and of cyclomatic complexity; the corrected reporter median;
 * the executor's lines of code, cyclomatic complexity and open workload, the extracted {@code
 * A09}, {@code A10} and {@code A13}; and the Jaccard, embedding and cluster medians, thirteen in
 * all. Al-Zubaidi at the start and Kikas at the start, the literature sets read as
 * they are ({@code ALZUBAIDI / C1}, {@code KIKAS / C1}). After the resolution, three. The own
 * retrospective model in its main reading ({@link #RETROSPECTIVE}): the own start model followed
 * by what was observed between the first In Progress and the first resolution ({@link
 * Retrospective}) except comments, participants and release lag: the five content attributes
 * and assignee changes, nineteen in all. The release-lag column stays in the matrix for audit.
 * The same with the comments and the participants ({@link #WITH_DISCUSSION}), twenty-one in all,
 * a ceiling, not a competitor: those two count the
 * discussion observed during the work ({@link Retrospective#DISCUSSION}). Kikas's retrospective
 * model, Kikas's retrospective literature set ({@code KIKAS / C2}): Kikas at
 * the start followed by Kikas's whole retrospective set as adapted, the twelve counts observed up
 * to the resolution and the missing indicator the matrix has for them; no similarity median, no
 * own column. The wait on blocking issues is in no model: it equalled the target in about half
 * of the issues that had one. Neither is the release-cycle pressure of the start (the fix
 * version, the release cycle and the Blocker pace): the own start model is
 * the own specification without them, as the inner validation decided.
 *
 * <p>The retrospective models read what was observed up to the resolution, and their counts grow
 * with the duration itself: a gain over the start is association with the duration, not a
 * prediction available during the work. The retrospective columns are new names, beside the
 * extracted columns of the same constructs, which keep their meaning.
 *
 * <p>Left out, their decisions kept by the earlier runs' reports: the own specification without
 * the similarity medians, the medians alone, the own specification with the pressure attributes
 * and the union of the families, at the start.
 *
 * <p><b>Similarity medians as attributes</b> (ticket 11, reversing ticket 08's choice by each
 * median's error read alone as a prediction). The three models with the medians ({@link
 * #WITH_SIMILARITY}) are swept once per {@link Combination} of a Jaccard n, an embedding n and a
 * cluster k among the values the similarity stage computes, 27 in all, each sweep the declared
 * alpha by lambda grid, and keep the combination whose chosen point errs least on the inner
 * validation. <b>Ties</b>: within a combination the sweep's own rule holds (a point is kept only
 * when its error is strictly lower, so the earlier point of the grid wins); across combinations
 * the same rule is read in the order of {@link Combination#all}, so of combinations whose errors
 * are exactly equal the one with the smaller Jaccard n wins, then the smaller embedding n, then
 * the smaller k. No tolerance: the sweep has none. The three models without the medians are swept
 * once.
 *
 * <p><b>Columns.</b> The reporter median and the three similarity medians depend
 * on the split, each reading the durations of its training side only (ticket 21); each model
 * reads them in the variant of its mode, {@code _inner} here. The three similarity
 * medians enter as {@code log1p} before standardisation, as the spec says; the reporter median
 * takes the transform of the registry's column it revises ({@code D05}), and the four team columns
 * that of the registry's volumes, both {@code log1p}. Every new column carries a missing
 * indicator: a missing value is handed to the design preparation as missing, filled there by
 * the fitting set's median. The retrospective columns are declared as {@link
 * #retrospective} says: the three counts of the discussion are never missing and carry none. The
 * extracted columns ({@code A01}, {@code A03}, {@code A09}, {@code A10}, {@code A13}) keep their
 * own transform and missing-indicator setting. A declared
 * column missing on every inner fitting row is removed from the model before the sweep, with the
 * reason recorded, never filled from the validation rows. The design preparation, sweep and grid
 * are reused as they are.
 *
 * <p><b>Random forest</b> (ticket 13, a robustness check, not a new contribution). Beside each
 * model, once its Elastic Net is chosen, the declared {@link Forest} grid is swept on the same
 * inner fitting and validation rows, with the same columns -- the similarity medians at the
 * combination the Elastic Net chose, and every declared column not removed for being missing,
 * whether or not the Elastic Net gave it a weight -- the same design matrix fitted on the fitting
 * side, the same {@code log1p} target and back-transform; n and k are not swept again. The point with the lowest inner
 * validation error is kept; of points whose errors are exactly equal, the first in the grid's
 * order (the smaller column share, then the smaller leaf; {@link Forest#sweep}). Its column share,
 * the count that share gave and its leaf go to the parameters file under the step {@link
 * #FOREST_STEP}.
 *
 * <p><b>Tail cut</b> (ticket 23, item 50). Before anything else, the k of the fitting cut is
 * chosen on the own start model alone ({@link #run(List, int)}); every model,
 * forest and reference is then fitted on the inner fitting rows at or below that k's fence
 * ({@link TailCut}). The similarity medians and the reporter median are columns
 * of the matrix, written from every training-side issue, and are read as they are. Every choice,
 * the k included, is scored on the usual-flow validation issues only.
 *
 * <p><b>Business days</b> (ticket 14). The target is whole business days ({@link BusinessDays}),
 * the outcome each unit carries, and the rows are the issues of at least one: the entries are
 * {@link BusinessDays#population}'s. Every error the selection reads and keeps is in business
 * days; the shared names of the fitting code are unit-neutral (ticket 17). The procedures, grids
 * and tie rules are those chosen for the hours, unchanged. A parameters file written for the hours has another
 * {@link #HEADER} and is never read or overwritten.
 */
public final class ElasticNetSelection {

  /**
   * The own start model, the own specification with similarity without the pressure attributes,
   * the first of {@link #MODELS}: the start model the SPSP would use, which both readings of the
   * retrospective model build on and the tail cut's k is chosen on alone, then reaching every
   * model.
   */
  public static final String BEFORE_PRESSURE = "own_with_similarity_before_pressure";

  /** Al-Zubaidi at the start, the literature set {@code ALZUBAIDI / C1}. */
  public static final String ALZUBAIDI_START = "alzubaidi_start";

  /** Kikas at the start, the literature set {@code KIKAS / C1}. */
  public static final String KIKAS_START = "kikas_start";

  /**
   * The retrospective scenario's model in its main reading, the fourth of {@link #MODELS}: the
   * own start model followed by the five content attributes and assignee changes, without
   * comments, participants or release lag.
   */
  public static final String RETROSPECTIVE = "own_retrospective";

  /** What the main reading is, for the selection step's output and record and both reports. */
  public static final String RETROSPECTIVE_ROLE = "the main reading of the retrospective model: "
      + "the own start model followed by the five content attributes (Java lines, files, mean "
      + "complexity, mean nesting, familiarity) and assignee changes, with their "
      + "missing indicators; no comments, participants or release lag, preserving the declared "
      + "attribute specification";

  /**
   * The retrospective model with the comments and the participants, the fifth of {@link
   * #MODELS}: {@link #RETROSPECTIVE} followed by those two and nothing else.
   */
  public static final String WITH_DISCUSSION = "own_retrospective_with_discussion";

  /** What the reading with the discussion is, for the selection step's output and record and both reports. */
  public static final String WITH_DISCUSSION_ROLE = "a CEILING, not a competitor: the main "
      + "reading of the retrospective model followed by the comments and the participants, which "
      + "accumulate during the work and are unavailable at the start; it "
      + "compares fairly with Kikas's retrospective set, which counts comments too";

  /**
   * Kikas's retrospective model, the last of {@link #MODELS}: Kikas's literature set for the
   * retrospective scenario, the literature counterpart of {@link #RETROSPECTIVE}.
   */
  public static final String KIKAS_RETROSPECTIVE = "kikas_retrospective";

  /** What Kikas's retrospective model is, for the selection step's output and record and both reports. */
  public static final String KIKAS_RETROSPECTIVE_ROLE = "Kikas at the start followed by Kikas's "
      + "retrospective set as adapted, the twelve counts observed up to the resolution and their "
      + "missing indicator (KIKAS / C2), the literature counterpart of the retrospective model; "
      + "it counts comments, which the main reading of the own retrospective model does not";

  /**
   * The six attribute sets, in their order: the three of the start, the own one first, then the
   * three after the resolution, the main reading, the ceiling and Kikas's. Each has its forest,
   * and the global and project medians stand beside them: fourteen models in the inner report
   * and the outer test alike.
   */
  public static final List<String> MODELS = List.of(BEFORE_PRESSURE, ALZUBAIDI_START,
      KIKAS_START, RETROSPECTIVE, WITH_DISCUSSION, KIKAS_RETROSPECTIVE);

  /** The models that read the similarity medians, whose n, n and k are chosen with them. */
  public static final List<String> WITH_SIMILARITY = List.of(BEFORE_PRESSURE, RETROSPECTIVE,
      WITH_DISCUSSION);

  /** Where the tail cut's k is chosen, in words, for the help, the run records and the reports. */
  public static final String TAIL_CUT_MODEL = "the k is chosen on the own start model ("
      + BEFORE_PRESSURE + ") alone, the start model the SPSP would use, and then reaches every "
      + "model, forest and reference";

  private static final Population P = Population.IN_PROGRESS;

  private static final String TRAIN = "TRAIN";
  private static final String TEST = "TEST";

  /** The suffix of a missing indicator, as the extracted matrix names its own. */
  private static final String ABSENT = "_ausente";

  /**
   * The values a model reads the three similarity medians at.
   *
   * @param jaccardN the Jaccard neighbours, one of {@link Augmentation#JACCARD_N}
   * @param embeddingN the embedding neighbours, one of {@link Augmentation#EMBEDDING_N}
   * @param clusterK the K-means clusters, one of {@link Augmentation#CLUSTER_K}
   */
  public record Combination(int jaccardN, int embeddingN, int clusterK) {

    /** Refuses a value the similarity stage does not compute. */
    public Combination {
      if (!Augmentation.JACCARD_N.contains(jaccardN)
          || !Augmentation.EMBEDDING_N.contains(embeddingN)
          || !Augmentation.CLUSTER_K.contains(clusterK)) {
        throw new IllegalArgumentException("Jaccard n " + jaccardN + ", embedding n "
            + embeddingN + " and cluster k " + clusterK + " are not all among the computed "
            + Augmentation.JACCARD_N + ", " + Augmentation.EMBEDDING_N + " and "
            + Augmentation.CLUSTER_K);
      }
    }

    /**
     * Every combination of the computed values, in the order ties are broken: Jaccard n
     * ascending, then embedding n, then cluster k, the last varying fastest.
     */
    public static List<Combination> all() {
      final List<Combination> out = new ArrayList<>();
      for (final int j : ascending(Augmentation.JACCARD_N)) {
        for (final int e : ascending(Augmentation.EMBEDDING_N)) {
          for (final int k : ascending(Augmentation.CLUSTER_K)) {
            out.add(new Combination(j, e, k));
          }
        }
      }
      return List.copyOf(out);
    }

    private static List<Integer> ascending(final List<Integer> values) {
      return values.stream().sorted().toList();
    }
  }

  /**
   * A column a model was declared with and does not read.
   *
   * @param reason why, in words for the report
   */
  public record Removed(String column, String reason) {}

  /**
   * One combination of a model with the similarity medians, swept: its chosen point.
   *
   * @param mae the chosen point's error on the inner validation, in business days
   */
  public record Swept(Combination combination, double alpha, double lambda, double mae) {}

  /**
   * One model's choice.
   *
   * @param cell the model as declared at the chosen combination, every column in it
   * @param combination the chosen combination; null for a model without the similarity medians
   * @param fitted the sweep's result on the columns kept: the chosen alpha and lambda, the inner
   *     validation error, and the design matrix's own drops of constant columns
   * @param removed the declared columns left out before the sweep
   * @param combinations every combination swept, in the order of {@link Combination#all}; empty
   *     for a model without the similarity medians
   * @param forest the forest swept beside it on the same columns and rows, at its chosen point
   */
  public record Model(Cell cell, Combination combination, Fitting.Fitted fitted,
      List<Removed> removed, List<Swept> combinations, Forest.Fitted forest) {}

  /**
   * @param projectMae the project median's error on the inner validation, the reference median
   *     the sweep's gains are measured against
   * @param constantMae the inner fitting median's error there
   * @param fitRows the inner fitting rows at or below the chosen k's fence, every model's
   * @param cut the tail cut: the chosen k, its fence, the usual flow's and every k swept
   * @param validationRows the inner validation issues every choice was scored on
   * @param scored which they are: the usual flow, or, only as a check of the code before ticket
   *     23, all of them
   * @param trees the trees of every forest swept ({@link #trees(SplitRule)})
   * @param analogy the analogy estimate's choice on the same validation issues, the temporal
   *     split's fifteenth model ({@link AnalogyEstimate#choose}); null under the random split,
   *     where it is no declared model
   * @param analogyFallback the value an issue without the candidate's median took in that
   *     choice: the global median of the inner fitting rows at or below the chosen k's fence, the
   *     declared reference's; NaN without the choice
   */
  public record Result(int fitRows, int validationRows, double projectMae,
      double constantMae, List<Model> models, TailCut.Choice cut, TailCut.Reading scored,
      int trees, AnalogyEstimate.Choice analogy, double analogyFallback) {}

  /**
   * A model's chosen mixture and penalty, and the values it reads the similarity medians at, as
   * the test refits it.
   *
   * @param combination null for a model without the similarity medians
   */
  public record Choice(double alpha, double lambda, Combination combination) {}

  /**
   * One line of the parameters file: which step chose it, for what, which parameter, its value.
   */
  public record Parameter(String step, String name, String parameter, String value) {}

  /**
   * The first line of every parameters file this step writes; one written for other models, as
   * the eleven before the fourteen were settled, or without the tail cut, before ticket 23, has
   * another, and is refused and never overwritten.
   */
  public static final String HEADER = "# the tail cut's k and fences, the k chosen on the own "
      + "start model, Elastic Net alpha and lambda, the similarity medians' n, n and k, and each "
      + "random forest's column share and leaf, chosen on the usual flow of the inner validation "
      + "only, the target in whole business days excluding Open/Reopened and retaining silence, "
      + "on the issues of at least one, for six "
      + "attribute sets: the own, Al-Zubaidi's and Kikas's at the start, the own retrospective "
      + "one without and with the comments and participants, both excluding release lag, and "
      + "Kikas's retrospective one; the "
      + "outer test was not read";

  /**
   * The first line of every parameters file this step writes for {@link Population#FIRST_COMMIT}:
   * the same choices on the same six attribute sets, the own ones with the two attributes of
   * decision 16a, and the target of that population, which another header tells apart.
   */
  public static final String HEADER_FIRST_COMMIT = "# the tail cut's k and fences, the k chosen "
      + "on the own start model, Elastic Net alpha and lambda, the similarity medians' n, n and k, "
      + "and each random forest's column share and leaf, chosen on the usual flow of the inner "
      + "validation only, the target in whole business days from the first commit to the first "
      + "resolution, its waits between marks capped, on the issues of at least one, for six "
      + "attribute sets: the own, Al-Zubaidi's and Kikas's at the start, the own retrospective "
      + "one without and with the comments and participants, both excluding release lag, the own "
      + "ones with the first commit's size and the retrospective ones with its share, and "
      + "Kikas's retrospective one; the outer test was not read";

  /** The header of {@code population}'s parameters file. */
  public static String header(final Population population) {
    return population == Population.FIRST_COMMIT ? HEADER_FIRST_COMMIT : HEADER;
  }

  /**
   * What follows a header to name the split rule its choices were made under, then the rule as
   * {@link SplitRule#setting} writes it. The random rule is never named: a header without this
   * mark, as every file written before the temporal split, is the random rule's.
   */
  public static final String RULE_MARK = "; the split rule: ";

  /**
   * The header of {@code population}'s parameters file under {@code rule}: {@link
   * #header(Population)}, followed for a rule other than the random one by {@link #RULE_MARK} and
   * its name.
   */
  public static String header(final Population population, final SplitRule rule) {
    return rule == SplitRule.RANDOM ? header(population)
        : header(population) + RULE_MARK + rule.setting();
  }

  /** The population a parameters file's header was written for; null for another header. */
  public static Population headerPopulation(final String header) {
    if (headerRule(header) == null) {
      return null;
    }
    final String base = base(header);
    return HEADER.equals(base) ? Population.IN_PROGRESS
        : HEADER_FIRST_COMMIT.equals(base) ? Population.FIRST_COMMIT : null;
  }

  /**
   * The split rule a parameters file's header was written under: the random one when it names
   * none; null for another header, or a rule that names nothing.
   */
  public static SplitRule headerRule(final String header) {
    if (header == null) {
      return null;
    }
    final String base = base(header);
    if (!HEADER.equals(base) && !HEADER_FIRST_COMMIT.equals(base)) {
      return null;
    }
    if (base.length() == header.length()) {
      return SplitRule.RANDOM;
    }
    final String named = header.substring(base.length() + RULE_MARK.length());
    for (final SplitRule r : SplitRule.values()) {
      if (r.setting().equals(named)) {
        return r;
      }
    }
    return null;
  }

  /** The header without its rule mark and what follows it. */
  private static String base(final String header) {
    final int mark = header.indexOf(RULE_MARK);
    return mark < 0 ? header : header.substring(0, mark);
  }

  /**
   * The step of the analogy estimate's frozen choice in the parameters file, the first-commit
   * study's temporal split only: one line under the name {@code chosen}, its {@code column}, a
   * candidate of {@link AnalogyEstimate#CANDIDATES} without its split suffix ({@link
   * #analogyLines}). No fallback is frozen: it is learned where the estimate is read ({@link
   * AnalogyEstimate.Frozen}), and a fallback line is refused.
   */
  public static final String ANALOGY_STEP = "analogy";

  private static final String COLUMN = "column";

  /** A fallback line, which a file of an earlier draft of the contract would carry; refused. */
  private static final String FALLBACK = "fallback_business_days";

  /**
   * The name, under {@link #FOREST_STEP}, of the line giving every forest's count of trees: one
   * line, its parameter {@code trees}, its value a whole number ({@link #treesLine}). A file
   * of the random split without it is at {@link Forest#TREES}; one of the temporal split must
   * give it.
   */
  public static final String EVERY_MODEL = "every_model";

  private static final String TREES = "trees";

  /**
   * The trees of every forest the selection sweeps under {@code rule}: {@link
   * Forest#LIBRARY_TREES} in the temporal split, {@link Forest#TREES} in the random one, whose
   * outer test was opened with them.
   */
  public static int trees(final SplitRule rule) {
    return rule == SplitRule.TEMPORAL ? Forest.LIBRARY_TREES : Forest.TREES;
  }

  /** The line giving every forest's count of trees, as the parameters file writes it. */
  public static String treesLine(final int trees) {
    return String.join("\t", FOREST_STEP, EVERY_MODEL, TREES, Integer.toString(trees));
  }

  /**
   * Every forest's count of trees, the file's line of {@link #EVERY_MODEL}; empty when it has
   * none. Refuses a count below one, or a line of {@link #EVERY_MODEL} with another parameter.
   */
  public static java.util.OptionalInt trees(final List<Parameter> parameters) {
    java.util.OptionalInt trees = java.util.OptionalInt.empty();
    for (final Parameter p : parameters) {
      if (p.step().equals(FOREST_STEP) && p.name().equals(EVERY_MODEL)) {
        if (!p.parameter().equals(TREES)) {
          throw new IllegalArgumentException("no forest parameter " + p.parameter()
              + " for every model: there it is " + TREES + " alone");
        }
        final int n = Integer.parseInt(p.value());
        if (n < 1) {
          throw new IllegalArgumentException("forests of " + n + " trees");
        }
        trees = java.util.OptionalInt.of(n);
      }
    }
    return trees;
  }

  /** The analogy estimate's frozen choice, as the parameters file writes it. */
  public static List<String> analogyLines(final AnalogyEstimate.Frozen frozen) {
    return List.of(String.join("\t", ANALOGY_STEP, CHOSEN, COLUMN, frozen.column()));
  }

  /**
   * The analogy estimate's frozen choice; null when the file has no line of {@link
   * #ANALOGY_STEP}. Refuses a fallback line, which is never frozen, and a line of another name or
   * parameter.
   */
  public static AnalogyEstimate.Frozen analogy(final List<Parameter> parameters) {
    String column = null;
    boolean any = false;
    for (final Parameter p : parameters) {
      if (!p.step().equals(ANALOGY_STEP)) {
        continue;
      }
      any = true;
      if (p.parameter().equals(FALLBACK)) {
        throw new IllegalArgumentException("the analogy estimate's " + FALLBACK + " is not "
            + "frozen: it is the global median learned where the estimate is read");
      }
      if (!p.name().equals(CHOSEN) || !p.parameter().equals(COLUMN)) {
        throw new IllegalArgumentException("no analogy line " + p.name() + " " + p.parameter()
            + ": the analogy estimate's choice is its " + CHOSEN + " " + COLUMN + " alone");
      }
      column = p.value();
    }
    if (!any) {
      return null;
    }
    return new AnalogyEstimate.Frozen(column);
  }

  /** The step of the tail cut's lines in the parameters file (ticket 23). */
  public static final String TAIL_STEP = "tail-cut";

  private static final String CHOSEN = "chosen";

  private static final String USUAL_FLOW = "usual_flow";

  private static final String K = "k";

  private static final String FENCE = "fence_business_days";

  private static final String FIT_ROWS = "fit_rows";

  private static final String MAE = "mae_business_days";

  /** The step of the Elastic Net choices in the parameters file. */
  public static final String STEP = "elastic-net";

  /** The step of the random forests' choices in the parameters file. */
  public static final String FOREST_STEP = "forest";

  /** The name of the lines giving what the choices were made on. */
  public static final String INPUTS = "inputs";

  private static final String MATRIX_SHA = "matrix_sha256";

  private static final String ALPHA = "alpha";

  private static final String LAMBDA = "lambda";

  private static final String JACCARD_N = "jaccard_n";

  private static final String EMBEDDING_N = "embedding_n";

  private static final String CLUSTER_K = "cluster_k";

  private static final String MTRY_SHARE = "mtry_share";

  private static final String MTRY = "mtry";

  private static final String NODE_SIZE = "node_size";

  private ElasticNetSelection() {}

  /**
   * Outer training, outer test, inner fitting and inner validation rows, counted from the split
   * labels: nothing else of an outer test row is read. The split read is the first-commit
   * population's when the rows carry it, the In Progress one's otherwise.
   */
  public static ProjectSplit.Sizes sizes(final List<RowCache.Entry> entries) {
    final Population split = entries.stream()
        .anyMatch(e -> e.parts().containsKey(Population.FIRST_COMMIT))
        ? Population.FIRST_COMMIT : P;
    int train = 0;
    int test = 0;
    int fit = 0;
    int validation = 0;
    for (final RowCache.Entry e : entries) {
      if (TRAIN.equals(e.outer(split))) {
        train++;
        fit += TRAIN.equals(e.inner(split)) ? 1 : 0;
        validation += TEST.equals(e.inner(split)) ? 1 : 0;
      } else if (TEST.equals(e.outer(split))) {
        test++;
      }
    }
    return new ProjectSplit.Sizes(train, test, fit, validation);
  }

  /**
   * The parameters file, one tab-separated line per parameter in four fields, {@code step, name,
   * parameter, value} ({@link #read} reads it): a commented header; a line with the SHA-256 of the
   * matrix the choices were made on; then per model an alpha line and a lambda line, each value
   * as Java prints a double, which reads back to the same number, and for a model with the
   * similarity medians a {@code jaccard_n}, an {@code embedding_n} and a {@code cluster_k} line;
   * then per model, under the step {@link #FOREST_STEP}, its forest's {@code mtry_share} (as Java
   * prints the double), {@code mtry} and {@code node_size}. The tail cut's lines, under the step
   * {@link #TAIL_STEP}, follow the matrix's: the chosen k and its fence, the usual flow's k and
   * fence, then per k of the grid its fence, its fitting rows and its error on the usual-flow
   * validation issues; each k as {@link TailCut#label} writes it, each fence and error as Java
   * prints the double, no cut's fence {@code Infinity}.
   */
  public static List<String> parameters(final Result r, final String matrixSha) {
    return parameters(r, matrixSha, P);
  }

  /** {@link #parameters(Result, String)} under {@code population}'s header. */
  public static List<String> parameters(final Result r, final String matrixSha,
      final Population population) {
    return parameters(r, matrixSha, population, SplitRule.RANDOM);
  }

  /**
   * {@link #parameters(Result, String)} under {@code population}'s header for {@code rule}
   * ({@link #header(Population, SplitRule)}).
   */
  public static List<String> parameters(final Result r, final String matrixSha,
      final Population population, final SplitRule rule) {
    // the temporal split freezes the analogy estimate's choice and the forests' trees; the random
    // split's file stays as every one written before it, with neither
    if (rule == SplitRule.TEMPORAL ? r.analogy() == null : r.analogy() != null) {
      throw new IllegalArgumentException("the analogy estimate is chosen under the temporal "
          + "split alone, not under the " + rule.setting() + " one");
    }
    if (r.trees() != trees(rule)) {
      throw new IllegalArgumentException("forests of " + r.trees() + " trees under the "
          + rule.setting() + " split, whose forests have " + trees(rule));
    }
    final List<String> out = new ArrayList<>();
    out.add(header(population, rule));
    out.add("# step\tname\tparameter\tvalue");
    out.add(String.join("\t", STEP, INPUTS, MATRIX_SHA, matrixSha));
    final TailCut.Choice cut = r.cut();
    out.add(String.join("\t", TAIL_STEP, CHOSEN, K, TailCut.label(cut.k())));
    out.add(String.join("\t", TAIL_STEP, CHOSEN, FENCE, Double.toString(cut.fence())));
    out.add(String.join("\t", TAIL_STEP, USUAL_FLOW, K, TailCut.label(TailCut.USUAL_FLOW_K)));
    out.add(String.join("\t", TAIL_STEP, USUAL_FLOW, FENCE, Double.toString(cut.usualFence())));
    for (final TailCut.Trial t : cut.grid()) {
      final String name = K + "_" + TailCut.label(t.k());
      out.add(String.join("\t", TAIL_STEP, name, FENCE, Double.toString(t.fence())));
      out.add(String.join("\t", TAIL_STEP, name, FIT_ROWS, Integer.toString(t.fitRows())));
      out.add(String.join("\t", TAIL_STEP, name, MAE, Double.toString(t.mae())));
    }
    for (final Model m : r.models()) {
      final String name = m.cell().label();
      out.add(String.join("\t", STEP, name, ALPHA, Double.toString(m.fitted().alpha())));
      out.add(String.join("\t", STEP, name, LAMBDA, Double.toString(m.fitted().lambda())));
      final Combination c = m.combination();
      if (c != null) {
        out.add(String.join("\t", STEP, name, JACCARD_N, Integer.toString(c.jaccardN())));
        out.add(String.join("\t", STEP, name, EMBEDDING_N, Integer.toString(c.embeddingN())));
        out.add(String.join("\t", STEP, name, CLUSTER_K, Integer.toString(c.clusterK())));
      }
    }
    for (final Model m : r.models()) {
      final String name = m.cell().label();
      final Forest.Fitted f = m.forest();
      out.add(String.join("\t", FOREST_STEP, name, MTRY_SHARE, Double.toString(f.share())));
      out.add(String.join("\t", FOREST_STEP, name, MTRY, Integer.toString(f.mtry())));
      out.add(String.join("\t", FOREST_STEP, name, NODE_SIZE, Integer.toString(f.nodeSize())));
    }
    if (rule == SplitRule.TEMPORAL) {
      out.add(treesLine(r.trees()));
      out.addAll(analogyLines(new AnalogyEstimate.Frozen(r.analogy().chosen())));
    }
    return out;
  }

  /**
   * Per model, in the file's order, its forest's column share, count and leaf. A model missing
   * one of the three refuses, as does a point the declared grid does not hold; which models must
   * have a forest is the caller's to check.
   */
  public static Map<String, Forest.Choice> forests(final List<Parameter> parameters) {
    final Map<String, Map<String, String>> byModel = new LinkedHashMap<>();
    for (final Parameter p : parameters) {
      if (!p.step().equals(FOREST_STEP)) {
        continue;
      }
      if (p.name().equals(EVERY_MODEL)) {
        continue; // the tree count, which trees reads
      }
      if (!List.of(MTRY_SHARE, MTRY, NODE_SIZE).contains(p.parameter())) {
        throw new IllegalArgumentException("no forest parameter " + p.parameter() + " in " + p);
      }
      byModel.computeIfAbsent(p.name(), k -> new HashMap<>()).put(p.parameter(), p.value());
    }
    final Map<String, Forest.Choice> out = new LinkedHashMap<>();
    for (final Map.Entry<String, Map<String, String>> e : byModel.entrySet()) {
      final Map<String, String> v = e.getValue();
      if (!v.keySet().equals(Set.of(MTRY_SHARE, MTRY, NODE_SIZE))) {
        throw new IllegalArgumentException(e.getKey() + "'s forest has not its mtry share, mtry "
            + "and leaf: " + v);
      }
      out.put(e.getKey(), new Forest.Choice(Double.parseDouble(v.get(MTRY_SHARE)),
          Integer.parseInt(v.get(MTRY)), Integer.parseInt(v.get(NODE_SIZE))));
    }
    return out;
  }

  /**
   * Every parameter of a parameters file, in its order; comments and blank lines skipped. A line
   * not of four fields, or a step, name and parameter given twice, refuses.
   */
  public static List<Parameter> read(final List<String> lines) {
    final List<Parameter> out = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    for (final String line : lines) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final String[] f = line.split("\t", -1);
      if (f.length != 4) {
        throw new IllegalArgumentException("not step, name, parameter and value: " + line);
      }
      if (!seen.add(f[0] + '\u0000' + f[1] + '\u0000' + f[2])) {
        throw new IllegalArgumentException("given twice: " + line);
      }
      out.add(new Parameter(f[0], f[1], f[2], f[3]));
    }
    return List.copyOf(out);
  }

  /**
   * Per model, in the file's order, its alpha, lambda and, when given, its combination. A model
   * missing alpha or lambda refuses, as does one with only part of its n, n and k, or a value the
   * similarity stage does not compute; which models must have a combination is the cells' to
   * check ({@link #cells(Map, Augmentation.Variant)}).
   */
  public static Map<String, Choice> choices(final List<Parameter> parameters) {
    final Map<String, Map<String, String>> byModel = new LinkedHashMap<>();
    for (final Parameter p : parameters) {
      if (!p.step().equals(STEP) || p.name().equals(INPUTS)) {
        continue;
      }
      if (!List.of(ALPHA, LAMBDA, JACCARD_N, EMBEDDING_N, CLUSTER_K).contains(p.parameter())) {
        throw new IllegalArgumentException("no parameter " + p.parameter() + " in " + p);
      }
      byModel.computeIfAbsent(p.name(), k -> new HashMap<>()).put(p.parameter(), p.value());
    }
    final Map<String, Choice> out = new LinkedHashMap<>();
    for (final Map.Entry<String, Map<String, String>> e : byModel.entrySet()) {
      final Map<String, String> v = e.getValue();
      if (!v.containsKey(ALPHA) || !v.containsKey(LAMBDA)) {
        throw new IllegalArgumentException(e.getKey() + " has not both alpha and lambda: " + v);
      }
      final int given = (v.containsKey(JACCARD_N) ? 1 : 0) + (v.containsKey(EMBEDDING_N) ? 1 : 0)
          + (v.containsKey(CLUSTER_K) ? 1 : 0);
      if (given != 0 && given != 3) {
        throw new IllegalArgumentException(e.getKey() + " has only part of its Jaccard n, "
            + "embedding n and cluster k: " + v);
      }
      final Combination c = given == 0 ? null : new Combination(
          Integer.parseInt(v.get(JACCARD_N)), Integer.parseInt(v.get(EMBEDDING_N)),
          Integer.parseInt(v.get(CLUSTER_K)));
      out.put(e.getKey(), new Choice(Double.parseDouble(v.get(ALPHA)),
          Double.parseDouble(v.get(LAMBDA)), c));
    }
    return out;
  }

  /** Per model given one, its combination; a model without the similarity medians has none. */
  public static Map<String, ElasticNetSelection.Combination> combinations(
      final Map<String, ElasticNetSelection.Choice> choices) {
    final Map<String, ElasticNetSelection.Combination> out = new LinkedHashMap<>();
    choices.forEach((name, c) -> {
      if (c.combination() != null) {
        out.put(name, c.combination());
      }
    });
    return out;
  }

  /**
   * The tail cut the parameters file records: the chosen k and its fence, the usual flow's fence
   * and every k of the grid with its fence, fitting rows and error. A file without any of them,
   * as one written before ticket 23, refuses, as does a k off the grid, a usual flow at another
   * k than {@link TailCut#USUAL_FLOW_K} or a line the step does not write.
   */
  public static TailCut.Choice tailCut(final List<Parameter> parameters) {
    final Map<String, Map<String, String>> byName = new LinkedHashMap<>();
    for (final Parameter p : parameters) {
      if (p.step().equals(TAIL_STEP)) {
        byName.computeIfAbsent(p.name(), k -> new HashMap<>()).put(p.parameter(), p.value());
      }
    }
    final Map<String, Set<String>> expected = new LinkedHashMap<>();
    expected.put(CHOSEN, Set.of(K, FENCE));
    expected.put(USUAL_FLOW, Set.of(K, FENCE));
    for (final String k : TailCut.labels()) {
      expected.put(K + "_" + k, Set.of(FENCE, FIT_ROWS, MAE));
    }
    for (final Map.Entry<String, Set<String>> e : expected.entrySet()) {
      final Map<String, String> v = byName.getOrDefault(e.getKey(), Map.of());
      if (!v.keySet().equals(e.getValue())) {
        throw new IllegalArgumentException("the parameters file has not the tail cut's "
            + e.getKey() + " " + e.getValue() + " but " + v.keySet() + "; one written before "
            + "the tail cut has none: run issues-in-progress-similarity-elastic-net again");
      }
    }
    if (!byName.keySet().equals(expected.keySet())) {
      throw new IllegalArgumentException("the tail cut's lines are for " + byName.keySet()
          + ", not " + expected.keySet());
    }
    if (TailCut.parse(byName.get(USUAL_FLOW).get(K)) != TailCut.USUAL_FLOW_K) {
      throw new IllegalArgumentException("the usual flow is at k "
          + TailCut.label(TailCut.USUAL_FLOW_K) + ", not " + byName.get(USUAL_FLOW).get(K));
    }
    final List<TailCut.Trial> grid = new ArrayList<>();
    for (final double k : TailCut.GRID) {
      final Map<String, String> v = byName.get(K + "_" + TailCut.label(k));
      grid.add(new TailCut.Trial(k, Double.parseDouble(v.get(FENCE)),
          Integer.parseInt(v.get(FIT_ROWS)), Double.parseDouble(v.get(MAE))));
    }
    return new TailCut.Choice(TailCut.parse(byName.get(CHOSEN).get(K)),
        Double.parseDouble(byName.get(CHOSEN).get(FENCE)),
        Double.parseDouble(byName.get(USUAL_FLOW).get(FENCE)), List.copyOf(grid));
  }

  /** The SHA-256 of the matrix the choices were made on; null if not given. */
  public static String matrixSha(final List<Parameter> parameters) {
    return parameters.stream().filter(p -> p.step().equals(STEP) && p.name().equals(INPUTS)
        && p.parameter().equals(MATRIX_SHA)).map(Parameter::value).findFirst().orElse(null);
  }

  /** The choice with the threads {@link Parallel#ELASTIC_NET_THREADS} asks for. */
  public static Result run(final List<RowCache.Entry> entries) {
    return run(entries, Parallel.threads(Parallel.ELASTIC_NET_THREADS));
  }

  /**
   * Chooses the tail cut's k ({@link TailCut}, ticket 23), then, by the declared sweep ({@link
   * Fitting#sweep}) on the inner fitting rows that cut leaves scored on the usual-flow inner
   * validation issues, alpha and lambda for every model, and for each of {@link
   * #WITH_SIMILARITY} the combination too. The outer test rows are dropped before anything is
   * read from them: only an entry inside the outer training set goes on, and of those {@link
   * Fitting.Mode#INNER} reads the inner fitting and validation rows alone.
   *
   * <p><b>The k.</b> Both fences are taken on the inner fitting set before any cut, never on the
   * validation: the usual flow's at {@link TailCut#USUAL_FLOW_K} and each candidate's. Every
   * choice -- alpha, lambda, the medians' n and k, each forest's point and the k itself -- is
   * scored on the usual-flow validation issues, those at or below the usual flow's fence (André,
   * 18/10/2026). For each k of {@link TailCut#GRID}, {@link #BEFORE_PRESSURE} is chosen as
   * below on the inner fitting rows at or below its fence, and its chosen point's error is the
   * k's; the lowest wins. <b>Ties</b>: the grid is read from the larger k down and a k is kept
   * only when its error is strictly lower, the sweep's own rule, so of errors exactly equal the
   * larger k, the smaller cut, wins. Every model and forest is then chosen on the rows of that k,
   * the own start model's choice there reused as it is; the references learn
   * on the same rows.
   *
   * <p>Every sweep -- one per model without the medians, one per model and combination with them
   * -- is one job, fitted alone on one thread from the same sides, which every job only reads; up
   * to {@code threads} jobs run at once. The results are then reduced in the declared order, the
   * models in {@link #MODELS}' order and each one's combinations in {@link Combination#all}'s,
   * so the result is the same bits whatever the number of threads.
   *
   * <p>A combination at which a model has no column observed on the inner fitting rows refuses
   * the whole run, as a model with no column did before, rather than being skipped. So does an
   * outer training entry whose business days are not set, one that did not go through {@link
   * BusinessDays#population}: the target here is never the hours.
   *
   * <p>Then each model's forest is swept on the columns its Elastic Net kept at its chosen
   * combination, one job per model on the same threads, read back in the models' order. The
   * forest's trees have seeds of their own, so its choice is the same bits whatever the number of
   * threads, and the Elastic Net's choices are made before it and do not depend on it.
   */
  public static Result run(final List<RowCache.Entry> entries, final int threads) {
    return run(entries, threads, P);
  }

  /**
   * {@link #run(List, int)} on the split and the attribute sets of {@code population}: the outer
   * training rows are those of its split, the models its sets ({@link #cells(Combination,
   * Augmentation.Variant, Population)}). Nothing else differs.
   */
  public static Result run(final List<RowCache.Entry> entries, final int threads,
      final Population population) {
    return run(entries, threads, population, SplitRule.RANDOM);
  }

  /**
   * {@link #run(List, int, Population)} under {@code rule}. Under the temporal split (its
   * ticket 06) two things differ: every forest is swept with {@link
   * Forest#LIBRARY_TREES} trees; and, once the k is chosen, the analogy estimate's candidate is
   * chosen by {@link AnalogyEstimate#choose} on the same validation issues every choice is scored
   * on, in their inner columns, an issue without the candidate's median taking the global median
   * of the inner fitting rows at or below the chosen k's fence, the declared reference's. Nothing
   * of the fourteen's choices depends on it. Under the random split, nothing differs.
   */
  public static Result run(final List<RowCache.Entry> entries, final int threads,
      final Population population, final SplitRule rule) {
    return select(entries, TailCut.GRID, TailCut.Reading.USUAL_FLOW, threads, population, rule);
  }

  static Result select(final List<RowCache.Entry> entries, final List<Double> grid,
      final TailCut.Reading scored, final int threads) {
    return select(entries, grid, scored, threads, P, SplitRule.RANDOM);
  }

  static Result select(final List<RowCache.Entry> entries, final List<Double> grid,
      final TailCut.Reading scored, final int threads, final Population population,
      final SplitRule rule) {
    final List<Cell> every = everyCell(Augmentation.Variant.INNER, population);
    final List<RowCache.Entry> training = BusinessDays.counted(
        entries.stream().filter(e -> TRAIN.equals(e.outer(population))).toList());
    final Fitting.Sides whole =
        flagged(Fitting.sides(training, Fitting.Mode.INNER, population), every);
    // both fences on the inner fitting set as it is, before any cut
    final double usualFence = TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K);
    final Fitting.Sides sides = scored == TailCut.Reading.ALL ? whole
        : TailCut.usualFlow(whole, usualFence);

    final List<TailCut.Trial> trials = new ArrayList<>(grid.size());
    final Map<Double, Model> own = new HashMap<>();
    for (final double k : grid) {
      final double fence = TailCut.fence(sides.fitUnits(), k);
      final Fitting.Sides cut = TailCut.cut(sides, fence);
      final Model m = choose(List.of(BEFORE_PRESSURE), every, cut, threads).get(0);
      own.put(k, m);
      trials.add(new TailCut.Trial(k, fence, cut.fitUnits().size(),
          TailCut.mae(m.fitted().predicted(), cut.observed(), usualFence)));
    }
    final TailCut.Trial best = trials.get(TailCut.chosen(trials));
    final Fitting.Sides cut = TailCut.cut(sides, best.fence());
    final References refs = References.learnedOn(cut.fitUnits());
    final double[] observed = cut.observed();
    final double constantMae = Fitting.mae(refs.constant(observed.length), observed);
    final double projectMae = Fitting.mae(refs.projectMedian(cut.scoreUnits()), observed);

    final List<String> others =
        MODELS.stream().filter(name -> !name.equals(BEFORE_PRESSURE)).toList();
    final List<Model> rest = choose(others, every, cut, threads);
    final List<Model> chosen = new ArrayList<>(MODELS.size());
    for (final String name : MODELS) {
      chosen.add(name.equals(BEFORE_PRESSURE) ? own.get(best.k())
          : rest.get(others.indexOf(name)));
    }

    final List<Callable<Forest.Fitted>> trees = new ArrayList<>(chosen.size());
    for (final Model m : chosen) {
      trees.add(() -> Forest.sweep(m.fitted().cell(), cut, projectMae, constantMae,
          trees(rule)));
    }
    final List<Forest.Fitted> forests = Parallel.map(threads, trees);
    final List<Model> out = new ArrayList<>(chosen.size());
    for (int m = 0; m < chosen.size(); m++) {
      final Model x = chosen.get(m);
      out.add(new Model(x.cell(), x.combination(), x.fitted(), x.removed(), x.combinations(),
          forests.get(m)));
    }
    // the temporal split's fifteenth model, its fallback the global median learned just above
    final double fallback = rule == SplitRule.TEMPORAL ? refs.constant(1)[0] : Double.NaN;
    final AnalogyEstimate.Choice analogy = rule == SplitRule.TEMPORAL
        ? AnalogyEstimate.choose(cut.scoreRows(), Augmentation.Variant.INNER, fallback, observed)
        : null;
    return new Result(cut.fitUnits().size(), observed.length, projectMae, constantMae,
        List.copyOf(out), new TailCut.Choice(best.k(), best.fence(), usualFence,
            List.copyOf(trials)), scored, trees(rule), analogy, fallback);
  }

  /**
   * The Elastic Net choice of each of {@code names}, in their order, on {@code sides}, without
   * its forest: every sweep one job, reduced in the declared order (see {@link #run(List, int)}).
   * The references the scores are read against learn on the fitting side given.
   */
  private static List<Model> choose(final List<String> names, final List<Cell> every,
      final Fitting.Sides sides, final int threads) {
    final List<Combination> combinations = Combination.all();
    final References refs = References.learnedOn(sides.fitUnits());
    final double[] observed = sides.observed();
    final double constantMae = Fitting.mae(refs.constant(observed.length), observed);
    final double projectMae = Fitting.mae(refs.projectMedian(sides.scoreUnits()), observed);

    // per model, one job, or one per combination; flattened in the declared order
    final List<Callable<Model>> jobs = new ArrayList<>();
    for (final String name : names) {
      final int m = MODELS.indexOf(name);
      final boolean similar = WITH_SIMILARITY.contains(name);
      for (int c = 0; c < (similar ? combinations.size() : 1); c++) {
        final Cell cell = every.get(c * MODELS.size() + m);
        final Combination combination = similar ? combinations.get(c) : null;
        jobs.add(() -> {
          final Kept kept = kept(cell, sides.fitRows(), "inner fitting");
          return new Model(cell, combination,
              Fitting.sweep(kept.cell(), sides, projectMae, constantMae, 1), kept.removed(),
              List.of(), null);
        });
      }
    }
    final List<Model> swept = Parallel.map(threads, jobs);

    final List<Model> chosen = new ArrayList<>();
    int next = 0;
    for (final String name : names) {
      if (!WITH_SIMILARITY.contains(name)) {
        chosen.add(swept.get(next++));
        continue;
      }
      Model best = null;
      final List<Swept> each = new ArrayList<>(combinations.size());
      for (int c = 0; c < combinations.size(); c++) {
        final Model m = swept.get(next++);
        final Fitting.Fitted f = m.fitted();
        each.add(new Swept(m.combination(), f.alpha(), f.lambda(), f.score().mae()));
        // strictly lower, as the sweep keeps its points: a tie stays with the earlier combination
        if (best == null || f.score().mae() < best.fitted().score().mae()) {
          best = m;
        }
      }
      chosen.add(new Model(best.cell(), best.combination(), best.fitted(), best.removed(),
          List.copyOf(each), null));
    }
    return chosen;
  }

  /**
   * A model with the declared columns that are missing on every fitting row left out, and those
   * columns with the reason.
   */
  public record Kept(Cell cell, List<Removed> removed) {}

  /**
   * Leaves out of {@code cell} every column missing on all of {@code fitRows}, recording why; it is
   * never filled from the scored rows. A model left with no column refuses.
   *
   * @param fittingSet the fitting set's name in the reason, as "inner fitting"
   */
  public static Kept kept(final Cell cell, final List<Row> fitRows, final String fittingSet) {
    final List<Attribute> kept = new ArrayList<>();
    final List<Removed> removed = new ArrayList<>();
    for (final Attribute a : cell.attributes()) {
      if (fitRows.stream().noneMatch(r -> r.values().containsKey(a.name()))) {
        removed.add(new Removed(a.name(), "missing on all " + fitRows.size() + " " + fittingSet
            + " rows"));
      } else {
        kept.add(a);
      }
    }
    if (kept.isEmpty()) {
      throw new IllegalArgumentException(cell.label() + " has no column observed on the "
          + fittingSet + " rows");
    }
    return new Kept(new Cell(cell.label(), cell.family(), cell.scenario(), kept),
        List.copyOf(removed));
  }

  /**
   * Both sides with a missing indicator beside every column of {@code cells} the extracted matrix
   * does not hold (see {@link #flagged(List, Set)}).
   */
  public static Fitting.Sides flagged(final Fitting.Sides raw, final List<Cell> cells) {
    final Set<String> added = new HashSet<>();
    for (final Cell cell : cells) {
      for (final Attribute a : cell.attributes()) {
        if (!Attributes.all().contains(a)) {
          added.add(a.name());
        }
      }
    }
    return new Fitting.Sides(raw.fitUnits(), flagged(raw.fitRows(), added), raw.scoreUnits(),
        flagged(raw.scoreRows(), added), raw.observed());
  }

  /**
   * The rows with a missing indicator beside each new column, one where the value is missing and
   * zero where it is observed, as the extracted matrix stores beside its own columns; the value
   * itself stays missing for the preparation to fill. A value that is not a finite number refuses.
   */
  private static List<Row> flagged(final List<Row> rows, final Set<String> added) {
    final List<Row> out = new ArrayList<>(rows.size());
    for (final Row row : rows) {
      final Map<String, Double> values = new HashMap<>(row.values());
      for (final String column : added) {
        final Double v = values.get(column);
        if (v != null && !Double.isFinite(v)) {
          throw new IllegalArgumentException(column + " of " + row.unitId() + " is " + v
              + ", not a number");
        }
        values.put(column + ABSENT, v == null ? 1.0 : 0.0);
      }
      out.add(new Row(row.unitId(), values, row.absent()));
    }
    return out;
  }

  /**
   * Every model at every combination, in the given variant: the combinations in {@link
   * Combination#all}'s order, the six models of each in theirs. What the selection may read.
   */
  public static List<Cell> everyCell(final Augmentation.Variant variant) {
    return everyCell(variant, P);
  }

  /** {@link #everyCell(Augmentation.Variant)} for the sets of {@code population}. */
  public static List<Cell> everyCell(final Augmentation.Variant variant,
      final Population population) {
    final List<Cell> out = new ArrayList<>();
    for (final Combination c : Combination.all()) {
      out.addAll(cells(c, variant, population));
    }
    return List.copyOf(out);
  }

  /**
   * The six models in their order, reading the split-dependent columns of the given variant,
   * each with the similarity medians at its own combination.
   *
   * @param combinations per model of {@link #WITH_SIMILARITY}, the values it reads the medians
   *     at; exactly those models, or the call refuses
   */
  public static List<Cell> cells(final Map<String, Combination> combinations,
      final Augmentation.Variant variant) {
    return cells(combinations, variant, P);
  }

  /** {@link #cells(Map, Augmentation.Variant)} for the sets of {@code population}. */
  public static List<Cell> cells(final Map<String, Combination> combinations,
      final Augmentation.Variant variant, final Population population) {
    if (!combinations.keySet().equals(Set.copyOf(WITH_SIMILARITY))) {
      throw new IllegalArgumentException("the models with the similarity medians are "
          + WITH_SIMILARITY + "; Jaccard n, embedding n and cluster k were given for "
          + combinations.keySet());
    }
    final List<Cell> out = new ArrayList<>(MODELS.size());
    final List<Cell> without =
        cells(combinations.get(WITH_SIMILARITY.get(0)), variant, population);
    for (int m = 0; m < MODELS.size(); m++) {
      final Combination c = combinations.get(MODELS.get(m));
      out.add(c == null ? without.get(m) : cells(c, variant, population).get(m));
    }
    return List.copyOf(out);
  }

  /**
   * The six models in their order, reading the split-dependent columns of the given variant,
   * the three with the similarity medians at {@code c}; the other three do not depend on it.
   */
  public static List<Cell> cells(final Combination c, final Augmentation.Variant variant) {
    return cells(c, variant, P);
  }

  /**
   * {@link #cells(Combination, Augmentation.Variant)} for the sets of {@code population}. For
   * {@link Population#FIRST_COMMIT} the two attributes of decision 16a join the own sets: the
   * first commit's size ({@link FirstCommit#FIRST_LINES}, {@code log1p}) closes the own start
   * model, so both readings of the retrospective model read it too; the first commit's share of
   * the issue's lines ({@link FirstCommit#FIRST_SHARE}, raw) closes the retrospective columns of
   * both readings, since its total is known only at the first resolution. The literature sets are
   * the same in both populations.
   */
  public static List<Cell> cells(final Combination c, final Augmentation.Variant variant,
      final Population population) {
    final boolean firstCommit = population == Population.FIRST_COMMIT;
    // the own start model: the ten of the own specification, then the three medians
    final List<Attribute> start = new ArrayList<>();
    start.add(Attributes.byName("A01_desc_length"));
    start.add(Attributes.byName("A03_team_size"));
    for (final String team : Extractor.TEAM_REVISED) {
      start.add(revised(team));
    }
    start.add(revised(Augmentation.reporter(variant)));
    for (final String executor : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
        "A13_exec_open_load")) {
      start.add(Attributes.byName(executor));
    }
    start.add(revised(Augmentation.jaccard(c.jaccardN(), variant)));
    start.add(revised(Augmentation.embedding(c.embeddingN(), variant)));
    start.add(revised(Augmentation.cluster(c.clusterK(), variant)));
    if (firstCommit) {
      start.add(new Attribute(FirstCommit.FIRST_LINES, FirstCommit.FIRST_LINES, Family.OWN, null,
          Construct.Kind.NUMERIC, Construct.Transform.LOG1P, false));
    }

    // the main reading: the own start model, then content and assignee changes
    final List<Attribute> retrospective = new ArrayList<>(start);
    retrospective.addAll(retrospective(MAIN_READING));
    if (firstCommit) {
      retrospective.add(new Attribute(FirstCommit.FIRST_SHARE, FirstCommit.FIRST_SHARE,
          Family.OWN, Scenario.Block.CONTENT, Construct.Kind.NUMERIC, Construct.Transform.NONE,
          false));
    }
    // the ceiling: the main reading, then the comments and the participants, nothing else
    final List<Attribute> withDiscussion = new ArrayList<>(retrospective);
    withDiscussion.addAll(retrospective(Retrospective.DISCUSSION));

    return List.of(
        new Cell(BEFORE_PRESSURE, Family.OWN, Scenario.C1, start),
        literature(ALZUBAIDI_START, "ALZUBAIDI / C1"),
        literature(KIKAS_START, "KIKAS / C1"),
        new Cell(RETROSPECTIVE, Family.OWN, Scenario.C2, retrospective),
        new Cell(WITH_DISCUSSION, Family.OWN, Scenario.C2, withDiscussion),
        literature(KIKAS_RETROSPECTIVE, "KIKAS / C2"));
  }

  /**
   * The retrospective columns the main reading reads, in their order: the five content ones,
   * the assignee changes; comments, participants and release lag are excluded.
   */
  static final List<String> MAIN_READING = Retrospective.COLUMNS.stream()
      .filter(c -> !Retrospective.DISCUSSION.contains(c) && !Retrospective.URGENCY.equals(c))
      .toList();

  /**
   * The given attributes of the retrospective scenario ({@link Retrospective}), in the order
   * given: the counts and volumes {@code log1p}, the ratio and the means raw, as the registered
   * columns they stand beside ({@code A15} to {@code A17}, {@code D07}, {@code D08}, {@code K03}
   * to {@code K05}); a missing indicator beside the five content attributes that can be missing.
   * The audit-only release-lag attribute is supported by this helper but passed by no model.
   * Split-independent: the same in both variants.
   */
  static List<Attribute> retrospective(final List<String> names) {
    if (!Retrospective.COLUMNS.containsAll(names)) {
      throw new IllegalArgumentException(names + " are not all retrospective columns");
    }
    final List<Attribute> out = new ArrayList<>(names.size());
    for (final String name : names) {
      out.add(new Attribute(name, name, Family.OWN,
          Retrospective.CONTENT.contains(name) ? Scenario.Block.CONTENT
              : Scenario.Block.EXPOSURE,
          Construct.Kind.NUMERIC,
          Retrospective.COUNTS.contains(name) ? Construct.Transform.LOG1P
              : Construct.Transform.NONE,
          Retrospective.CONTENT.contains(name) || Retrospective.URGENCY.equals(name)));
    }
    return List.copyOf(out);
  }

  private static Cell literature(final String name, final String label) {
    final Cell cell = Cell.byLabel(label);
    return new Cell(name, cell.family(), cell.scenario(), cell.attributes());
  }

  /** A column of the new chain: numeric, {@code log1p}, with a missing indicator. */
  private static Attribute revised(final String name) {
    return new Attribute(name, name, Family.OWN, null, Construct.Kind.NUMERIC,
        Construct.Transform.LOG1P, true);
  }
}
