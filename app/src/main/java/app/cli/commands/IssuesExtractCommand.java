package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Initial;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.pipeline.IssuesExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the study's populations and columns, prints the gates, and writes the cache to the file
 * {@code CACHE} names -- only when every gate passed. Records a summary in
 * {@code ISSUES_DB}.
 */
public final class IssuesExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<output file> is required"));
    }
    final IssuesExtractTask.Report r =
        new IssuesExtractTask(context.study(), context.progress()).run();

    System.out.printf("%n  ISSUE DURATION -- EXTRACTION AND GATES%n");
    System.out.printf("%n  FUNNEL%n");
    r.funnel().forEach((k, v) -> System.out.printf("    %-50s %,10d%n", k, v));

    System.out.printf("%n  70/30 SPLIT BY POPULATION (TemporalSplit's rule without eligibility)%n");
    for (final var s : r.splits()) {
      System.out.printf("    %-10s %,8d rows  cut %s  training %,d  test %,d  crosses %,d%n",
          s.population(), s.rows(), s.cut(), s.train(), s.test(), s.crosses());
      System.out.printf("    %-10s          inner cut %s  fit %,d  selection %,d  crosses %,d%n",
          "", s.innerCut(), s.fit(), s.selection(), s.innerCrosses());
    }
    System.out.printf("%n  GATE 1 -- the rule, with 5 resolutions per pair, reproduces the labels' "
        + "stored split: %,d mismatches%n", r.thesisSplitMismatches());

    System.out.printf("%n  CHANGELOG (broad): per field, issues changed, chain and final value "
        + "consistent, initial value != current%n");
    for (final Initial.Fidelity f : r.fidelity()) {
      System.out.printf("    %-12s changed %6.2f%%  consistent %6.2f%%  initial!=current %6.2f%%%n",
          f.field(), 100.0 * f.changed() / f.issues(),
          f.changed() == 0 ? 100.0 : 100.0 * f.consistent() / f.changed(),
          100.0 * f.differs() / f.issues());
    }

    System.out.printf("%n  GATE 2 -- TIME TRAVEL (%d issues from the broad population, seed %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d of %4d equal   naive rule differs in %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-extract");
    run.put("funnel", r.funnel());
    run.put("gate_thesis_split", r.thesisSplitMismatches());
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.entries().size());
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, List.of()));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  REFUSED: a gate failed -- the cache was NOT "
          + "written. Explain every mismatch before going on."));
    }
    try {
      RowCache.write(Path.of(cache), r.entries(), r.columns());
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + cache + ": " + e));
    }
    System.out.printf("%n  cache written: %s (%,d rows, %d columns)%n", cache,
        r.entries().size(), r.columns().size());
  }
}
