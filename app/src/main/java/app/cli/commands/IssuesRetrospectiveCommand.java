package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.DayBoundaries;
import app.issues.FirstCommit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.IssuesRetrospectiveTask;
import app.pipeline.IssuesSimilarityTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The study's retrospective stage (ticket 15), after the similarity
 * stage: the nine {@link Retrospective} attributes observed between the first In Progress and
 * the first resolution, added to the similarity stage's matrix, every earlier column copied as it
 * is. A separate stage and a separate file, so neither the extracted matrix nor the similarity
 * matrix is rewritten, and the similarity stage need not run again.
 *
 * <p>Reads MATRIX (the similarity stage's), the corpus and the methods each implementation commit
 * changed; refuses, writing and recording nothing, unless MATRIX has the similarity stage's columns
 * and the day boundary and none of the nine, every row's split is the one the rule the run
 * declares draws ({@link SplitRule}, setting {@code SPLIT_RULE}), and the corpus holds every
 * issue of the matrix as it is. Writes the matrix with the nine columns to RETROSPECTIVE_MATRIX,
 * never over MATRIX nor over a file that is not a retrospective matrix, and records the run, with
 * the SHA-256 of both files and the coverage of each of the nine on the inner fitting and inner
 * validation, in ISSUES_DB. The outer
 * test's rows get their columns, so the test can later read them; nothing of them is counted or
 * reported here.
 */
public final class IssuesRetrospectiveCommand implements CommandRunner {

  public static final String STAGE = "issues-in-progress-retrospective";

  /** What the scenario does not read, named in the run record. */
  static final List<String> EXCLUDED = List.of(
      "D06, hours to the first interaction",
      "team load by retrospective authorship",
      "labels",
      "mean comment words",
      "citations received",
      "links",
      "commits citing the issue",
      "project issue and commit windows",
      "the actors' commits and committers");

  /** Where each setting is read from: the environment, then the system properties. */
  private final Function<String, String> settings;

  public IssuesRetrospectiveCommand() {
    this(name -> {
      final String fromEnv = System.getenv(name);
      return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
    });
  }

  /**
   * @param settings the settings by name; the tests hand their own, so a real MATRIX in the
   *     environment of whoever runs them is never read
   */
  IssuesRetrospectiveCommand(final Function<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public void run(final Context context) throws CommandFailedException {
    final Path matrix = path("MATRIX");
    final Path out = path("RETROSPECTIVE_MATRIX");
    final long began = System.nanoTime();
    if (matrix.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize())) {
      throw refused("MATRIX and RETROSPECTIVE_MATRIX must be two different files; the input is "
          + "never overwritten");
    }

    final String matrixSha;
    final List<RowCache.Entry> entries;
    final List<String> columns;
    final Population population;
    final SplitRule rule;
    try {
      refuseForeign(out);
      columns = new ArrayList<>(RowCache.columns(matrix));
      final String obsoleteTarget = BusinessDays.unwritten(columns);
      if (obsoleteTarget != null && columns.stream().anyMatch(BusinessDays.LEGACY_RECORDED_COLUMNS::contains)) {
        throw refused(obsoleteTarget);
      }
      entries = RowCache.read(matrix);
      try {
        population = RowCache.population(entries);
        rule = SplitRule.declared(settings.apply(SplitRule.SETTING), population);
      } catch (final IllegalArgumentException e) {
        throw refused(e.getMessage());
      }
      final List<String> needed = new ArrayList<>(IssuesSimilarityTask.columns());
      needed.addAll(BusinessDays.columns(population));
      if (population == Population.FIRST_COMMIT) {
        needed.addAll(FirstCommit.ATTRIBUTE_COLUMNS);
      }
      if (!columns.containsAll(needed)) {
        throw refused(population == Population.IN_PROGRESS
            ? matrix + " has not the similarity stage's columns, the day boundary and the active "
                + "business days; give the matrix issues-in-progress-similarity wrote"
            : matrix + " has not the similarity stage's columns, the first-commit target and "
                + "the attributes of decision 16a; give the matrix issues-in-progress-similarity "
                + "wrote from the first-commit extraction");
      }
      final List<String> already =
          Retrospective.COLUMNS.stream().filter(columns::contains).toList();
      if (!already.isEmpty()) {
        throw refused(matrix + " already has " + String.join(", ", already));
      }
      matrixSha = Sha256.of(matrix);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not read " + matrix + ": " + e));
    }
    final boolean firstCommit = population == Population.FIRST_COMMIT;
    final ResultStore store = IssuesInProgressExtractCommand.runStore(context, population, rule);

    System.out.printf("%n  DURATION FROM %s -- DESCRIPTION SIMILARITY -- RETROSPECTIVE "
        + "SCENARIO%n", firstCommit ? "THE FIRST COMMIT" : "IN PROGRESS");
    System.out.printf("  %s%n", Retrospective.scenario(population));
    System.out.printf("  matrix read %s  sha256 %s (%,d rows)%n", matrix, matrixSha,
        entries.size());
    final IssuesRetrospectiveTask.Report r;
    try {
      r = new IssuesRetrospectiveTask(context.study(), context.changedMethods(),
          context.progress(), population, rule).run(entries);
    } catch (final IllegalArgumentException e) {
      throw refused(e.getMessage());
    }
    if (!r.refused().isEmpty()) {
      final List<String> lines = new ArrayList<>();
      lines.add("  REFUSED, nothing was written or recorded:");
      r.refused().forEach(x -> lines.add("    " + x));
      throw new CommandFailedException(lines);
    }

    System.out.printf("  %,d implementation commits read for their methods, %,d changed a "
        + "recovered method; %d bot accounts left out of the discussion%n", r.commits(),
        r.withMethods(), r.bots().size());
    System.out.printf("%n  coverage on the issues of at least one business day (the outer test is "
        + "not counted)%n");
    for (final IssuesRetrospectiveTask.Coverage c : r.coverage()) {
      System.out.printf("    %s, %,d issues%n", c.side(), c.rows());
      c.observed().forEach((column, n) -> System.out.printf("      %-24s observed %,6d (%.1f%%)%n",
          column, n, c.rows() == 0 ? 0.0 : 100.0 * n / c.rows()));
    }

    final List<String> all = new ArrayList<>(columns);
    all.addAll(Retrospective.COLUMNS);
    final String outSha;
    try {
      RowCache.write(out, r.entries(), all, List.of(population));
      outSha = Sha256.of(out);
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not write " + out + ": " + e));
    }
    System.out.printf("%n  written to %s  sha256 %s (%,d rows, %d columns)%n", out, outSha,
        r.entries().size(), all.size());

    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", STAGE);
    if (firstCommit && rule == SplitRule.TEMPORAL) {
      run.put(SplitRule.DESIGN_FIELD, rule.setting());
      run.put(SplitRule.RULE_FIELD, rule.rule());
    }
    run.putAll(statements(population));
    if (firstCommit) {
      run.putAll(firstCommitStatements());
    }
    run.put("columns", Retrospective.COLUMNS);
    run.put("matrix_sha256", matrixSha);
    run.put("retrospective_matrix_sha256", outSha);
    run.put("rows", r.entries().size());
    run.put("implementation_commits", r.commits());
    run.put("implementation_commits_with_methods", r.withMethods());
    run.put("bots", r.bots().stream().sorted().toList());
    final List<Map<String, Object>> coverage = new ArrayList<>();
    for (final IssuesRetrospectiveTask.Coverage c : r.coverage()) {
      final Map<String, Object> one = new LinkedHashMap<>();
      one.put("side", c.side());
      one.put("rows", c.rows());
      one.put("observed", c.observed());
      coverage.add(one);
    }
    run.put("coverage", coverage);
    run.put("outer_test", "columns written, nothing counted or reported");
    run.put("seconds", (System.nanoTime() - began) / 1e9);
    System.out.printf("  run recorded in ISSUES_DB: %s%n",
        store.record(run, List.of()));
  }

  /** What the run record states about the scenario and its definitions. */
  static Map<String, Object> statements() {
    return statements(Population.IN_PROGRESS);
  }

  /** {@link #statements()} with the scenario's window of {@code population}. */
  static Map<String, Object> statements(final Population population) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("scenario", Retrospective.scenario(population));
    out.put("implementation_commits", "the issue's linked commits by a human author visible "
        + "before the first resolution, those before the first In Progress included");
    out.put("content", "Java lines added and removed, each commit's split among the issues it "
        + "cites (log1p); mean cyclomatic complexity and mean nesting of the methods those commits "
        + "changed, each method once at its latest state, no single-author requirement (raw); "
        + "distinct files of any type (log1p); share of them any author touched in the project "
        + "before the first In Progress, the issue's own commits left out (raw); all five missing "
        + "without an implementation commit, complexity and nesting without a recovered method");
    out.put("discussion", "in [first In Progress, first resolution), bots left out: comments; "
        + "distinct people who commented or changed a field, the reporter only if active, commit "
        + "authors not counted; effective changes of the assignee (all log1p, never missing)");
    out.put("discussion_reading", "the declared main retrospective specification leaves "
        + "comments and participants out, and the reading "
        + "with them is a ceiling, not a competitor");
    out.put("not_written", "the wait on the issues it was blocked by: counted in the target's "
        + "own unit and clipped at it, it equalled the target in about half of the issues that "
        + "had one, so no model reads it");
    out.put("bots", DayBoundaries.BOT_RULE);
    out.put("excluded", EXCLUDED);
    return out;
  }

  /**
   * What the run record of the first-commit population states besides {@link #statements()},
   * replacing the ones whose start differs: the window starts at the first commit that counts.
   */
  static Map<String, Object> firstCommitStatements() {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("study_population", Population.FIRST_COMMIT.name());
    out.put("window", "[first commit that counts, first resolution): the first commit replaces "
        + "the first In Progress wherever the statements name it; the implementation commits "
        + "are the same, those before the first commit included, and the familiarity reads the "
        + "files touched before the first commit");
    out.put("target", FirstCommit.RULE);
    out.put("attributes_of_decision_16a", "written by the extraction and copied as they are: "
        + FirstCommit.FIRST_LINES + ", the first commit's lines added and removed in every file, "
        + "a start attribute; " + FirstCommit.FIRST_SHARE + ", its share of the issue's lines, a "
        + "retrospective attribute");
    return out;
  }

  /** Refuses when {@code out} exists and is not a matrix with the nine columns. */
  private static void refuseForeign(final Path out) throws CommandFailedException {
    if (!Files.exists(out)) {
      return;
    }
    boolean ours;
    try {
      ours = RowCache.columns(out).containsAll(Retrospective.COLUMNS);
    } catch (final IOException | RuntimeException e) {
      ours = false;
    }
    if (!ours) {
      throw refused(out + " exists and is not a retrospective matrix; it was left as it is");
    }
  }

  private static CommandFailedException refused(final String why) {
    return new CommandFailedException(List.of("  REFUSED, nothing was written or recorded: "
        + why));
  }

  private Path path(final String name) throws CommandFailedException {
    final String value = settings.apply(name);
    if (value == null || value.isBlank()) {
      throw new CommandFailedException(List.of("  MATRIX and RETROSPECTIVE_MATRIX are required; "
          + "missing " + name));
    }
    return Path.of(value);
  }
}
