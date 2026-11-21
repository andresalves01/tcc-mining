package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The study's extraction ({@link IssuesFirstCommitExtractTask}): the first-commit population, its
 * split under the revised temporal rule ({@link SplitRule}), the cap on the waits and the target,
 * and every column read at the first commit that counts, with time travel checked at that instant.
 * Writes the cache to CACHE only when the gate passed. It refuses the In Progress study's
 * TARGET_DB and ISSUES_DB, records its run in the revised design's own run collection ({@link
 * ResultStore#design}), refusing one that holds another design's runs, and prints of the target
 * the outer training's alone. The record adds the cuts, the issues crossing them, how many issues
 * each level leaves out of what it teaches, that validation and test keep every issue, the outer
 * training's target with and without its crossing issues, and the new projects by both
 * definitions, each labelled.
 */
public final class IssuesInProgressExtractCommand implements CommandRunner {

  /** The stage this command records its runs under, of either study. */
  static final String STAGE = "issues-in-progress-extract";

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

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String cache = settings.apply("CACHE");
    if (cache == null || cache.isBlank()) {
      throw new CommandFailedException(List.of("  CACHE=<output file> is required"));
    }
    firstCommit(context, cache, SplitRule.REVISED, waitFenceK(settings));
  }

  /** What the revised design keeps of every issue crossing a cut, in its record and its print. */
  static final String SCORED_SIDES = "validation and test keep every issue";

  /** The label of the whole outer training's target under the revised design. */
  static final String WHOLE_SIDE = "the whole outer training, crossing issues included";

  /** The label of the target of the outer training issues that teach. */
  static final String TEACHING_SIDE = "the outer training issues that teach, crossing issues "
      + "left out";

  /** One line of a target's distribution. */
  private static void print(final FirstCommit.Summary a) {
    System.out.printf(EN, "    n %,d  min %.2f  Q1 %.2f  median %.2f  Q3 %.2f  P90 %.2f  max %.2f"
        + "  mean %.2f  sum %,d%n", a.n(), a.min(), a.q1(), a.median(), a.q3(), a.p90(), a.max(),
        a.mean(), a.sum());
  }

  /** A target's distribution as the record keeps it, in this order. */
  private static Map<String, Object> summary(final FirstCommit.Summary a) {
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
    return target;
  }

  /** New projects as the record keeps them: how many, their issues, and those per project. */
  private static Map<String, Object> newProjects(final Map<String, Integer> perProject) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("projects", perProject.size());
    out.put("issues", issues(perProject));
    out.put("issues_per_project", new LinkedHashMap<>(perProject));
    return out;
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
    final Optional<Long> others;
    try {
      own = store.design(rule);
      others = own.otherDesignRuns();
    } catch (final RuntimeException e) {
      throw new CommandFailedException(List.of("  the runs of the " + rule.setting()
          + " design cannot be told apart from another design's: " + e.getMessage()));
    }
    if (others.isEmpty()) {
      throw new CommandFailedException(List.of("  the run store cannot tell which design its "
          + "runs were drawn under, so the " + rule.setting() + " design is not recorded in it"));
    }
    if (others.get() > 0) {
      throw new CommandFailedException(List.of("  the run collection of the " + rule.setting()
          + " design (suffix '" + rule.collectionSuffix() + "') already holds " + others.get()
          + " runs of another design: nothing is written over another design"));
    }
    return own;
  }

  /** The run store of a later stage: the revised design's, in databases of the study's own. */
  static ResultStore runStore(final Context context) throws CommandFailedException {
    requireOwnDatabases(context.config());
    return requireOwnDesign(context.issuesResults(), SplitRule.REVISED);
  }

  /**
   * The k of the wait fence the matrix was extracted with, read from the run store of its design
   * ({@link ResultStore#waitFenceK()}); the official k when there is no store.
   */
  static double matrixWaitFenceK(final ResultStore store) {
    return store != null ? store.waitFenceK() : FirstCommit.DEFAULT_WAIT_K;
  }

  /** The k of {@code WAIT_FENCE_K} in {@code settings}, refused as the stage's failure. */
  static double waitFenceK(final Function<String, String> settings)
      throws CommandFailedException {
    try {
      return FirstCommit.waitFenceK(settings.apply(FirstCommit.WAIT_FENCE_SETTING));
    } catch (final IllegalArgumentException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }
  }

  /**
   * The first-commit extraction: refuses the In Progress study's databases, and a run collection
   * holding another design's runs, before reading anything; records the run in ISSUES_DB, in the
   * collection of {@code rule}'s design; writes the cache only when the gate passed.
   */
  private void firstCommit(final Context context, final String cache, final SplitRule rule,
      final double waitK) throws CommandFailedException {
    final PipelineConfig config = context.config();
    requireOwnDatabases(config);
    final ResultStore store = requireOwnDesign(context.issuesResults(), rule);
    final IssuesFirstCommitExtractTask.Report fc =
        new IssuesFirstCommitExtractTask(context.study(), context.progress(), rule, waitK).run();
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
    System.out.printf("    outer cut %s  inner cut %s%n", tc.cuts().outer(), tc.cuts().inner());
    System.out.printf("    crossing the cut: %,d outer training issues, %,d inner fitting "
        + "issues (first resolution after the cut)%n", tc.crossing(), tc.innerCrossing());
    System.out.printf("    left out of what the training side teaches: %,d at the outer "
        + "level, %,d at the inner; %s%n", tc.crossing(), tc.innerCrossing(), SCORED_SIDES);
    final Map<String, Integer> byTrainingSide = tc.revised().newProjectsTrainingSide();
    System.out.printf("    new projects (no issue that teaches, as the scoring reads them): "
        + "%,d, with %,d issues %s%n", tc.newProjects().size(), issues(tc.newProjects()),
        tc.newProjects());
    System.out.printf("    new projects (no issue on the training side, crossing issues "
        + "included): %,d, with %,d issues %s%n", byTrainingSide.size(), issues(byTrainingSide),
        byTrainingSide);
    final FirstCommit.Threshold t = fc.threshold();
    System.out.printf("%n  WAIT CAP, LEARNED ON THE INNER FITTING%n");
    if (waitK != FirstCommit.DEFAULT_WAIT_K) {
      System.out.printf("    wait fence: %s%n", FirstCommit.fenceText(waitK));
    }
    System.out.printf(EN, "    Q1 %.2f  median %.2f  Q3 %.2f  fence %s  cap %s"
        + "  (%,d waits)%n", t.q1(), t.median(), t.q3(),
        Double.isInfinite(t.fence()) ? FirstCommit.NONE : String.format(EN, "%.2f", t.fence()),
        t.uncapped() ? FirstCommit.NONE + ", no wait cut" : t.cap() + " business days",
        t.gaps());
    final FirstCommit.Summary a = fc.training();
    System.out.printf("%n  TARGET ON THE OUTER TRAINING, BUSINESS DAYS (the outer test's is not "
        + "read here)%n");
    final IssuesFirstCommitExtractTask.Revised revised = tc.revised();
    System.out.printf("    %s%n", WHOLE_SIDE);
    print(a);
    System.out.printf("    %s%n", TEACHING_SIDE);
    print(revised.teaching());
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
    run.put("stage", STAGE);
    run.put("study_population", Population.FIRST_COMMIT.name());
    run.put("gate_time_travel", r.travelPassed());
    run.put("rows", r.entries().size());
    run.put("population", FirstCommit.POPULATION);
    run.put("first_commit_share", FirstCommit.SHARE);
    run.put("target_rule", FirstCommit.rule(waitK));
    if (waitK != FirstCommit.DEFAULT_WAIT_K) {
      // only a sensitivity run names its k: the official record stays as it was
      run.put("wait_fence_k", FirstCommit.waitFenceLabel(waitK));
    }
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
    cap.put("fence", Double.isInfinite(t.fence()) ? FirstCommit.NONE : (Object) t.fence());
    cap.put("cap", t.uncapped() ? FirstCommit.NONE : (Object) t.cap());
    cap.put("waits", t.gaps());
    run.put("wait_cap", cap);
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
    final Map<String, Object> left = new LinkedHashMap<>();
    left.put("outer_level", tc.crossing());
    left.put("inner_level", tc.innerCrossing());
    left.put("left_out_of", "everything the training side teaches at that level: the "
        + "fitting rows, the reference medians, the fences, the wait cap and the histories");
    left.put("scored_sides", SCORED_SIDES);
    run.put("crossing_left_out", left);
    final Map<String, Object> newProjects = newProjects(tc.newProjects());
    newProjects.put("definition", "projects with issues on the outer test and no issue that "
        + "teaches at the outer level, as the scoring reads them");
    run.put("new_projects", newProjects);
    final Map<String, Object> side = newProjects(revised.newProjectsTrainingSide());
    side.put("definition", "projects with issues on the outer test and none on the outer "
        + "training side, crossing issues included");
    run.put("new_projects_by_training_side", side);
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
    final Map<String, Object> target = summary(a);
    if (revised != null) {
      target.put("issues", WHOLE_SIDE);
    }
    run.put("outer_training_target", target);
    if (revised != null) {
      final Map<String, Object> teaching = summary(revised.teaching());
      teaching.put("issues", TEACHING_SIDE);
      run.put("outer_training_target_without_crossing", teaching);
    }
    run.put("target_columns", IssuesFirstCommitExtractTask.TARGET_COLUMNS);
    run.put("attribute_columns", FirstCommit.ATTRIBUTE_COLUMNS);
    run.put("not_written", "PAUSA_volta_open: it reads the status, which this study does not "
        + "read");
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
