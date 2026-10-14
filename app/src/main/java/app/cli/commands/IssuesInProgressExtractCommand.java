package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Parity;
import app.issues.RowCache;
import app.issues.TimeTravel;
import app.issues.Universe.Population;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-progress study's extraction: the FIXED issues read at
 * their first In Progress, the split on that instant, time travel at it and parity with the Python
 * specification. Writes the cache to CACHE only when every gate passed; records a summary in
 * ISSUES_DB. Needs PARITY, the Python specification's in-progress export.
 */
public final class IssuesInProgressExtractCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = System.getenv("CACHE");
    final String parity = System.getenv("PARITY");
    if (cache == null || cache.isBlank() || parity == null || parity.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<output file> and PARITY=<the Python "
          + "in-progress export> are required"));
    }
    final Parity.Spec python;
    try {
      python = Parity.read(Path.of(parity));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + parity + ": " + e));
    }
    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(context.study(), context.progress()).run(python);

    System.out.printf("%n  DURATION FROM IN PROGRESS -- EXTRACTION AND GATES%n");
    final var s = r.split();
    System.out.printf("%n  POPULATION AND 70/30 SPLIT AT THE IN PROGRESS INSTANT%n");
    System.out.printf("    %,d issues  cut %s  training %,d  test %,d  crosses %,d%n", s.rows(),
        s.cut(), s.train(), s.test(), s.crosses());
    System.out.printf("    inner cut %s  fit %,d  selection %,d  crosses %,d%n",
        s.innerCut(), s.fit(), s.selection(), s.innerCrosses());
    System.out.printf("%n  EXECUTOR AT t0 (P8)%n");
    r.executors().forEach((k, v) -> System.out.printf("    %-40s %,8d%n", k, v));

    System.out.printf("%n  GATE 1 -- TIME TRAVEL AT t0 = IN PROGRESS (%d issues, seed %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d of %4d equal   naive rule differs in %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }
    System.out.printf("%n  GATE 2 -- PARITY WITH THE PYTHON SPECIFICATION (%s)%n", parity);
    if (r.membership().isEmpty()) {
      System.out.printf("    the export has no 'andamento' column: nothing compared%n");
    }
    for (final Parity.Membership m : r.membership()) {
      System.out.printf("    population %-9s java %,8d  python %,8d  java only %,d  python only %,d%n",
          m.population(), m.java(), m.python(), m.onlyJava(), m.onlyPython());
    }
    for (final Parity.Column c : r.parity()) {
      System.out.printf("    %-42s %,8d compared  %,6d mismatched%n", c.name(), c.compared(),
          c.mismatches());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-in-progress-extract");
    run.put("gate_time_travel", r.travelPassed());
    run.put("gate_parity", r.parityPassed());
    run.put("rows", r.entries().size());
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Parity.Column c : r.parity()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("column", c.name());
      row.put("compared", c.compared());
      row.put("mismatched", c.mismatches());
      rows.add(row);
    }
    System.out.printf("%n  run recorded in ISSUES_DB: %s%n",
        context.issuesResults().record(run, rows));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  REFUSED: a gate failed -- the cache was NOT "
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
