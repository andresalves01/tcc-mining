package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Augmentation;
import app.issues.ElasticNetSelection;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.model.Cell;
import app.issues.model.Fitting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The selection step of the description-similarity chain, inner mode only: each Elastic Net
 * model's alpha and lambda, by the registered chain's sweep on the inner validation, and, for the
 * three models reading the similarity medians, the Jaccard n, embedding n and cluster k they read,
 * the combination of the 27 whose sweep errs least (see {@link ElasticNetSelection}).
 *
 * <p>Reads MATRIX; refuses, writing and recording nothing, unless the four split sizes are the
 * registered study's and the matrix has every column the models declare at every combination.
 * The outer test is counted by its split labels and nothing else. Writes the choices to
 * ELASTIC_NET_PARAMETERS -- never the registered PARAMETERS, and never over a file holding
 * anything but this step's own lines under its own header, a check made before anything is fitted
 * -- and records the run, with the SHA-256 of the matrix and of the parameters file, in ISSUES_DB.
 */
public final class IssuesSimilarityElasticNetCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-elastic-net";

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

    final RegistrationInProgress.Sizes s = ElasticNetSelection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!RegistrationInProgress.SIZES.equals(s)) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: the "
          + "split sizes are not the registered study's, " + RegistrationInProgress.SIZES));
    }
    final ElasticNetSelection.Result r;
    try {
      final List<String> missing = missing(
          ElasticNetSelection.everyCell(Augmentation.Variant.INNER), columns);
      if (!missing.isEmpty()) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + matrix + " has no column " + missing));
      }
      r = ElasticNetSelection.run(entries);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }

    System.out.printf("  inner fitting %,d rows, inner validation %,d rows; the outer test is not "
        + "read%n", r.fitRows(), r.validationRows());
    System.out.printf("  Elastic Net grid: alpha %s x %d lambdas, anchor 2*max|X'r|/alpha; "
        + "references on the inner validation: project median %.2f h, fitting median %.2f h%n",
        java.util.Arrays.toString(Fitting.ALPHAS), Fitting.LAMBDA_STEPS, r.projectMaeHours(),
        r.constantMaeHours());
    System.out.printf("  the models with the similarity medians sweep each of %d combinations of "
        + "Jaccard n %s, embedding n %s and cluster k %s; exact ties go to the smaller values, "
        + "Jaccard n first, then embedding n, then cluster k%n",
        ElasticNetSelection.Combination.all().size(), Augmentation.JACCARD_N,
        Augmentation.EMBEDDING_N, Augmentation.CLUSTER_K);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final ElasticNetSelection.Model m : r.models()) {
      final Fitting.Fitted f = m.fitted();
      System.out.printf("%n  %-20s alpha %.2f  lambda %-12.6g step %2d of %d%s; MAE %.2f h "
          + "(%.2f d); %d columns, %d selected%n", m.cell().label(), f.alpha(), f.lambda(),
          f.step(), Fitting.LAMBDA_STEPS, f.step() == 0 ? " = TOP, ALL ZEROED"
              : f.step() == Fitting.LAMBDA_STEPS - 1 ? " = GRID FLOOR" : "",
          f.score().maeHours(), f.score().maeHours() / 24, f.columns().size(), f.selected());
      if (m.combination() != null) {
        System.out.printf("    chosen: Jaccard n %d, embedding n %d, cluster k %d%n",
            m.combination().jaccardN(), m.combination().embeddingN(),
            m.combination().clusterK());
        System.out.printf("    each combination's best on the inner validation (Jaccard n, "
            + "embedding n, cluster k: MAE h)%n");
        for (final ElasticNetSelection.Swept x : m.combinations()) {
          System.out.printf("      %2d %2d %2d: %.2f%s%n", x.combination().jaccardN(),
              x.combination().embeddingN(), x.combination().clusterK(), x.maeHours(),
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
    run.put("matrix_sha256", matrixSha);
    run.put("parameters_sha256", parametersSha);
    run.put("jaccard_n", Augmentation.JACCARD_N);
    run.put("embedding_n", Augmentation.EMBEDDING_N);
    run.put("cluster_k", Augmentation.CLUSTER_K);
    run.put("ties", "within a sweep the earlier point; across combinations the smaller Jaccard n, "
        + "then embedding n, then cluster k");
    run.put("fit_rows", r.fitRows());
    run.put("validation_rows", r.validationRows());
    run.put("project_mae_hours", r.projectMaeHours());
    run.put("constant_mae_hours", r.constantMaeHours());
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /**
   * Refuses when {@code parameters} exists and is not a file of this step's own choices: it may
   * be the registered chain's, or one written in an earlier format, and is never overwritten.
   */
  private static void refuseForeign(final Path parameters)
      throws IOException, CommandFailedException {
    if (Files.exists(parameters) && !onlyThisStep(Files.readAllLines(parameters))) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + parameters + " exists and is not a file of this step's choices in its current "
          + "format; it may be the registered chain's or an earlier one, and is never "
          + "overwritten"));
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
        one.put("mae_hours", x.maeHours());
        each.add(one);
      }
      row.put("combinations", each);
    }
    row.put("alpha", f.alpha());
    row.put("lambda", f.lambda());
    row.put("step", f.step());
    row.put("mae_hours", f.score().maeHours());
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
    return row;
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

  /** A file this step wrote and nothing appended to: its header and its own lines only. */
  static boolean onlyThisStep(final List<String> lines) {
    if (lines.isEmpty() || !ElasticNetSelection.HEADER.equals(lines.get(0))) {
      return false;
    }
    try {
      return ElasticNetSelection.read(lines).stream()
          .allMatch(p -> p.step().equals(ElasticNetSelection.STEP));
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean same(final Path a, final Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private static String env(final String name) {
    final String fromEnv = System.getenv(name);
    final String value = fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    return value == null || value.isBlank() ? null : value;
  }

  private static Path path(final String name) throws CommandFailedException {
    final String value = env(name);
    if (value == null) {
      throw new CommandFailedException(List.of("  MATRIX and ELASTIC_NET_PARAMETERS "
          + "are required; missing " + name));
    }
    return Path.of(value);
  }
}
