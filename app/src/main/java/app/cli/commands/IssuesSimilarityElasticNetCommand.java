package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.ProjectSplit;
import app.issues.RegistrationInProgress;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import app.issues.model.Forest;
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
 * The selection step of the description-similarity chain, inner mode only: each Elastic Net
 * model's alpha and lambda, by the registered chain's sweep on the inner validation, and, for the
 * six models reading the similarity medians, the Jaccard n, embedding n and cluster k they read,
 * the combination of the 27 whose sweep errs least (see {@link ElasticNetSelection}); and beside
 * each model, as a robustness check, a random forest's column share and leaf, swept on the same
 * rows and columns. The seventh model is the retrospective scenario ({@link Retrospective}), whose
 * ten columns {@code issues-in-progress-retrospective} adds to the matrix; the ninth is the same
 * without ticket 19's five columns.
 *
 * <p><b>Tail cut</b> (ticket 23; {@link TailCut}). Before the models, the fitting k is chosen on
 * the own specification with similarity among 1.5, 3, 5, 10 and no cut, every choice scored on
 * the usual-flow validation issues; every model, forest and reference is then chosen on the inner
 * fitting rows at or below that k's fence. The chosen k, both fences and every k's error are
 * printed, written to the parameters file and recorded.
 *
 * <p>The target is whole business days, on the issues of at least one ({@link BusinessDays}):
 * the outer training rows are read with each one's day boundary from the matrix, any of 0
 * business days dropped -- none since ticket 21, the split being drawn over the issues of at least
 * one -- and the selection runs on the rest.
 *
 * <p>Reads MATRIX, the one {@code issues-in-progress-retrospective} wrote, which has every
 * model's columns; refuses, writing and recording nothing, unless every row's split is the one its
 * rule draws ({@link ProjectSplit}) and the matrix has the day boundary and every column the
 * models declare at every combination. Of the outer test only the ids, the projects and the split
 * labels are read, to count it and to check the split. Writes the choices to
 * ELASTIC_NET_PARAMETERS -- never the registered PARAMETERS, and never over a file holding
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
    final List<Path> others = new ArrayList<>(List.of(matrix));
    final String registered = env("PARAMETERS");
    if (registered != null) {
      others.add(Path.of(registered));
    }
    for (final Path other : others) {
      if (same(other, parameters)) {
        throw new CommandFailedException(List.of("  REFUSED: ELASTIC_NET_PARAMETERS is " + other
            + ", which this step never writes"));
      }
    }

    final String matrixSha;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      refuseForeign(parameters);
      matrixSha = IssuesReservedCommand.sha256(matrix);
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + matrix + " or "
          + parameters + ": " + e));
    }
    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY -- ELASTIC NET "
        + "ON THE INNER VALIDATION%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());

    final List<String> split = ProjectSplit.differences(entries);
    if (!split.isEmpty()) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + split.get(0)));
    }
    final RegistrationInProgress.Sizes s = ElasticNetSelection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    final ElasticNetSelection.Result r;
    final BusinessDays.Population population;
    try {
      final List<String> missing = missing(
          ElasticNetSelection.everyCell(Augmentation.Variant.INNER), columns);
      if (!missing.isEmpty()) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + matrix + " has no column " + missing + retrospectiveHint(missing)));
      }
      final String unwritten = BusinessDays.unwritten(columns);
      if (unwritten != null) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + matrix + ": " + unwritten));
      }
      // the outer test is left behind here, before any outcome is read
      population = BusinessDays.population(entries.stream()
          .filter(e -> "TRAIN".equals(e.outer(Population.IN_PROGRESS))).toList());
      r = ElasticNetSelection.run(population.entries());
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }

    System.out.printf("  target: %s; %s%n", BusinessDays.TARGET, BusinessDays.HOLIDAYS);
    System.out.printf("  population: %s%n", BusinessDays.POPULATION);
    System.out.printf("  %s: %s%n", ElasticNetSelection.RETROSPECTIVE,
        Retrospective.SCENARIO);
    System.out.printf("  %s: %s%n", ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE,
        ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE_ROLE);
    System.out.printf("  issues of at least one business day: inner fitting %,d of %,d, inner "
        + "validation %,d of %,d, outer training %,d of %,d%n", population.kept().innerFit(),
        population.all().innerFit(), population.kept().innerSelection(),
        population.all().innerSelection(), population.kept().train(), population.all().train());
    final TailCut.Choice cut = r.cut();
    System.out.printf("  tail cut: %s; %s%n", TailCut.RULE, TailCut.QUANTILES);
    System.out.printf("  usual-flow fence (k %s) %.3f bd on the inner fitting set; each k's "
        + "own_with_similarity on the usual-flow validation issues (k: fence bd, fitting rows, "
        + "MAE bd)%n", TailCut.label(TailCut.USUAL_FLOW_K), cut.usualFence());
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

    final String parametersSha;
    try {
      refuseForeign(parameters);
      final Path partial = parameters.resolveSibling(parameters.getFileName() + ".partial");
      Files.write(partial, ElasticNetSelection.parameters(r, matrixSha));
      Files.move(partial, parameters, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      parametersSha = IssuesReservedCommand.sha256(parameters);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + parameters + ": " + e));
    }
    System.out.printf("%n  parameters written to %s  sha256 %s%n", parameters, parametersSha);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    run.put("mode", "inner");
    run.put("outer_test", "not read");
    run.put("split_rule", ProjectSplit.RULE);
    run.putAll(statements());
    run.put("population_rows", populationRows(population));
    run.put("models", ElasticNetSelection.MODELS);
    // not in statements(), which the test's record shares
    run.put("retrospective_before_pressure",
        ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE_ROLE);
    run.put("matrix_sha256", matrixSha);
    run.put("parameters_sha256", parametersSha);
    run.put("jaccard_n", Augmentation.JACCARD_N);
    run.put("embedding_n", Augmentation.EMBEDDING_N);
    run.put("cluster_k", Augmentation.CLUSTER_K);
    run.put("ties", "within a sweep the earlier point; across combinations the smaller Jaccard n, "
        + "then embedding n, then cluster k");
    run.put("forest_grid", forestGrid());
    run.put("forest_ties", "the first point of the grid in its order: the smaller column share, "
        + "then the smaller leaf");
    run.put("tail_cut", tailCut(cut));
    run.put("fit_rows", r.fitRows());
    run.put("validation_rows", r.validationRows());
    run.put("validation_scored", r.scored().description());
    run.put("project_mae_business_days", r.projectMae());
    run.put("constant_mae_business_days", r.constantMae());
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /**
   * Refuses when {@code parameters} exists and is not a file of this step's own choices: it may
   * be the registered chain's, or one written in an earlier format or for the models before
   * ticket 12, and is never overwritten.
   */
  private static void refuseForeign(final Path parameters)
      throws IOException, CommandFailedException {
    if (Files.exists(parameters) && !onlyThisStep(Files.readAllLines(parameters))) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + parameters + " exists and is not a file of this step's choices in its current "
          + "format, for the models " + ElasticNetSelection.MODELS + "; it may be the registered "
          + "chain's, or this step's from an earlier format, without the forests or for the "
          + "models before ticket 12, and is never overwritten"));
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
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("target", BusinessDays.TARGET);
    out.put("population", BusinessDays.POPULATION);
    out.put("holidays", BusinessDays.HOLIDAYS);
    out.put("forest_role", FOREST_ROLE);
    out.put("retrospective_scenario", Retrospective.SCENARIO);
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

  /** The tail cut as a document: its rule, the chosen k, both fences and every k swept. */
  static Map<String, Object> tailCut(final TailCut.Choice cut) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", TailCut.RULE);
    out.put("quantiles", TailCut.QUANTILES);
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

  /** The forest's declared grid as a document, for the run records of both steps. */
  static Map<String, Object> forestGrid() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("trees", Forest.TREES);
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
   * ElasticNetSelection#MODELS}, a forest line for one of those models, or a tail cut line (which
   * {@link ElasticNetSelection#tailCut} reads whole or refuses). A file written before ticket 23,
   * without the tail cut, has another header, as has one written before ticket 13, without the
   * forests, or one of ticket 14's six models, without the retrospective one; one of the
   * models before ticket 12 ({@code previous_own_start}, {@code revised_nine}, ...) is told
   * apart by its model names.
   */
  static boolean onlyThisStep(final List<String> lines) {
    if (lines.isEmpty() || !ElasticNetSelection.HEADER.equals(lines.get(0))) {
      return false;
    }
    try {
      return ElasticNetSelection.read(lines).stream()
          .allMatch(p -> p.step().equals(ElasticNetSelection.STEP)
              && (p.name().equals(ElasticNetSelection.INPUTS)
                  || ElasticNetSelection.MODELS.contains(p.name()))
              || p.step().equals(ElasticNetSelection.FOREST_STEP)
                  && ElasticNetSelection.MODELS.contains(p.name())
              || p.step().equals(ElasticNetSelection.TAIL_STEP));
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
