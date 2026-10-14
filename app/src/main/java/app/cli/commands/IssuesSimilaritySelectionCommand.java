package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Selection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The selection step of the description-similarity chain, inner mode only: for each similarity
 * method in the augmented matrix, the candidate value whose direct predictor errs least on the inner
 * validation (see {@link Selection}).
 *
 * <p>Reads MATRIX, the matrix {@code issues-in-progress-similarity} wrote; refuses, writing and
 * recording nothing, unless the four split sizes are the registered study's and the matrix has a
 * similarity column. Only the inner fitting and inner validation rows are read; the outer test is
 * counted by its split labels and nothing else. Writes the choices to SIMILARITY_PARAMETERS -- not
 * PARAMETERS, the registered chain's, whose format differs -- never over a file holding anything
 * but this step's own lines, and records the run, with the SHA-256 of the matrix and of the
 * parameters, in ISSUES_DB.
 */
public final class IssuesSimilaritySelectionCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-similarity-selection";

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = path("MATRIX");
    final Path parameters = path("SIMILARITY_PARAMETERS");
    final long began = System.nanoTime();
    if (matrix.toAbsolutePath().normalize().equals(parameters.toAbsolutePath().normalize())) {
      throw new CommandFailedException(List.of("  REFUSED: SIMILARITY_PARAMETERS is MATRIX"));
    }

    final String matrixSha;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    try {
      matrixSha = IssuesReservedCommand.sha256(matrix);
      entries = RowCache.read(matrix);
      columns = RowCache.columns(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + matrix + ": " + e));
    }
    System.out.printf("%n  DURATION FROM IN PROGRESS -- DESCRIPTION SIMILARITY -- SELECTION ON "
        + "THE INNER VALIDATION%n");
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());

    final RegistrationInProgress.Sizes s = Selection.sizes(entries);
    System.out.printf("  outer training %,d  outer test %,d  inner fitting %,d  inner validation "
        + "%,d%n", s.train(), s.test(), s.innerFit(), s.innerSelection());
    if (!RegistrationInProgress.SIZES.equals(s)) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: the "
          + "split sizes are not the registered study's, " + RegistrationInProgress.SIZES));
    }
    final Selection.Result r;
    try {
      r = Selection.run(entries, columns);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + e.getMessage()));
    }
    if (r.methods().isEmpty()) {
      throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
          + matrix + " has no similarity column; give the matrix issues-in-progress-similarity "
          + "wrote"));
    }

    System.out.printf("  inner fitting %,d rows, inner validation %,d rows; the outer test is not "
        + "read%n", r.fitRows(), r.validationRows());
    System.out.printf("  a missing value reads the median of the inner fitting set, %.1f h; ties "
        + "within %.2f h go to the smaller value%n", r.fallbackHours(), Selection.TIE_HOURS);
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Selection.Method m : r.methods()) {
      System.out.printf("%n  %s%n", m.name());
      System.out.printf("    %6s %10s %8s %18s %18s%n", "value", "MAE h", "MAE d", "observed",
          "MAE h observed");
      for (final Selection.Candidate c : m.candidates()) {
        System.out.printf("    %6d %10.2f %8.2f %,8d (%5.1f%%) %18.2f%s%n", c.value(),
            c.maeHours(), c.maeHours() / 24, c.observed(), 100.0 * c.observed()
                / r.validationRows(), c.maeObservedHours(),
            c.value() == m.chosen() ? "  CHOSEN" : "");
        rows.add(row(r, m, c));
      }
    }

    final String parametersSha;
    try {
      if (Files.exists(parameters) && !onlyThisStep(Files.readAllLines(parameters))) {
        throw new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
            + parameters + " exists and holds more than this step's choices; it may be the "
            + "registered chain's, or carry a later step's, and is never overwritten"));
      }
      final Path partial = parameters.resolveSibling(parameters.getFileName() + ".partial");
      Files.write(partial, Selection.parameters(r));
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
    run.put("fit_rows", r.fitRows());
    run.put("validation_rows", r.validationRows());
    run.put("fallback_hours", r.fallbackHours());
    run.put("tie_hours", Selection.TIE_HOURS);
    final Map<String, Object> chosen = new LinkedHashMap<>();
    r.methods().forEach(m -> chosen.put(m.name(), m.chosen()));
    run.put("chosen", chosen);
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    run.put("java", System.getProperty("java.version"));
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));
  }

  /** One candidate as a document; the error on observed rows absent when none was observed. */
  static Map<String, Object> row(final Selection.Result r, final Selection.Method m,
      final Selection.Candidate c) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("method", m.name());
    row.put("value", c.value());
    row.put("column", c.column());
    row.put("chosen", c.value() == m.chosen());
    row.put("rows", r.validationRows());
    row.put("observed", c.observed());
    row.put("mae_hours", c.maeHours());
    row.put("mae_observed_hours", Double.isNaN(c.maeObservedHours()) ? null
        : c.maeObservedHours());
    return row;
  }

  /** A file this step wrote and nothing appended to: its header and its own lines only. */
  static boolean onlyThisStep(final List<String> lines) {
    if (lines.isEmpty() || !Selection.HEADER.equals(lines.get(0))) {
      return false;
    }
    try {
      return Selection.read(lines).stream().allMatch(p -> p.step().equals(Selection.DIRECT));
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static Path path(final String name) throws CommandFailedException {
    final String fromEnv = System.getenv(name);
    final String value = fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX and SIMILARITY_PARAMETERS are "
          + "required; missing " + name));
    }
    return Path.of(value);
  }
}
