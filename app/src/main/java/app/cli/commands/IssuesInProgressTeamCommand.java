package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Extractor;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The revised team columns, on their own file. Runs the extraction as {@code
 * issues-in-progress-extract} does and writes only the four columns per issue to TEAM_COLUMNS, so
 * the extracted matrix is never rewritten; refuses, as the extraction does, when its time-travel
 * gate fails. That gate recomputes the extracted columns only, not these four. Never replaces a
 * file that is not a team file. Records the run, with the file's SHA-256, in ISSUES_DB.
 *
 * <p>With POPULATION=FIRST_COMMIT, the columns of the first-commit population read at the first
 * commit that counts ({@link IssuesFirstCommitExtractTask}); it refuses the In Progress study's
 * TARGET_DB and ISSUES_DB before reading anything.
 */
public final class IssuesInProgressTeamCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-team";

  private final Function<String, String> settings;

  public IssuesInProgressTeamCommand() {
    this(n -> {
      final String s = System.getenv(n);
      return s == null || s.isBlank() ? System.getProperty(n) : s;
    });
  }

  /**
   * @param settings the settings by name; the tests hand their own, so a real TEAM_COLUMNS in the
   *     environment of whoever runs them is never read
   */
  IssuesInProgressTeamCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String file = settings.apply("TEAM_COLUMNS");
    if (file == null || file.isBlank()) {
      throw new CommandFailedException(List.of("  TEAM_COLUMNS=<output file> is required"));
    }
    final Population population =
        IssuesInProgressExtractCommand.population(settings.apply("POPULATION"));
    final boolean firstCommit = population == Population.FIRST_COMMIT;
    if (firstCommit) {
      IssuesInProgressExtractCommand.requireOwnDatabases(context.config());
    }
    final IssuesInProgressExtractTask.Report r = firstCommit
        ? new IssuesFirstCommitExtractTask(context.study(), context.progress()).run().extraction()
        : new IssuesInProgressExtractTask(context.study(), context.progress()).run();

    System.out.printf("%n  DURATION FROM %s -- REVISED TEAM COLUMNS%n",
        firstCommit ? "THE FIRST COMMIT" : "IN PROGRESS");
    System.out.printf("    %,d issues  time-travel gate (the registry's columns) %s%n",
        r.team().size(), r.travelPassed() ? "passed" : "FAILED");
    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  REFUSED: the time-travel gate failed -- "
          + "nothing was written or recorded. Run issues-in-progress-extract to see why."));
    }
    for (final String column : Extractor.TEAM_REVISED) {
      final long observed = r.team().values().stream().filter(v -> v.containsKey(column)).count();
      System.out.printf("    %-18s observed %,6d of %,6d%n", column, observed, r.team().size());
    }

    final Path out = Path.of(file);
    final String sha;
    try {
      TeamColumns.write(out, r.team());
      sha = Sha256.of(out);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + file + ": " + e));
    }
    System.out.printf("%n  written to %s  sha256 %s%n", out, sha);

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    if (firstCommit) {
      run.put("study_population", population.name());
      run.put("read_at", "the first commit that counts");
    }
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.team().size());
    run.put("columns", Extractor.TEAM_REVISED);
    run.put("team_columns_sha256", sha);
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, List.of()));
  }
}
