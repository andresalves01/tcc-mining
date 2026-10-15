package app.issues;

import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.References;
import app.study.Construct;
import app.study.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Elastic Net models of the description-similarity chain, and the choice of each one's alpha
 * and lambda on the inner validation only, by the registered chain's own sweep.
 *
 * <p><b>Models.</b> Seven, each a {@link Cell}. Three are the registered in-progress cells read
 * as they are: the previous own specification at the start ({@code OWN / C1}), Al-Zubaidi at the
 * start ({@code ALZUBAIDI / C1}) and Kikas at the start ({@code KIKAS / C1}). The revised twelve
 * are description length; the team's median and spread of lines of code and of cyclomatic
 * complexity; the corrected reporter median; the executor's lines of code, cyclomatic complexity
 * and open workload, the registered {@code A09}, {@code A10} and {@code A13}; and the Jaccard,
 * embedding and cluster medians at the values the direct predictors chose. The revised nine are
 * the same without the three medians; the similarity block alone is the three medians; the union
 * is the literature families as the registered union reads them, Kikas's body length entering
 * once as Al-Zubaidi's word counts, followed by the revised twelve.
 *
 * <p><b>Columns.</b> The reporter median and the three similarity medians depend on the split;
 * each model reads them in the variant of its mode, {@code _inner} here. The three similarity
 * medians enter as {@code log1p} before standardisation, as the spec says; the reporter median and
 * the four team columns take the transform of the registered columns they revise ({@code D05};
 * {@code A04} to {@code A07}), which is also {@code log1p}. Every new column carries a missing
 * indicator: a missing value is handed to the registered preparation as missing, filled there by
 * the fitting set's median. A declared column missing on every inner fitting row is removed from
 * the model before the sweep, with the reason recorded, never filled from the validation rows.
 * The registered preparation, sweep and grid are reused as they are.
 */
public final class ElasticNetSelection {

  /** The three similarity methods, as the direct predictors' parameters file names them. */
  public static final List<String> METHODS =
      List.of("jaccard_median", "embedding_median", "cluster_median");

  private static final Population P = Population.IN_PROGRESS;

  private static final String TRAIN = "TRAIN";

  /** The suffix of a missing indicator, as the registered matrix names its own. */
  private static final String ABSENT = "_ausente";

  /**
   * A column a model was declared with and does not read.
   *
   * @param reason why, in words for the report
   */
  public record Removed(String column, String reason) {}

  /**
   * One model's choice.
   *
   * @param cell the model as declared, every column in it
   * @param fitted the sweep's result on the columns kept: the chosen alpha and lambda, the inner
   *     validation error, and the design matrix's own drops of constant columns
   * @param removed the declared columns left out before the sweep
   */
  public record Model(Cell cell, Fitting.Fitted fitted, List<Removed> removed) {}

  /**
   * @param projectMaeHours the project median's error on the inner validation, the sweep's
   *     baseline as in the registered grid
   * @param constantMaeHours the inner fitting median's error there
   */
  public record Result(int fitRows, int validationRows, double projectMaeHours,
      double constantMaeHours, List<Model> models) {}

  /** A model's chosen mixture and penalty, as the test refits it. */
  public record Choice(double alpha, double lambda) {}

  /** The first line of every parameters file this step writes. */
  public static final String HEADER = "# Elastic Net alpha and lambda chosen on the inner "
      + "validation only; the outer test was not read";

  /** The step of the Elastic Net choices in the parameters file. */
  public static final String STEP = "elastic-net";

  /** The line naming the direct predictors' file the choices were made with. */
  private static final String INPUTS = "inputs";

  private static final String SIMILARITY_SHA = "similarity_parameters_sha256";

  private static final String MATRIX_SHA = "matrix_sha256";

  private static final String ALPHA = "alpha";

  private static final String LAMBDA = "lambda";

  private ElasticNetSelection() {}

  /**
   * The parameters file, in the direct predictors' four fields ({@link Selection#read} reads it):
   * a commented header; two lines with the SHA-256 of the matrix the choices were made on and of
   * the direct predictors' parameters file whose candidate values the models read; then per model
   * an alpha line and a lambda line, each value as Java prints a double, which reads back to the
   * same number.
   */
  public static List<String> parameters(final Result r, final String matrixSha,
      final String similaritySha) {
    final List<String> out = new ArrayList<>();
    out.add(HEADER);
    out.add("# step\tname\tparameter\tvalue");
    out.add(String.join("\t", STEP, INPUTS, MATRIX_SHA, matrixSha));
    out.add(String.join("\t", STEP, INPUTS, SIMILARITY_SHA, similaritySha));
    for (final Model m : r.models()) {
      final String name = m.cell().label();
      out.add(String.join("\t", STEP, name, ALPHA, Double.toString(m.fitted().alpha())));
      out.add(String.join("\t", STEP, name, LAMBDA, Double.toString(m.fitted().lambda())));
    }
    return out;
  }

  /** Per model, its alpha and lambda; a model missing either refuses. */
  public static Map<String, Choice> choices(final List<Selection.Parameter> parameters) {
    final Map<String, Double> alphas = new LinkedHashMap<>();
    final Map<String, Double> lambdas = new HashMap<>();
    for (final Selection.Parameter p : parameters) {
      if (!p.step().equals(STEP) || p.name().equals(INPUTS)) {
        continue;
      }
      switch (p.parameter()) {
        case ALPHA -> alphas.put(p.name(), Double.parseDouble(p.value()));
        case LAMBDA -> lambdas.put(p.name(), Double.parseDouble(p.value()));
        default -> throw new IllegalArgumentException("no parameter " + p.parameter() + " in "
            + p);
      }
    }
    if (!alphas.keySet().equals(lambdas.keySet())) {
      throw new IllegalArgumentException("models with alpha " + alphas.keySet()
          + " and with lambda " + lambdas.keySet() + " differ");
    }
    final Map<String, Choice> out = new LinkedHashMap<>();
    alphas.forEach((name, alpha) -> out.put(name, new Choice(alpha, lambdas.get(name))));
    return out;
  }

  /** The SHA-256 of the direct predictors' file the choices were made with; null if not given. */
  public static String similaritySha(final List<Selection.Parameter> parameters) {
    return input(parameters, SIMILARITY_SHA);
  }

  /** The SHA-256 of the matrix the choices were made on; null if not given. */
  public static String matrixSha(final List<Selection.Parameter> parameters) {
    return input(parameters, MATRIX_SHA);
  }

  private static String input(final List<Selection.Parameter> parameters, final String what) {
    return parameters.stream().filter(p -> p.step().equals(STEP) && p.name().equals(INPUTS)
        && p.parameter().equals(what)).map(Selection.Parameter::value).findFirst().orElse(null);
  }

  /**
   * Chooses alpha and lambda for every model of {@link #cells} in the inner variant, by the
   * registered sweep ({@link Fitting#sweep}) on the inner fitting rows, scored on the inner
   * validation. The outer test rows are dropped before anything is read from them: only an
   * entry inside the outer training set goes on, and of those {@link Fitting.Mode#INNER} reads
   * the inner fitting and validation rows alone.
   *
   * @param chosen the direct predictors' choices, {@link Selection#chosen}
   */
  public static Result run(final List<RowCache.Entry> entries,
      final Map<String, Integer> chosen) {
    final List<Cell> cells = cells(chosen, Augmentation.Variant.INNER);
    final List<RowCache.Entry> training =
        entries.stream().filter(e -> TRAIN.equals(e.outer(P))).toList();
    final Fitting.Sides sides = flagged(Fitting.sides(training, Fitting.Mode.INNER, P), cells);

    final References refs = References.learnedOn(sides.fitUnits());
    final double[] observed = sides.observed();
    final double constantMae = Fitting.mae(refs.constant(observed.length), observed);
    final double projectMae = Fitting.mae(refs.projectHours(sides.scoreUnits()), observed);

    final List<Model> out = new ArrayList<>();
    for (final Cell cell : cells) {
      final Kept kept = kept(cell, sides.fitRows(), "inner fitting");
      out.add(new Model(cell, Fitting.sweep(kept.cell(), sides, projectMae, constantMae),
          kept.removed()));
    }
    return new Result(sides.fitUnits().size(), observed.length, projectMae, constantMae,
        List.copyOf(out));
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
   * The seven models in their order, reading the split-dependent columns of the given variant.
   *
   * @param chosen per method of {@link #METHODS}, the candidate value the direct predictors chose
   */
  public static List<Cell> cells(final Map<String, Integer> chosen,
      final Augmentation.Variant variant) {
    for (final String method : METHODS) {
      if (!chosen.containsKey(method)) {
        throw new IllegalArgumentException("no chosen value for " + method + "; the direct "
            + "predictors' parameters are " + chosen);
      }
    }
    final List<Attribute> nine = new ArrayList<>();
    nine.add(Attributes.byName("A01_desc_length"));
    for (final String team : Extractor.TEAM_REVISED) {
      nine.add(revised(team));
    }
    nine.add(revised(Augmentation.reporter(variant)));
    for (final String executor : List.of("A09_exec_loc_tot", "A10_exec_mccc_tot",
        "A13_exec_open_load")) {
      nine.add(Attributes.byName(executor));
    }
    final List<Attribute> similarity = List.of(
        revised(Augmentation.jaccard(chosen.get(METHODS.get(0)), variant)),
        revised(Augmentation.embedding(chosen.get(METHODS.get(1)), variant)),
        revised(Augmentation.cluster(chosen.get(METHODS.get(2)), variant)));
    final List<Attribute> twelve = new ArrayList<>(nine);
    twelve.addAll(similarity);

    final List<Attribute> union = new ArrayList<>();
    for (final Attribute a : Attributes.ofInProgress(Family.UNION, Scenario.C1)) {
      if (a.family() == Family.ALZUBAIDI || a.family() == Family.KIKAS) {
        union.add(a);
      }
    }
    union.addAll(twelve);

    return List.of(
        registered("previous_own_start", "OWN / C1"),
        new Cell("revised_nine", Family.OWN, Scenario.C1, nine),
        new Cell("similarity_alone", Family.OWN, Scenario.C1, similarity),
        new Cell("revised_twelve", Family.OWN, Scenario.C1, twelve),
        registered("alzubaidi_start", "ALZUBAIDI / C1"),
        registered("kikas_start", "KIKAS / C1"),
        new Cell("union_revised", Family.UNION, Scenario.C1, union));
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
