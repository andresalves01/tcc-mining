package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.BusinessDays;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TimeTravel;
import app.issues.Universe.Population;
import app.config.PipelineConfig;
import app.issues.FirstCommit;
import app.issues.port.ResultStore;
import app.pipeline.IssuesFirstCommitAuditTask;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.IssuesInProgressExtractTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The study's extraction: the issues resolved as Fixed read at their first In Progress, those of
 * at least one active business day kept, the split drawn over them at random and stratified by
 * project ({@link ProjectSplit}), and time travel at the first In Progress. Writes the cache to
 * CACHE only when the gate passed; records a summary, with the split's rule and seed, in
 * ISSUES_DB. Prints the split in counts only: no duration of either side.
 *
 * <p>With POPULATION=FIRST_COMMIT, the first-commit population instead ({@link
 * IssuesFirstCommitExtractTask}): the audit's population, split, cap and target, the columns read
 * at the first commit. It refuses the In Progress study's TARGET_DB and ISSUES_DB, so its run is
 * recorded in a database of its own, and prints of the target the outer training's alone. The
 * split is the rule SPLIT_RULE declares ({@link SplitRule}), random by default; the run is recorded
 * in that design's own run collection ({@link ResultStore#design}), and the stage refuses one that
 * already holds another design's runs. Under the temporal rule the record adds the cuts, the
 * issues crossing them and the new projects. The In Progress population refuses a temporal rule.
 */
public final class IssuesInProgressExtractCommand implements CommandRunner {

  private static final Locale EN = Locale.ROOT;

  private final Function<String, String> settings;

  public IssuesInProgressExtractCommand() {
    this(n -> {
      final String s = System.getenv(n);
      return s == null || s.isBlank() ? System.getProperty(n) : s;
    });
  }

  public IssuesInProgressExtractCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  /** The population POPULATION names, IN_PROGRESS when unset. */
  static Population population(final String setting) throws CommandFailedException {
    if (setting == null || setting.isBlank()) {
      return Population.IN_PROGRESS;
    }
    try {
      return Population.valueOf(setting.strip().toUpperCase(EN));
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  POPULATION must be one of "
          + java.util.Arrays.toString(Population.values()) + ", got '" + setting + "'"));
    }
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = settings.apply("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<output file> is required"));
    }
    final Population population = population(settings.apply("POPULATION"));
    final SplitRule rule;
    try {
      rule = SplitRule.declared(settings.apply(SplitRule.SETTING), population);
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
    if (population == Population.FIRST_COMMIT) {
      firstCommit(context, cache, rule);
      return;
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

  /** The issues of every project in {@code perProject}. */
  private static int issues(final Map<String, Integer> perProject) {
    return perProject.values().stream().mapToInt(Integer::intValue).sum();
  }

  /**
   * Refuses, before anything is read, a configuration that would record a stage of the
   * first-commit study in the In Progress study's TARGET_DB or ISSUES_DB.
   */
  static void requireOwnDatabases(final PipelineConfig config) throws CommandFailedException {
    if (config == null || PipelineConfig.DEFAULT_TARGET_DB.equals(config.targetDb())
        || PipelineConfig.DEFAULT_ISSUES_DB.equals(config.issuesDb())) {
      throw new CommandFailedException(List.of("  the first-commit study records its runs in "
          + "databases of its own: set TARGET_DB and ISSUES_DB to names other than "
          + PipelineConfig.DEFAULT_TARGET_DB + " and " + PipelineConfig.DEFAULT_ISSUES_DB
          + ", and PREPARED_DB to where the prepared collections are read"));
    }
  }

  /**
   * The store of {@code rule}'s design ({@link ResultStore#design}), refused, before anything is
   * read, when its run collection already holds a run of another design, or cannot tell.
   */
  static ResultStore requireOwnDesign(final ResultStore store, final SplitRule rule)
      throws CommandFailedException {
    final ResultStore own;
    final Optional<Set<SplitRule>> designs;
    try {
      own = store.design(rule);
      designs = own.designs();
    } catch (final RuntimeException e) {
      throw new CommandFailedException(List.of("  the runs of the " + rule.setting()
          + " design cannot be told apart from another design's: " + e.getMessage()));
    }
    if (designs.isEmpty()) {
      throw new CommandFailedException(List.of("  the run store cannot tell which design its "
          + "runs were drawn under, so the " + rule.setting() + " design is not recorded in it"));
    }
    final Set<SplitRule> others = EnumSet.noneOf(SplitRule.class);
    others.addAll(designs.get());
    others.remove(rule);
    if (!others.isEmpty()) {
      throw new CommandFailedException(List.of("  the run collection of the " + rule.setting()
          + " design (suffix '" + rule.collectionSuffix() + "') already holds runs of " + others
          + ": nothing is written over another design"));
    }
    return own;
  }

  /**
   * The first-commit extraction: refuses the In Progress study's databases, and a run collection
   * holding another design's runs, before reading anything; records the run in ISSUES_DB, in the
   * collection of {@code rule}'s design; writes the cache only when the gate passed.
   */
  private void firstCommit(final Context context, final String cache, final SplitRule rule)
      throws CommandFailedException {
    final PipelineConfig config = context.config();
    requireOwnDatabases(config);
    final ResultStore store = requireOwnDesign(context.issuesResults(), rule);
    final IssuesFirstCommitExtractTask.Report fc =
        new IssuesFirstCommitExtractTask(context.study(), context.progress(), rule).run();
    final IssuesInProgressExtractTask.Report r = fc.extraction();
    final var s = r.split();

    System.out.printf("%n  DURATION FROM THE FIRST COMMIT -- EXTRACTION AND GATES%n");
    System.out.printf("%n  FUNNEL%n");
    for (final IssuesFirstCommitAuditTask.Step step : fc.funnel()) {
      System.out.printf("    %-66s %,8d  %,8d%n", step.label(), step.remaining(), step.removed());
    }
    System.out.printf("%n  POPULATION AND SPLIT%n");
    System.out.printf("    %,d issues past every rule but the target's, %,d of a target of 0 "
        + "never drawn%n", s.started(), s.zeroDays());
    System.out.printf("    %s%n", rule.rule());
    System.out.printf("    %,d issues of %,d projects  training %,d  test %,d%n", s.rows(),
        s.projects(), s.train(), s.test());
    System.out.printf("    inner fitting %,d  inner validation %,d%n", s.fit(), s.validation());
    final IssuesFirstCommitExtractTask.Temporal tc = fc.temporal();
    if (tc != null) {
      System.out.printf("    outer cut %s  inner cut %s%n", tc.cuts().outer(), tc.cuts().inner());
      System.out.printf("    crossing the cut: %,d outer training issues, %,d inner fitting "
          + "issues (first resolution after the cut)%n", tc.crossing(), tc.innerCrossing());
      System.out.printf("    new projects (outer test alone): %,d, with %,d issues %s%n",
          tc.newProjects().size(), issues(tc.newProjects()), tc.newProjects());
    }
    final FirstCommit.Threshold t = fc.threshold();
    System.out.printf("%n  WAIT CAP, LEARNED ON THE INNER FITTING%n");
    System.out.printf(EN, "    Q1 %.2f  median %.2f  Q3 %.2f  fence %.2f  cap %d business days"
        + "  (%,d waits)%n", t.q1(), t.median(), t.q3(), t.fence(), t.cap(), t.gaps());
    final FirstCommit.Summary a = fc.training();
    System.out.printf("%n  TARGET ON THE OUTER TRAINING, BUSINESS DAYS (the outer test's is not "
        + "read here)%n");
    System.out.printf(EN, "    n %,d  min %.2f  Q1 %.2f  median %.2f  Q3 %.2f  P90 %.2f  max %.2f"
        + "  mean %.2f  sum %,d%n", a.n(), a.min(), a.q1(), a.median(), a.q3(), a.p90(), a.max(),
        a.mean(), a.sum());
    System.out.printf("%n  EXECUTOR AT THE FIRST COMMIT%n");
    r.executors().forEach((k, v) -> System.out.printf("    %-40s %,8d%n", k, v));

    System.out.printf("%n  GATE 1 -- TIME TRAVEL AT t0 = THE FIRST COMMIT (%d issues, seed %d)%n",
        TimeTravel.SAMPLE, TimeTravel.SEED);
    for (final TimeTravel.Check c : r.travel()) {
      System.out.printf("    %-42s %4d of %4d equal   naive rule differs in %4d%n", c.column(),
          c.same(), c.total(), c.naiveDiffers());
      c.examples().forEach(x -> System.out.println("        " + x));
    }

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", "issues-in-progress-extract");
    run.put("study_population", Population.FIRST_COMMIT.name());
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.entries().size());
    run.put("population", FirstCommit.POPULATION);
    run.put("first_commit_share", FirstCommit.SHARE);
    run.put("target_rule", FirstCommit.RULE);
    run.put("prepared_db", config.preparedDb());
    final List<Map<String, Object>> funnel = new java.util.ArrayList<>();
    for (final IssuesFirstCommitAuditTask.Step step : fc.funnel()) {
      final Map<String, Object> line = new LinkedHashMap<>();
      line.put("step", step.label());
      line.put("remaining", step.remaining());
      line.put("removed", step.removed());
      funnel.add(line);
    }
    run.put("funnel", funnel);
    final Map<String, Object> cap = new LinkedHashMap<>();
    cap.put("q1", t.q1());
    cap.put("median", t.median());
    cap.put("q3", t.q3());
    cap.put("fence", t.fence());
    cap.put("cap", t.cap());
    cap.put("waits", t.gaps());
    run.put("wait_cap", cap);
    if (tc == null) {
      run.put(SplitRule.RULE_FIELD, ProjectSplit.RULE);
      run.put("split_seed", ProjectSplit.SEED);
      run.put("split_share", ProjectSplit.SHARE);
    } else {
      run.put(SplitRule.DESIGN_FIELD, rule.setting());
      run.put(SplitRule.RULE_FIELD, rule.rule());
      final Map<String, Object> cuts = new LinkedHashMap<>();
      cuts.put("outer", tc.cuts().outer().toString());
      cuts.put("inner", tc.cuts().inner().toString());
      run.put("split_cuts", cuts);
      final Map<String, Object> crossing = new LinkedHashMap<>();
      crossing.put("outer_training", tc.crossing());
      crossing.put("inner_fitting", tc.innerCrossing());
      run.put("crossing_the_cut", crossing);
      final Map<String, Object> newProjects = new LinkedHashMap<>();
      newProjects.put("projects", tc.newProjects().size());
      newProjects.put("issues", issues(tc.newProjects()));
      newProjects.put("issues_per_project", new LinkedHashMap<>(tc.newProjects()));
      run.put("new_projects", newProjects);
    }
    final Map<String, Object> sizes = new LinkedHashMap<>();
    sizes.put("fixed_base", fc.base());
    sizes.put("past_every_rule_but_the_target", s.started());
    sizes.put("zero_business_days", s.zeroDays());
    sizes.put("drawn", s.rows());
    sizes.put("projects", s.projects());
    sizes.put("outer_training", s.train());
    sizes.put("outer_test", s.test());
    sizes.put("inner_fitting", s.fit());
    sizes.put("inner_validation", s.validation());
    run.put("split_sizes", sizes);
    final Map<String, Object> target = new LinkedHashMap<>();
    target.put("n", a.n());
    target.put("min", a.min());
    target.put("q1", a.q1());
    target.put("median", a.median());
    target.put("q3", a.q3());
    target.put("p90", a.p90());
    target.put("max", a.max());
    target.put("mean", a.mean());
    target.put("sum", a.sum());
    run.put("outer_training_target", target);
    run.put("target_columns", IssuesFirstCommitExtractTask.TARGET_COLUMNS);
    run.put("attribute_columns", FirstCommit.ATTRIBUTE_COLUMNS);
    run.put("not_written", IssuesInProgressExtractTask.PAUSED + ": it reads the status, which "
        + "this study does not read");
    System.out.printf("%n  run recorded in ISSUES_DB (%s, runs%s): %s%n", config.issuesDb(),
        rule.collectionSuffix(), store.record(run, List.of()));

    if (!r.passed()) {
      throw new CommandFailedException(List.of("", "  REFUSED: the gate failed -- the cache was NOT "
          + "written. Explain every mismatch before going on."));
    }
    try {
      RowCache.write(Path.of(cache), r.entries(), r.columns(), List.of(Population.FIRST_COMMIT));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + cache + ": " + e));
    }
    System.out.printf("%n  cache written: %s (%,d rows, %d columns)%n", cache,
        r.entries().size(), r.columns().size());
  }
}
