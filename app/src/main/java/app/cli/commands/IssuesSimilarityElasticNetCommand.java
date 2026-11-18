package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.AnalogyEstimate;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.ProjectSplit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
import app.issues.port.ResultStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * The study's selection step, inner mode only: for the six attribute sets of {@link
 * ElasticNetSelection#MODELS}, each Elastic Net model's alpha and lambda, by the declared sweep on
 * the inner validation, and, for the three models reading the
 * similarity medians, the Jaccard n, embedding n and cluster k they read, the combination of the
 * 27 whose sweep errs least (see {@link ElasticNetSelection}); and beside each model, as a
 * robustness check, a random forest's column share and leaf, swept on the same rows and columns.
 * At the start: the own start model, Al-Zubaidi and Kikas. After the resolution: the
 * retrospective scenario's main reading ({@link Retrospective}), whose columns {@code
 * issues-in-progress-retrospective} adds to the matrix, on the own start model and without the
 * comments and the participants; the same with them, a ceiling, not a competitor; and Kikas's
 * retrospective cell ({@code KIKAS / C2}).
 *
 * <p><b>Tail cut</b> (ticket 23; {@link TailCut}). Before the models, the fitting k is chosen on
 * the own start model among 1.5, 3, 5, 10 and no cut, every choice scored on the usual-flow
 * validation issues; every model, forest and reference is then chosen on the inner fitting rows
 * at or below that k's fence. The chosen k, the model it was chosen on, both fences and every
 * k's error are printed, written to the parameters file and recorded.
 *
 * <p><b>The first-commit study's temporal split</b> (its ticket 06). Under {@code
 * SPLIT_RULE=temporal} every forest is swept with {@link Forest#LIBRARY_TREES} trees, and the
 * analogy estimate, the fifteenth model, is chosen on the same usual-flow validation issues
 * ({@link ElasticNetSelection#run(List, int, Population, SplitRule)}); the parameters file then
 * names the rule in its header and gives the tree count and the chosen column, which the record
 * states with every similarity median's error. Under the random split the file and the record
 * are as before it.
 *
 * <p>The target is whole business days, on the issues of at least one ({@link BusinessDays}):
 * the outer training rows are read with each one's day boundary from the matrix, any of 0
 * business days dropped -- none since ticket 21, the split being drawn over the issues of at least
 * one -- and the selection runs on the rest.
 *
 * <p>Reads MATRIX, the one {@code issues-in-progress-retrospective} wrote, which has every
 * model's columns; refuses, writing and recording nothing, unless every row's split is the one the
 * rule the run declares draws ({@link SplitRule}, setting {@code SPLIT_RULE}, random unless the
 * first-commit study's matrix is declared temporal) and the matrix has the day boundary and
 * every column the models declare at every combination. Of the outer test only the ids, the
 * projects and the split
 * labels are read, to count it and to check the split. Writes the choices to
 * ELASTIC_NET_PARAMETERS, never over a file holding
 * anything but this step's own lines under its own header, a check made before anything is fitted
 * -- and records the run, with the SHA-256 of the matrix and of the parameters file, in ISSUES_DB.
 */
public final class IssuesSimilarityElasticNetCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-elastic-net";

  /** What the forest is in this study, stated in both steps' run records. */
  static final String FOREST_ROLE = "the random forest beside each Elastic Net is a robustness "
      + "check, not a new contribution: it answers whether the small gains come from relations "
      + "the linear model cannot capture; added after an inner-validation prototype and before "
      + "the outer test was opened";

  /** Why the temporal split's forests have their count of trees, for the record and reports. */
  static final String TEMPORAL_TREES = "the temporal split's forests have the Smile library's "
      + "default of " + Forest.LIBRARY_TREES + " trees, decided by the author on 02/11/2026 "
      + "before any temporal result, in place of the " + Forest.TREES + " inherited from the "
      + "pull-request study's gradient-boosting control, which were never evaluated; the random "
      + "split keeps " + Forest.TREES;

  /** What the analogy estimate's choice is, for the output and the run record. */
  static final String ANALOGY_RULE = "analogy estimate, the fifteenth model of the temporal "
      + "split: of the " + AnalogyEstimate.CANDIDATES.size() + " similarity medians, each read in "
      + "its inner column as the estimate with no model around it, the one with the lowest MAE on "
      + "the usual-flow inner validation, the first in order on a tie; an issue without it takes "
      + "the global median of the inner fitting rows at or below the chosen k's fence; only the "
      + "chosen column is frozen";

  /** Decision 16a in the first-commit study's sets, for its run records and reports. */
  static final String FIRST_COMMIT_ATTRIBUTES = "decision 16a: the first commit's lines added "
      + "plus removed in every file (FC_first_commit_lines, log1p) closes the own start model, so "
      + "both readings of the own retrospective model read it too; its share of the issue's "
      + "changed lines (FC_first_commit_share, raw) closes their retrospective columns; the "
      + "literature sets are unchanged";

  /** Where each setting is read from: the environment, then the system properties. */
  private final Function<String, String> settings;

  public IssuesSimilarityElasticNetCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /**
   * @param settings the settings by name; the tests hand their own, so a real MATRIX in the
   *     environment of whoever runs them is never read
   */
  IssuesSimilarityElasticNetCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = path("MATRIX");
    final Path parameters = path("ELASTIC_NET_PARAMETERS");
    final long began = System.nanoTime();
    if (same(matrix, parameters)) {
      throw new CommandFailedException(List.of("  REFUSED: ELASTIC_NET_PARAMETERS is " + matrix
          + ", which this step never writes"));
    }

    final Population study;
    try {
      study = RowCache.splitPopulation(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), study);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    final boolean firstCommit = study == Population.FIRST_COMMIT;
    // before any row is read, choose the run store of the declared design
    final ResultStore store = IssuesInProgressExtractCommand.runStore(context, study, rule);

    final String matrixSha;
    final List<RowCache.Entry> entries;
    final List<RowCache.Entry> training;
    final List<String> columns;
    try {
      refuseForeign(parameters);
      matrixSha = Sha256.of(matrix);
      // every row by its id, project and split verdicts alone; the outer training rows whole
      entries = RowCache.splits(matrix);
      training = RowCache.training(matrix, study);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + matrix + " or "
          + parameters + ": " + e));
    }
    System.out.printf("%n  DURATION FROM %s -- DESCRIPTION SIMILARITY -- ELASTIC NET "
        + "ON THE INNER VALIDATION%n", firstCommit ? "THE FIRST COMMIT" : "IN PROGRESS");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());

    final List<String> split = rule.differences(entries, study);
    if (!split.isEmpty()) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + split.get(0)));
    }
    final ProjectSplit.Sizes s = ElasticNetSelection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    final ElasticNetSelection.Result r;
    final BusinessDays.Population population;
    try {
      final List<String> missing = missing(
          ElasticNetSelection.everyCell(Augmentation.Variant.INNER, study), columns);
      if (!missing.isEmpty()) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + matrix + " has no column " + missing + retrospectiveHint(missing)));
      }
      final String unwritten = BusinessDays.unwritten(columns, study);
      if (unwritten != null) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + matrix + ": " + unwritten));
      }
      // the outer test was left behind on its split field, before any outcome was read
      population = BusinessDays.population(training, Augmentation.Variant.INNER);
      r = ElasticNetSelection.run(population.entries(),
          app.model.Parallel.threads(app.model.Parallel.ELASTIC_NET_THREADS), study, rule);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }

    System.out.printf("  target: %s; %s%n", BusinessDays.target(training),
        BusinessDays.HOLIDAYS);
    System.out.printf("  population: %s%n", BusinessDays.populationRule(training));
    System.out.printf("  %s: %s%n", ElasticNetSelection.RETROSPECTIVE,
        Retrospective.scenario(study));
    System.out.printf("  %s: %s%n", ElasticNetSelection.RETROSPECTIVE,
        ElasticNetSelection.RETROSPECTIVE_ROLE);
    System.out.printf("  %s: %s%n", ElasticNetSelection.WITH_DISCUSSION,
        ElasticNetSelection.WITH_DISCUSSION_ROLE);
    System.out.printf("  %s: %s%n", ElasticNetSelection.KIKAS_RETROSPECTIVE,
        ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE);
    System.out.printf("  issues of at least one business day: inner fitting %,d of %,d, inner "
        + "validation %,d of %,d, outer training %,d of %,d%n", population.kept().innerFit(),
        population.all().innerFit(), population.kept().innerSelection(),
        population.all().innerSelection(), population.kept().train(), population.all().train());
    final TailCut.Choice cut = r.cut();
    System.out.printf("  tail cut: %s; %s%n", TailCut.RULE, TailCut.QUANTILES);
    System.out.printf("  %s%n", ElasticNetSelection.TAIL_CUT_MODEL);
    System.out.printf("  usual-flow fence (k %s) %.3f bd on the inner fitting set; each k's "
        + "%s on the usual-flow validation issues (k: fence bd, fitting rows, MAE bd)%n",
        TailCut.label(TailCut.USUAL_FLOW_K), cut.usualFence(),
        ElasticNetSelection.BEFORE_PRESSURE);
    for (final TailCut.Trial t : cut.grid()) {
      System.out.printf("    %-5s %10.3f %,8d %.3f%s%n", TailCut.label(t.k()), t.fence(),
          t.fitRows(), t.mae(), t.k() == cut.k() ? "  chosen" : "");
    }
    System.out.printf("  inner fitting %,d rows at or below the fence of k %s (%.3f bd), the same "
        + "for every model, forest and reference; every choice scored on the %,d usual-flow "
        + "inner validation rows of %,d; the outer test is not read%n", r.fitRows(),
        TailCut.label(cut.k()), cut.fence(), r.validationRows(),
        population.kept().innerSelection());
    System.out.printf("  Elastic Net grid: alpha %s x %d lambdas, anchor 2*max|X'r|/alpha; "
        + "references on the usual-flow inner validation: project median %.3f bd, fitting "
        + "median %.3f bd "
        + "(MAE, business days)%n", java.util.Arrays.toString(Fitting.ALPHAS),
        Fitting.LAMBDA_STEPS, r.projectMae(), r.constantMae());
    System.out.printf("  the models with the similarity medians sweep each of %d combinations of "
        + "Jaccard n %s, embedding n %s and cluster k %s; exact ties go to the smaller values, "
        + "Jaccard n first, then embedding n, then cluster k%n",
        ElasticNetSelection.Combination.all().size(), Augmentation.JACCARD_N,
        Augmentation.EMBEDDING_N, Augmentation.CLUSTER_K);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final ElasticNetSelection.Model m : r.models()) {
      final Fitting.Fitted f = m.fitted();
      System.out.printf("%n  %-22s alpha %.2f  lambda %-12.6g step %2d of %d%s; MAE %.3f bd; "
          + "%d columns, %d selected%n", m.cell().label(), f.alpha(), f.lambda(),
          f.step(), Fitting.LAMBDA_STEPS, f.step() == 0 ? " = TOP, ALL ZEROED"
              : f.step() == Fitting.LAMBDA_STEPS - 1 ? " = GRID FLOOR" : "",
          f.score().mae(), f.columns().size(), f.selected());
      if (m.combination() != null) {
        System.out.printf("    chosen: Jaccard n %d, embedding n %d, cluster k %d%n",
            m.combination().jaccardN(), m.combination().embeddingN(),
            m.combination().clusterK());
        System.out.printf("    each combination's best on the inner validation (Jaccard n, "
            + "embedding n, cluster k: MAE bd)%n");
        for (final ElasticNetSelection.Swept x : m.combinations()) {
          System.out.printf("      %2d %2d %2d: %.3f%s%n", x.combination().jaccardN(),
              x.combination().embeddingN(), x.combination().clusterK(), x.mae(),
              x.combination().equals(m.combination()) ? "  chosen" : "");
        }
      }
      for (final ElasticNetSelection.Removed removed : m.removed()) {
        System.out.printf("    removed %s: %s%n", removed.column(), removed.reason());
      }
      if (!f.dropped().isEmpty()) {
        System.out.printf("    dropped for being constant on the inner fitting rows: %s%n",
            f.dropped());
      }
      final Forest.Fitted forest = m.forest();
      System.out.printf("    random forest (robustness check): mtry %d (share %.3f), leaf %d; MAE "
          + "%.3f bd, Elastic Net minus forest %+.3f bd%n", forest.mtry(), forest.share(),
          forest.nodeSize(), forest.score().mae(),
          f.score().mae() - forest.score().mae());
      final StringBuilder grid = new StringBuilder("      grid (mtry/leaf: MAE bd)");
      for (final Forest.Trial t : forest.trials()) {
        grid.append(String.format("  %d/%d %.3f", t.mtry(), t.nodeSize(), t.mae()));
      }
      System.out.println(grid);
      rows.add(row(m));
    }
    final boolean temporal = rule == SplitRule.TEMPORAL;
    if (temporal) {
      System.out.printf("%n  random forests: %d trees each (%s)%n", r.trees(), TEMPORAL_TREES);
      System.out.printf(java.util.Locale.ROOT, "  %s; fallback %.3f bd (similarity median: MAE bd, "
          + "issues given the fallback, of %,d)%n", ANALOGY_RULE, r.analogyFallback(),
          r.validationRows());
      for (final AnalogyEstimate.Candidate c : r.analogy().candidates()) {
        System.out.printf(java.util.Locale.ROOT, "    %-20s %.3f %,6d%s%n", c.name(), c.mae(),
            c.fallbacks(), c.name().equals(r.analogy().chosen()) ? "  chosen" : "");
      }
    }

    final String parametersSha;
    try {
      refuseForeign(parameters);
      final Path partial = parameters.resolveSibling(parameters.getFileName() + ".partial");
      Files.write(partial, ElasticNetSelection.parameters(r, matrixSha, study, rule));
      Files.move(partial, parameters, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      parametersSha = Sha256.of(parameters);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + parameters + ": " + e));
    }
    System.out.printf("%n  parameters written to %s  sha256 %s%n", parameters, parametersSha);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("mode", "inner");
    run.put("outer_test", "not read");
    run.put("split_rule", rule.rule());
    if (firstCommit && rule == SplitRule.TEMPORAL) {
      run.put(SplitRule.DESIGN_FIELD, rule.setting());
    }
    run.putAll(statements(study));
    run.put("target", BusinessDays.target(training));
    run.put("population", BusinessDays.populationRule(training));
    if (firstCommit) {
      run.put("study", study.name());
      run.put("first_commit_attributes", FIRST_COMMIT_ATTRIBUTES);
    }
    run.put("population_rows", populationRows(population));
    run.put("models", ElasticNetSelection.MODELS);
    run.put("matrix_sha256", matrixSha);
    run.put("parameters_sha256", parametersSha);
    run.put("jaccard_n", Augmentation.JACCARD_N);
    run.put("embedding_n", Augmentation.EMBEDDING_N);
    run.put("cluster_k", Augmentation.CLUSTER_K);
    run.put("ties", "within a sweep the earlier point; across combinations the smaller Jaccard n, "
        + "then embedding n, then cluster k");
    run.put("forest_grid", forestGrid(r.trees()));
    run.put("forest_ties", "the first point of the grid in its order: the smaller column share, "
        + "then the smaller leaf");
    run.put("tail_cut", tailCut(cut));
    run.put("fit_rows", r.fitRows());
    run.put("validation_rows", r.validationRows());
    // every inner validation issue, as validation_rows counted before ticket 23
    run.put("validation_rows_all", population.kept().innerSelection());
    run.put("validation_scored", r.scored().description());
    run.put("project_mae_business_days", r.projectMae());
    run.put("constant_mae_business_days", r.constantMae());
    if (temporal) {
      // the temporal split's additions; the random split's record stays as it was
      run.put("forest_trees", r.trees());
      run.put("forest_trees_rule", TEMPORAL_TREES);
      run.put("analogy_estimate", analogyRecord(r));
    }
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        store.record(run, rows));
  }

  /**
   * Refuses when {@code parameters} exists and is not a file of this step's own choices: it may
   * belong to another step, or have an earlier format or another model set, and is never
   * overwritten.
   */
  private static void refuseForeign(final Path parameters)
      throws IOException, CommandFailedException {
    if (Files.exists(parameters) && !onlyThisStep(Files.readAllLines(parameters))) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + parameters + " exists and is not a file of this step's choices in its current "
          + "format, for the models " + ElasticNetSelection.MODELS + "; it may be another "
          + "step's, or this step's from an earlier format, for other models or without the "
          + "tail cut or the forests, and is never overwritten"));
    }
  }

  /** One model as a document: its choice, what it read, and what it left out and why. */
  static Map<String, Object> row(final ElasticNetSelection.Model m) {
    final Fitting.Fitted f = m.fitted();
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("model", m.cell().label());
    row.put("declared", m.cell().attributes().stream().map(Attribute::name).toList());
    if (m.combination() != null) {
      row.put("jaccard_n", m.combination().jaccardN());
      row.put("embedding_n", m.combination().embeddingN());
      row.put("cluster_k", m.combination().clusterK());
      final List<Map<String, Object>> each = new ArrayList<>();
      for (final ElasticNetSelection.Swept x : m.combinations()) {
        final Map<String, Object> one = new LinkedHashMap<>();
        one.put("jaccard_n", x.combination().jaccardN());
        one.put("embedding_n", x.combination().embeddingN());
        one.put("cluster_k", x.combination().clusterK());
        one.put("alpha", x.alpha());
        one.put("lambda", x.lambda());
        one.put("mae_business_days", x.mae());
        each.add(one);
      }
      row.put("combinations", each);
    }
    row.put("alpha", f.alpha());
    row.put("lambda", f.lambda());
    row.put("step", f.step());
    row.put("mae_business_days", f.score().mae());
    row.put("columns", f.columns());
    row.put("selected", f.selected());
    final List<Map<String, Object>> removed = new ArrayList<>();
    for (final ElasticNetSelection.Removed x : m.removed()) {
      final Map<String, Object> one = new LinkedHashMap<>();
      one.put("column", x.column());
      one.put("reason", x.reason());
      removed.add(one);
    }
    row.put("removed", removed);
    row.put("dropped_constant", f.dropped());
    final Forest.Fitted forest = m.forest();
    final Map<String, Object> trees = new LinkedHashMap<>();
    trees.put("mtry_share", forest.share());
    trees.put("mtry", forest.mtry());
    trees.put("node_size", forest.nodeSize());
    trees.put("mae_business_days", forest.score().mae());
    trees.put("columns", forest.columns());
    final List<Map<String, Object>> grid = new ArrayList<>();
    for (final Forest.Trial t : forest.trials()) {
      final Map<String, Object> one = new LinkedHashMap<>();
      one.put("mtry_share", t.share());
      one.put("mtry", t.mtry());
      one.put("node_size", t.nodeSize());
      one.put("mae_business_days", t.mae());
      grid.add(one);
    }
    trees.put("grid", grid);
    row.put("forest", trees);
    return row;
  }

  /**
   * What the run record states about the target, the population, the forest and the
   * retrospective scenario.
   */
  static Map<String, Object> statements() {
    return statements(Population.IN_PROGRESS);
  }

  /** {@link #statements()} with the retrospective window of {@code population}. */
  static Map<String, Object> statements(final Population population) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("target", BusinessDays.TARGET);
    out.put("population", BusinessDays.POPULATION);
    out.put("holidays", BusinessDays.HOLIDAYS);
    out.put("forest_role", FOREST_ROLE);
    out.put("retrospective_scenario", Retrospective.scenario(population));
    out.put("own_retrospective", ElasticNetSelection.RETROSPECTIVE_ROLE);
    out.put("own_retrospective_with_discussion", ElasticNetSelection.WITH_DISCUSSION_ROLE);
    out.put("kikas_retrospective", ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE);
    return out;
  }

  /**
   * The rows of each split inside the outer training before and after the issues of 0 business
   * days are dropped, for the run record; the outer test is not counted here.
   */
  static Map<String, Object> populationRows(final BusinessDays.Population p) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("inner_fitting", List.of(p.kept().innerFit(), p.all().innerFit()));
    out.put("inner_validation", List.of(p.kept().innerSelection(), p.all().innerSelection()));
    out.put("outer_training", List.of(p.kept().train(), p.all().train()));
    return out;
  }

  /**
   * The tail cut as a document: its rule, the model the k is chosen on, the chosen k, both fences
   * and every k swept.
   */
  static Map<String, Object> tailCut(final TailCut.Choice cut) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", TailCut.RULE);
    out.put("quantiles", TailCut.QUANTILES);
    out.put("chosen_on", ElasticNetSelection.BEFORE_PRESSURE);
    out.put("chosen_on_rule", ElasticNetSelection.TAIL_CUT_MODEL);
    out.put("k", TailCut.label(cut.k()));
    out.put("fence_business_days", cut.fence());
    out.put("usual_flow_k", TailCut.label(TailCut.USUAL_FLOW_K));
    out.put("usual_flow_fence_business_days", cut.usualFence());
    final List<Map<String, Object>> grid = new ArrayList<>();
    for (final TailCut.Trial t : cut.grid()) {
      final Map<String, Object> one = new LinkedHashMap<>();
      one.put("k", TailCut.label(t.k()));
      one.put("fence_business_days", t.fence());
      one.put("fit_rows", t.fitRows());
      one.put("usual_flow_mae_business_days", t.mae());
      grid.add(one);
    }
    out.put("grid", grid);
    return out;
  }

  /** The analogy estimate's choice as the run record states it. */
  private static Map<String, Object> analogyRecord(final ElasticNetSelection.Result r) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", ANALOGY_RULE);
    out.put("column", r.analogy().chosen());
    out.put("fallback_business_days", r.analogyFallback());
    final List<Map<String, Object>> medians = new ArrayList<>();
    for (final AnalogyEstimate.Candidate c : r.analogy().candidates()) {
      final Map<String, Object> one = new LinkedHashMap<>();
      one.put("median", c.name());
      one.put("mae_business_days", c.mae());
      one.put("fallbacks", c.fallbacks());
      medians.add(one);
    }
    out.put("similarity_medians", medians);
    return out;
  }

  /** The forest's declared grid as a document, for the run records of both steps. */
  static Map<String, Object> forestGrid() {
    return forestGrid(Forest.TREES);
  }

  /** {@link #forestGrid()} with {@code trees} trees per forest. */
  static Map<String, Object> forestGrid(final int trees) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("trees", trees);
    out.put("max_depth", Forest.MAX_DEPTH);
    out.put("mtry_shares", Arrays.stream(Forest.MTRY_SHARES).boxed().toList());
    out.put("node_sizes", Arrays.stream(Forest.NODE_SIZES).boxed().toList());
    out.put("seed", Forest.SEED);
    return out;
  }

  /** Where the retrospective columns come from, when some of {@code missing} are theirs. */
  static String retrospectiveHint(final List<String> missing) {
    return missing.stream().anyMatch(Retrospective.COLUMNS::contains) ? "; the retrospective "
        + "columns are added by issues-in-progress-retrospective, whose RETROSPECTIVE_MATRIX is "
        + "the matrix to give" : "";
  }

  /** The columns the models declare that the matrix does not have, in the models' order. */
  static List<String> missing(final List<Cell> cells, final List<String> columns) {
    final Set<String> have = new HashSet<>(columns);
    final Set<String> out = new LinkedHashSet<>();
    for (final Cell cell : cells) {
      for (final Attribute a : cell.attributes()) {
        if (!have.contains(a.name())) {
          out.add(a.name());
        }
        // a registered column's indicator is stored beside it; the new columns' are derived
        if (Attributes.all().contains(a) && a.absenceIndicator()
            && !have.contains(a.absenceName())) {
          out.add(a.absenceName());
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * A file this step wrote and nothing appended to: its header and its own lines only, every one
   * of them an Elastic Net line for the matrix's inputs or for one of {@link
   * ElasticNetSelection#MODELS}, a forest line for one of those models or for every one (the
   * tree count, {@link ElasticNetSelection#trees}), a tail cut line (which {@link
   * ElasticNetSelection#tailCut} reads whole or refuses), or a line of the analogy estimate's
   * frozen choice (which {@link ElasticNetSelection#analogy} reads whole or refuses). The header
   * may name the split rule ({@link ElasticNetSelection#headerRule}). A file written for the eleven
   * models before the fourteen were settled has another header, as has one written before ticket
   * 23, without the tail cut, one written before ticket 13, without the forests, or one of ticket
   * 14's six models, without the retrospective one; one of the models before ticket 12 ({@code
   * previous_own_start}, {@code revised_nine}, ...) is told apart by its model names.
   */
  static boolean onlyThisStep(final List<String> lines) {
    if (lines.isEmpty() || ElasticNetSelection.headerPopulation(lines.get(0)) == null) {
      return false;
    }
    try {
      return ElasticNetSelection.read(lines).stream()
          .allMatch(p -> p.step().equals(ElasticNetSelection.STEP)
              && (p.name().equals(ElasticNetSelection.INPUTS)
                  || ElasticNetSelection.MODELS.contains(p.name()))
              || p.step().equals(ElasticNetSelection.FOREST_STEP)
                  && (ElasticNetSelection.MODELS.contains(p.name())
                      || p.name().equals(ElasticNetSelection.EVERY_MODEL))
              || p.step().equals(ElasticNetSelection.TAIL_STEP)
              || p.step().equals(ElasticNetSelection.ANALOGY_STEP));
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private String env(final String name) {
    final String value = settings.apply(name);
    return value == null || value.isBlank() ? null : value;
  }

  private Path path(final String name) throws CommandFailedException {
    final String value = env(name);
    if (value == null) {
      throw new CommandFailedException(List.of("  MATRIX and ELASTIC_NET_PARAMETERS "
          + "are required; missing " + name));
    }
    return Path.of(value);
  }
}
