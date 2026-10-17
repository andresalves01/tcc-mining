package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.BusinessDays;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.issues.Universe.Population;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-progress study's extraction: the FIXED issues read at their first In Progress, those of
 * at least one active business day kept, the split drawn over them at random and stratified by
 * project ({@link ProjectSplit}), and time travel at the first In Progress. Writes the cache to
 * CACHE only when the gate passed; records a summary, with the split's rule and seed, in
 * ISSUES_DB. Prints the split in counts only: no duration of either side.
 */
public final class IssuesInProgressExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<output file> is required"));
    }
    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(context.study(), context.progress()).run();

    System.out.printf("%n  DURATION FROM IN PROGRESS -- EXTRACTION AND GATES%n");
    final var s = r.split();
    System.out.printf("%n  POPULATION AND SPLIT%n");
    System.out.printf("    %,d In-Progress issues, %,d of 0 active business days never drawn%n",
        s.started(), s.zeroDays());
    System.out.printf("    %s%n", ProjectSplit.RULE);
    System.out.printf("    %,d issues of %,d projects  training %,d  test %,d%n", s.rows(),
        s.projects(), s.train(), s.test());
    System.out.printf("    inner fitting %,d  inner validation %,d%n", s.fit(), s.validation());
    System.out.printf("%n  EXECUTOR AT t0 (P8)%n");
    r.executors().forEach((k, v) -> System.out.printf("    %-40s %,8d%n", k, v));

    System.out.printf("%n  GATE 1 -- TIME TRAVEL AT t0 = IN PROGRESS (%d issues, seed %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d of %4d equal   naive rule differs in %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-in-progress-extract");
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.entries().size());
    run.put("population", BusinessDays.POPULATION);
    run.put("split_rule", ProjectSplit.RULE);
    run.put("split_seed", ProjectSplit.SEED);
    run.put("split_share", ProjectSplit.SHARE);
    final Map<String, Object> sizes = new LinkedHashMap<>();
    sizes.put("in_progress", s.started());
    sizes.put("zero_business_days", s.zeroDays());
    sizes.put("drawn", s.rows());
    sizes.put("projects", s.projects());
    sizes.put("outer_training", s.train());
    sizes.put("outer_test", s.test());
    sizes.put("inner_fitting", s.fit());
    sizes.put("inner_validation", s.validation());
    run.put("split_sizes", sizes);
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, List.of()));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  REFUSED: the gate failed -- the cache was NOT "
          + "written. Explain every mismatch before going on."));
    }
    try {
      RowCache.write(Path.of(cache), r.entries(), r.columns(), List.of(Population.IN_PROGRESS));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + cache + ": " + e));
    }
    System.out.printf("%n  cache written: %s (%,d rows, %d columns)%n", cache,
        r.entries().size(), r.columns().size());
  }
}
