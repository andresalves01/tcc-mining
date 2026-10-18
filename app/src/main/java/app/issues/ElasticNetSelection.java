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
 * The Elastic Net models of the description-similarity chain, and the choice on the inner
 * validation only of each one's alpha and lambda, by the registered chain's own sweep, and, for
 * the six models reading the similarity medians, of the Jaccard n, embedding n and cluster k
 * they read.
 *
 * <p><b>Models.</b> Nine, each a {@link Cell}. Two are the registered in-progress cells read as
 * they are: Al-Zubaidi at the start ({@code ALZUBAIDI / C1}) and Kikas at the start ({@code
 * KIKAS / C1}). The own specification with similarity, the study's own contribution, is
 * description length and team size, the registered {@code A01} and {@code A03}; the team's median
 * and spread of lines of code and of cyclomatic complexity; the corrected reporter median; the
 * executor's lines of code, cyclomatic complexity and open workload, the registered {@code A09},
 * {@code A10} and {@code A13}; the fix version set before the start, the days left in the
 * project's release cycle and the pace of its other Blocker issues ({@link Pressure}, ticket 19);
 * and the Jaccard, embedding and cluster medians, sixteen in all. The own specification without
 * similarity, its control, is the same thirteen without the three medians; the similarity block
 * alone is the three medians; the union is the literature families as the registered union reads
 * them, Kikas's body length entering once as Al-Zubaidi's word counts, followed by the own
 * specification with similarity. The seventh, the retrospective scenario, is the own
 * specification with similarity followed by the ten attributes observed between the first In
 * Progress and the first resolution ({@link Retrospective}), twenty-six in all. The eighth is the
 * own specification with similarity as it was before ticket 19, the ten and the three medians
 * without the start columns of {@link Pressure} (item 36), so the new start model is compared
 * with the old one on the same run. The ninth is the retrospective model as it was before ticket
 * 19, the retrospective one without ticket 19's five columns -- the three start columns of
 * {@link Pressure} and the blocking-link wait and urgency of {@link Retrospective} -- and their
 * missing indicators (item 43), twenty-one in all: it adds no attribute and only measures what
 * ticket 19 did to the retrospective model.
 *
 * <p><b>Ticket 15.</b> The retrospective model ({@code own_retrospective}) reads what was observed
 * up to the resolution, and its counts grow with the duration itself: a gain over the own
 * specification with similarity is association with the duration, not a prediction available
 * during the work. It reads the similarity medians, so it is swept over the 27 combinations as the
 * other models with them are; its forest follows as for every model. The six models before it are
 * declared, read and chosen as they were; the ten columns are new names, beside the registered
 * columns of the same constructs, which keep their meaning.
 *
 * <p><b>Ticket 12.</b> The previous own specification ({@code OWN / C1}) is no longer a model of
 * this chain, and the team size returns to the own specification. The registered in-progress
 * chain, its cell and its outputs, are untouched.
 *
 * <p><b>Similarity medians as attributes</b> (ticket 11, reversing ticket 08's choice by each
 * median's error read alone as a prediction). The six models with the medians ({@link
 * #WITH_SIMILARITY}) are swept once per {@link Combination} of a Jaccard n, an embedding n and a
 * cluster k among the values the similarity stage computes, 27 in all, each sweep the registered
 * alpha by lambda grid, and keep the combination whose chosen point errs least on the inner
 * validation. <b>Ties</b>: within a combination the sweep's own rule holds (a point is kept only
 * when its error is strictly lower, so the earlier point of the grid wins); across combinations
 * the same rule is read in the order of {@link Combination#all}, so of combinations whose errors
 * are exactly equal the one with the smaller Jaccard n wins, then the smaller embedding n, then
 * the smaller k. No tolerance: the sweep has none. The three models without the medians are swept
 * once.
 *
 * <p><b>Columns.</b> The reporter median, the Blocker pace and the three similarity medians depend
 * on the split, each reading the durations of its training side only (ticket 21); each model
 * reads them in the variant of its mode, {@code _inner} here. The three similarity
 * medians enter as {@code log1p} before standardisation, as the spec says; the reporter median and
 * the four team columns take the transform of the registered columns they revise ({@code D05};
 * {@code A04} to {@code A07}), which is also {@code log1p}. Every new column carries a missing
 * indicator: a missing value is handed to the registered preparation as missing, filled there by
 * the fitting set's median. The retrospective model's ten are declared as {@link
 * #retrospective} says: the three counts of the discussion are never missing and carry none. The
 * registered columns ({@code A01}, {@code A03}, {@code A09}, {@code A10}, {@code A13}) keep their
 * own transform and missing-indicator setting. A declared
 * column missing on every inner fitting row is removed from the model before the sweep, with the
 * reason recorded, never filled from the validation rows. The registered preparation, sweep and
 * grid are reused as they are.
 *
 * <p><b>Random forest</b> (ticket 13, a robustness check, not a new contribution). Beside each
 * model, once its Elastic Net is chosen, the declared {@link Forest} grid is swept on the same
 * inner fitting and validation rows, with the same columns -- the similarity medians at the
 * combination the Elastic Net chose, and every declared column not removed for being missing,
 * whether or not the Elastic Net gave it a weight -- the same design matrix fitted on the fitting
 * side, the same {@code log1p} target and back-transform; n and k are not swept again. The point with the lowest inner
 * validation error is kept; of points whose errors are exactly equal, the first in the grid's
 * order (the smaller column share, then the smaller leaf; {@link Forest#chosen}). Its column share,
 * the count that share gave and its leaf go to the parameters file under the step {@link
 * #FOREST_STEP}.
 *
 * <p><b>Tail cut</b> (ticket 23, item 50). Before anything else, the k of the fitting cut is
 * chosen on the own specification with similarity alone ({@link #run(List, int)}); every model,
 * forest and reference is then fitted on the inner fitting rows at or below that k's fence
 * ({@link TailCut}). The similarity medians, the reporter median and the Blocker pace are columns
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

  /** The retrospective scenario's model (ticket 15), the seventh of {@link #MODELS}. */
  public static final String RETROSPECTIVE = "own_retrospective";

  /**
   * The own specification with similarity as it was before ticket 19, without the three start
   * columns of {@link Pressure} (item 36), the eighth of {@link #MODELS}: the new start model is
   * compared with it on the same run, and that comparison decides which goes to the outer test.
   */
  public static final String BEFORE_PRESSURE = "own_with_similarity_before_pressure";

  /**
   * The own specification with similarity, the start model the SPSP would use: the tail cut's k
   * is chosen on it alone, then reaches every model (item 50 iv).
   */
  public static final String OWN_WITH_SIMILARITY = "own_with_similarity";

  /**
   * The retrospective model as it was before ticket 19, without ticket 19's five columns and their
   * missing indicators (item 43), the last of {@link #MODELS}: compared with the retrospective
   * model on the same run, it measures what ticket 19 did to it and nothing else.
   */
  public static final String RETROSPECTIVE_BEFORE_PRESSURE = "own_retrospective_before_pressure";

  /**
   * What the ninth model is, for the selection step's output and record and the inner report;
   * never the test's, which does not score it (ticket 22).
   */
  public static final String RETROSPECTIVE_BEFORE_PRESSURE_ROLE = "the retrospective model "
      + "without ticket 19's five columns (the three start ones, the blocking-link wait and "
      + "urgency) and their missing indicators; it adds no attribute and only measures what "
      + "ticket 19 did to the retrospective model; only the inner report scores it";

  /**
   * The nine models, in their order: the six of the start, the retrospective one, the own
   * specification with similarity as before ticket 19, then the retrospective one as before it.
   */
  public static final List<String> MODELS = List.of("own_without_similarity", "similarity_alone",
      OWN_WITH_SIMILARITY, "alzubaidi_start", "kikas_start", "union_own", RETROSPECTIVE,
      BEFORE_PRESSURE, RETROSPECTIVE_BEFORE_PRESSURE);

  /** The models that read the similarity medians, whose n, n and k are chosen with them. */
  public static final List<String> WITH_SIMILARITY = List.of("similarity_alone",
      "own_with_similarity", "union_own", RETROSPECTIVE, BEFORE_PRESSURE,
      RETROSPECTIVE_BEFORE_PRESSURE);

  private static final Population P = Population.IN_PROGRESS;

  private static final String TRAIN = "TRAIN";
  private static final String TEST = "TEST";

  /** The suffix of a missing indicator, as the registered matrix names its own. */
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
   * @param projectMae the project median's error on the inner validation, the sweep's
   *     baseline as in the registered grid
   * @param constantMae the inner fitting median's error there
   * @param fitRows the inner fitting rows at or below the chosen k's fence, every model's
   * @param cut the tail cut: the chosen k, its fence, the usual flow's and every k swept
   * @param validationRows the inner validation issues every choice was scored on
   * @param scored which they are: the usual flow, or, only as a check of the code before ticket
   *     23, all of them
   */
  public record Result(int fitRows, int validationRows, double projectMae,
      double constantMae, List<Model> models, TailCut.Choice cut, TailCut.Reading scored) {}

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
   * The first line of every parameters file this step writes; one without the tail cut, written
   * before ticket 23, has another.
   */
  public static final String HEADER = "# the tail cut's k and fences, Elastic Net alpha and "
      + "lambda, the similarity medians' n, n and k, and each random forest's column share and "
      + "leaf, chosen on the usual flow of the inner validation only, the target in whole "
      + "business days on the issues of at least one, for nine models with the retrospective one "
      + "and the own and retrospective ones before ticket 19; the outer test was not read";

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
   * labels: nothing else of an outer test row is read.
   */
  public static RegistrationInProgress.Sizes sizes(final List<RowCache.Entry> entries) {
    int train = 0;
    int test = 0;
    int fit = 0;
    int validation = 0;
    for (final RowCache.Entry e : entries) {
      if (TRAIN.equals(e.outer(P))) {
        train++;
        fit += TRAIN.equals(e.inner(P)) ? 1 : 0;
        validation += TEST.equals(e.inner(P)) ? 1 : 0;
      } else if (TEST.equals(e.outer(P))) {
        test++;
      }
    }
    return new RegistrationInProgress.Sizes(train, test, fit, validation);
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
    final List<String> out = new ArrayList<>();
    out.add(HEADER);
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
            + "ticket 23 has none: run issues-in-progress-similarity-elastic-net again");
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
   * Chooses the tail cut's k ({@link TailCut}, ticket 23), then, by the registered sweep ({@link
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
   * 18/10/2026). For each k of {@link TailCut#GRID}, {@link #OWN_WITH_SIMILARITY} is chosen as
   * below on the inner fitting rows at or below its fence, and its chosen point's error is the
   * k's; the lowest wins. <b>Ties</b>: the grid is read from the larger k down and a k is kept
   * only when its error is strictly lower, the sweep's own rule, so of errors exactly equal the
   * larger k, the smaller cut, wins. Every model and forest is then chosen on the rows of that k,
   * the own specification with similarity's choice there reused as it is; the references learn
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
    return select(entries, TailCut.GRID, TailCut.Reading.USUAL_FLOW, threads);
  }

  /**
   * The choice of {@link #run(List, int)} with the k given, no other k swept, every choice scored
   * on the validation issues {@code scored} reads; at {@link TailCut#NO_CUT} and {@link
   * TailCut.Reading#ALL}, the choice before ticket 23, bit for bit.
   */
  public static Result fixed(final List<RowCache.Entry> entries, final double k,
      final TailCut.Reading scored, final int threads) {
    if (!TailCut.GRID.contains(k)) {
      throw new IllegalArgumentException("k " + k + " is not one of the grid " + TailCut.GRID);
    }
    return select(entries, List.of(k), scored, threads);
  }

  private static Result select(final List<RowCache.Entry> entries, final List<Double> grid,
      final TailCut.Reading scored, final int threads) {
    final List<Cell> every = everyCell(Augmentation.Variant.INNER);
    final List<RowCache.Entry> training =
        BusinessDays.counted(entries.stream().filter(e -> TRAIN.equals(e.outer(P))).toList());
    final Fitting.Sides whole = flagged(Fitting.sides(training, Fitting.Mode.INNER, P), every);
    // both fences on the inner fitting set as it is, before any cut
    final double usualFence = TailCut.fence(whole.fitUnits(), TailCut.USUAL_FLOW_K);
    final Fitting.Sides sides = scored == TailCut.Reading.ALL ? whole
        : TailCut.usualFlow(whole, usualFence);

    final List<TailCut.Trial> trials = new ArrayList<>(grid.size());
    final Map<Double, Model> own = new HashMap<>();
    for (final double k : grid) {
      final double fence = TailCut.fence(sides.fitUnits(), k);
      final Fitting.Sides cut = TailCut.cut(sides, fence);
      final Model m = choose(List.of(OWN_WITH_SIMILARITY), every, cut, threads).get(0);
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
        MODELS.stream().filter(name -> !name.equals(OWN_WITH_SIMILARITY)).toList();
    final List<Model> rest = choose(others, every, cut, threads);
    final List<Model> chosen = new ArrayList<>(MODELS.size());
    for (final String name : MODELS) {
      chosen.add(name.equals(OWN_WITH_SIMILARITY) ? own.get(best.k())
          : rest.get(others.indexOf(name)));
    }

    final List<Callable<Forest.Fitted>> trees = new ArrayList<>(chosen.size());
    for (final Model m : chosen) {
      trees.add(() -> Forest.sweep(m.fitted().cell(), cut, projectMae, constantMae));
    }
    final List<Forest.Fitted> forests = Parallel.map(threads, trees);
    final List<Model> out = new ArrayList<>(chosen.size());
    for (int m = 0; m < chosen.size(); m++) {
      final Model x = chosen.get(m);
      out.add(new Model(x.cell(), x.combination(), x.fitted(), x.removed(), x.combinations(),
          forests.get(m)));
    }
    return new Result(cut.fitUnits().size(), observed.length, projectMae, constantMae,
        List.copyOf(out), new TailCut.Choice(best.k(), best.fence(), usualFence,
            List.copyOf(trials)), scored);
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
   * Both sides with a missing indicator beside every column of {@code cells} the registered matrix
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
   * zero where it is observed, as the registered matrix stores beside its own columns; the value
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
   * Combination#all}'s order, the nine models of each in theirs. What the selection may read.
   */
  public static List<Cell> everyCell(final Augmentation.Variant variant) {
    final List<Cell> out = new ArrayList<>();
    for (final Combination c : Combination.all()) {
      out.addAll(cells(c, variant));
    }
    return List.copyOf(out);
  }

  /**
   * The nine models in their order, reading the split-dependent columns of the given variant,
   * each with the similarity medians at its own combination.
   *
   * @param combinations per model of {@link #WITH_SIMILARITY}, the values it reads the medians
   *     at; exactly those models, or the call refuses
   */
  public static List<Cell> cells(final Map<String, Combination> combinations,
      final Augmentation.Variant variant) {
    if (!combinations.keySet().equals(Set.copyOf(WITH_SIMILARITY))) {
      throw new IllegalArgumentException("the models with the similarity medians are "
          + WITH_SIMILARITY + "; Jaccard n, embedding n and cluster k were given for "
          + combinations.keySet());
    }
    final List<Cell> out = new ArrayList<>(MODELS.size());
    final List<Cell> without = cells(combinations.get(WITH_SIMILARITY.get(0)), variant);
    for (int m = 0; m < MODELS.size(); m++) {
      final Combination c = combinations.get(MODELS.get(m));
      out.add(c == null ? without.get(m) : cells(c, variant).get(m));
    }
    return List.copyOf(out);
  }

  /**
   * The nine models in their order, reading the split-dependent columns of the given variant,
   * the six with the similarity medians at {@code c}; the other three do not depend on it.
   */
  public static List<Cell> cells(final Combination c, final Augmentation.Variant variant) {
    final List<Attribute> without = new ArrayList<>();
    without.add(Attributes.byName("A01_desc_length"));
    without.add(Attributes.byName("A03_team_size"));
    for (final String team : Extractor.TEAM_REVISED) {
      without.add(revised(team));
    }
    without.add(revised(Augmentation.reporter(variant)));
    for (final String executor : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
        "A13_exec_open_load")) {
      without.add(Attributes.byName(executor));
    }
    final List<Attribute> similarity = List.of(
        revised(Augmentation.jaccard(c.jaccardN(), variant)),
        revised(Augmentation.embedding(c.embeddingN(), variant)),
        revised(Augmentation.cluster(c.clusterK(), variant)));
    // the own specification with similarity as before ticket 19: the ten, then the three medians
    final List<Attribute> beforePressure = new ArrayList<>(without);
    beforePressure.addAll(similarity);
    without.addAll(pressure(variant));
    final List<Attribute> with = new ArrayList<>(without);
    with.addAll(similarity);

    final List<Attribute> union = new ArrayList<>();
    for (final Attribute a : Attributes.ofInProgress(Family.UNION, Scenario.C1)) {
      if (a.family() == Family.ALZUBAIDI || a.family() == Family.KIKAS) {
        union.add(a);
      }
    }
    union.addAll(with);

    final List<Attribute> retrospective = new ArrayList<>(with);
    retrospective.addAll(retrospective());
    // the retrospective model as before ticket 19: the old start model, then the eight of ticket 15
    final List<Attribute> retrospectiveBeforePressure = new ArrayList<>(beforePressure);
    retrospective().stream().filter(a -> !Retrospective.LINKS_AND_RELEASES.contains(a.name()))
        .forEach(retrospectiveBeforePressure::add);

    return List.of(
        new Cell(MODELS.get(0), Family.OWN, Scenario.C1, without),
        new Cell(MODELS.get(1), Family.OWN, Scenario.C1, similarity),
        new Cell(MODELS.get(2), Family.OWN, Scenario.C1, with),
        registered(MODELS.get(3), "ALZUBAIDI / C1"),
        registered(MODELS.get(4), "KIKAS / C1"),
        new Cell(MODELS.get(5), Family.UNION, Scenario.C1, union),
        new Cell(RETROSPECTIVE, Family.OWN, Scenario.C2, retrospective),
        new Cell(BEFORE_PRESSURE, Family.OWN, Scenario.C1, beforePressure),
        new Cell(RETROSPECTIVE_BEFORE_PRESSURE, Family.OWN, Scenario.C2,
            retrospectiveBeforePressure));
  }

  /**
   * The ten attributes of the retrospective scenario ({@link Retrospective}), in its order: the
   * counts and volumes {@code log1p}, the ratio and the means raw, as the registered columns they
   * stand beside ({@code A15} to {@code A17}, {@code D07}, {@code D08}, {@code K03} to {@code
   * K05}); a missing indicator beside the five that can be missing. Ticket 19's two follow: the
   * blocking-link wait {@code log1p} and urgency raw, being signed, each with a missing indicator.
   * Split-independent: the same in both variants.
   */
  static List<Attribute> retrospective() {
    final List<Attribute> out = new ArrayList<>(Retrospective.COLUMNS.size());
    for (final String name : Retrospective.COLUMNS) {
      out.add(new Attribute(name, name, Family.OWN,
          Retrospective.CONTENT.contains(name) ? Scenario.Block.CONTENT
              : Scenario.Block.EXPOSURE,
          Construct.Kind.NUMERIC,
          Retrospective.COUNTS.contains(name) ? Construct.Transform.LOG1P
              : Construct.Transform.NONE,
          Retrospective.CONTENT.contains(name)
              || Retrospective.LINKS_AND_RELEASES.contains(name), true));
    }
    return List.copyOf(out);
  }

  /**
   * The start model's three attributes of ticket 19 ({@link Pressure}), in its order: the fix
   * version flag a binary never missing; the release cycle raw, being signed, and the Blocker pace
   * {@code log1p}, each with a missing indicator. No block. The flag and the cycle are known at the
   * start and split-independent; the Blocker pace reads the training side's durations (ticket 21),
   * so it is read in the given variant, as the similarity medians are.
   */
  static List<Attribute> pressure(final Augmentation.Variant variant) {
    return List.of(
        new Attribute(Pressure.FIX_VERSION, Pressure.FIX_VERSION, Family.OWN, null,
            Construct.Kind.BINARY, Construct.Transform.NONE, false, true),
        new Attribute(Pressure.RELEASE_CYCLE, Pressure.RELEASE_CYCLE, Family.OWN, null,
            Construct.Kind.NUMERIC, Construct.Transform.NONE, true, true),
        revised(Pressure.blockerPace(variant)));
  }

  private static Cell registered(final String name, final String label) {
    final Cell cell = Cell.byLabel(label, P);
    return new Cell(name, cell.family(), cell.scenario(), cell.attributes());
  }

  /** A column of the new chain: numeric, {@code log1p}, with a missing indicator. */
  private static Attribute revised(final String name) {
    return new Attribute(name, name, Family.OWN, null, Construct.Kind.NUMERIC,
        Construct.Transform.LOG1P, true, true);
  }
}
