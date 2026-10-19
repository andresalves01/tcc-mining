package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Extractor;
import app.issues.TeamColumns;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The revised team columns of the description-similarity chain, on their own file. Runs the
 * in-progress extraction as {@code issues-in-progress-extract} does and writes only the four
 * columns per issue to TEAM_COLUMNS, so the registered matrix is never rewritten; refuses, as the
 * extraction does, when its time-travel gate fails. That gate recomputes the registered columns
 * only, not these four. Never replaces a file that is not a team file. Records the run, with the
 * file's SHA-256, in ISSUES_DB.
 */
public final class IssuesInProgressTeamCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-team";

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String fromEnv = System.getenv("TEAM_COLUMNS");
    final String file = fromEnv == null || fromEnv.isBlank() ? System.getProperty("TEAM_COLUMNS")
        : fromEnv;
    if (file == null || file.isBlank()) {
      throw new CommandFailedException(List.of("  TEAM_COLUMNS=<output file> is required"));
    }
    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(context.study(), context.progress()).run();

    System.out.printf("%n  DURATION FROM IN PROGRESS -- REVISED TEAM COLUMNS%n");
    System.out.printf("    %,d issues  time-travel gate (registered columns) %s%n",
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
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.team().size());
    run.put("columns", Extractor.TEAM_REVISED);
    run.put("team_columns_sha256", sha);
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, List.of()));
  }
}
