package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.AnalogyEstimate;
import app.issues.ElasticNetSelection;
import app.issues.ProjectSplit;
import app.issues.SplitRule;
import app.issues.TemporalSplit;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stages that check a matrix's sides check them against the rule the run declares in
 * SPLIT_RULE: random unless set, temporal for the first-commit study only.
 */
class DeclaredSplitRuleTest {

  @TempDir Path dir;

  private final List<Map<String, Object>> runs = new ArrayList<>();
  private final List<Map<String, Object>> randomRuns = new ArrayList<>();
  private final List<Map<String, Object>> temporalRuns = new ArrayList<>();
  private final List<Map<String, Object>> revisedRuns = new ArrayList<>();
  /** Every design whose store a stage asked for, in order. */
  private final List<SplitRule> asked = new ArrayList<>();

  private static PipelineConfig own() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private static PipelineConfig defaults() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", "smartshark_tcc",
        "smartshark_issues", "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private Context context(final PipelineConfig config) {
    final ResultStore store = new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        runs.add(run);
        randomRuns.add(run);
        return "run";
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(randomRuns.stream().anyMatch(r -> stage.equals(r.get("stage"))));
      }

      @Override
      public ResultStore design(final SplitRule rule) {
        asked.add(rule);
        if (rule == SplitRule.RANDOM) {
          return this;
        }
        // each design's collection of its own: the temporal one's, or the revised one's
        final List<Map<String, Object>> own = rule == SplitRule.TEMPORAL ? temporalRuns
            : revisedRuns;
        return new ResultStore() {
          @Override
          public String record(final Map<String, Object> run,
              final List<Map<String, Object>> rows) {
            runs.add(run);
            own.add(run);
            return rule.setting() + "-run";
          }

          @Override
          public Optional<Boolean> recorded(final String stage) {
            return Optional.of(own.stream().anyMatch(r -> stage.equals(r.get("stage"))));
          }

          @Override
          public Optional<java.util.Set<SplitRule>> designs() {
            return Optional.of(own.stream().map(SplitRule::recordedBy)
                .collect(java.util.stream.Collectors.toSet()));
          }
        };
      }

      @Override
      public Optional<java.util.Set<SplitRule>> designs() {
        return Optional.of(randomRuns.stream().map(SplitRule::recordedBy)
            .collect(java.util.stream.Collectors.toSet()));
      }
    };
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  private static String refused(final CommandRunner step, final Context context) {
    final CommandFailedException e =
        assertThrows(CommandFailedException.class, () -> step.run(context));
    return String.join("\n", e.lines());
  }

  @Test
  @DisplayName("selection: a temporal matrix is refused under the default rule and selected "
      + "under SPLIT_RULE=temporal, which the run records; a random matrix is refused under "
      + "the temporal rule")
  void selection() throws Exception {
    final Path temporal = SelectionFixture.temporal(dir.resolve("temporal.tsv"));
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", temporal.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    final Function<String, String> get = settings::get;

    assertTrue(refused(new IssuesSimilarityElasticNetCommand(get), context(own()))
        .contains("seed " + ProjectSplit.SEED));
    assertFalse(Files.exists(parameters));

    settings.put(SplitRule.SETTING, "temporal");
    new IssuesSimilarityElasticNetCommand(get).run(context(own()));
    assertTrue(Files.exists(parameters));
    assertEquals(TemporalSplit.RULE, runs.getFirst().get("split_rule"));

    settings.put("MATRIX", SelectionFixture.write(dir.resolve("random.tsv"),
        Population.FIRST_COMMIT).toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("other.tsv").toString());
    assertTrue(refused(new IssuesSimilarityElasticNetCommand(get), context(own()))
        .contains("rule (temporal"));
    assertFalse(Files.exists(dir.resolve("other.tsv")));
    assertEquals(1, runs.size());
  }

  @Test
  @DisplayName("first-commit test step: the split checked against the declared rule before "
      + "anything is scored")
  void testStep() throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", SelectionFixture.temporal(dir.resolve("temporal.tsv")).toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("elastic-net.tsv").toString());
    settings.put(SplitRule.SETTING, "temporal");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own()));
    settings.put("REPORT_DIR", dir.resolve("test").toString());
    final Path parameters = dir.resolve("elastic-net.tsv");
    assertEquals(SplitRule.TEMPORAL,
        ElasticNetSelection.headerRule(Files.readAllLines(parameters).getFirst()));
    // the analogy estimate's choice and the forests' trees, as the temporal selection froze them
    final List<ElasticNetSelection.Parameter> frozen =
        ElasticNetSelection.read(Files.readAllLines(parameters));
    assertTrue(ElasticNetSelection.analogy(frozen) != null);
    assertEquals(500, ElasticNetSelection.trees(frozen).getAsInt());

    settings.remove(SplitRule.SETTING);
    final IssuesSimilarityTestCommand step =
        new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT);
    assertTrue(refused(step, context(own())).contains("seed " + ProjectSplit.SEED));
    assertFalse(Files.exists(dir.resolve("test")));
    assertEquals(1, runs.size());

    settings.put(SplitRule.SETTING, "temporal");
    step.run(context(own()));
    assertEquals(2, runs.size());
    final Path predictions = dir.resolve("test").resolve(IssuesSimilarityTestCommand.PREDICTIONS);
    assertTrue(Files.exists(predictions));
    // the opening of the temporal split's test, under its own stage name, with the fifteenth model
    final Map<String, Object> run = runs.getLast();
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_TEMPORAL_STAGE, run.get("stage"));
    assertEquals(TemporalSplit.RULE, run.get("split_rule"));
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_TEMPORAL_EXAMINED,
        run.get("outer_test_draw"));
    assertEquals(15, ((List<?>) run.get("models")).size());
    assertEquals(2, temporalRuns.size(), "selection and test use the temporal collection");
    assertTrue(((List<?>) run.get("models")).contains(app.issues.SimilarityScoring.ANALOGY));
    assertTrue(Files.readAllLines(predictions).getFirst()
        .contains(app.issues.SimilarityScoring.ANALOGY));
    assertTrue(run.containsKey("new_projects"));
    assertEquals(500, run.get("forest_trees"));
  }

  /** The analogy estimate's frozen choice the tests append for the temporal selection. */
  private static final AnalogyEstimate.Frozen ANALOGY =
      new AnalogyEstimate.Frozen("jaccard_median_10");

  @Test
  @DisplayName("first-commit test step: a parameters file whose header declares another split rule "
      + "than the run's is refused, as is a temporal one without the analogy estimate's choice "
      + "and a random one with it, before anything is scored or recorded")
  void parametersOfAnotherDesign() throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", SelectionFixture.write(dir.resolve("random.tsv"),
        Population.FIRST_COMMIT).toString());
    final Path parameters = dir.resolve("elastic-net.tsv");
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own()));
    settings.put("REPORT_DIR", dir.resolve("test").toString());
    final List<String> random = Files.readAllLines(parameters);
    assertEquals(SplitRule.RANDOM, ElasticNetSelection.headerRule(random.getFirst()));
    final IssuesSimilarityTestCommand step =
        new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT);

    // the header declares the temporal rule; the matrix and the run, the random one
    final List<String> temporal = new ArrayList<>(random);
    temporal.set(0, ElasticNetSelection.header(Population.FIRST_COMMIT, SplitRule.TEMPORAL));
    temporal.addAll(ElasticNetSelection.analogyLines(ANALOGY));
    Files.write(parameters, temporal);
    assertTrue(refused(step, context(own())).contains("written under the split rule temporal"));

    // a random file with the analogy estimate's choice: there it is exploratory
    final List<String> withAnalogy = new ArrayList<>(random);
    withAnalogy.addAll(ElasticNetSelection.analogyLines(ANALOGY));
    Files.write(parameters, withAnalogy);
    assertTrue(refused(step, context(own())).contains("exploratory"));
    assertFalse(Files.exists(dir.resolve("test")));
    assertEquals(1, runs.size());

    // the random file as the selection wrote it is scored, the fourteen alone
    Files.write(parameters, random);
    step.run(context(own()));
    assertEquals(2, runs.size());
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_STAGE, runs.getLast().get("stage"));
    assertEquals(14, ((List<?>) runs.getLast().get("models")).size());
    // a file without the trees' line, as every file of the random split: the forests at 300
    assertEquals(300, runs.getLast().get("forest_trees"));
  }

  @Test
  @DisplayName("first-commit test step: a temporal parameters file without the analogy "
      + "estimate's choice is refused, and so is one without the forests' tree count")
  void temporalWithoutAnalogy() throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", SelectionFixture.temporal(dir.resolve("temporal.tsv")).toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("elastic-net.tsv").toString());
    settings.put(SplitRule.SETTING, "temporal");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own()));
    settings.put("REPORT_DIR", dir.resolve("test").toString());
    final Path parameters = dir.resolve("elastic-net.tsv");
    final List<String> written = Files.readAllLines(parameters);
    Files.write(parameters, written.stream().filter(l -> !l.startsWith("analogy\t")).toList());
    assertTrue(refused(new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT),
        context(own())).contains("freezes no choice of the analogy estimate"));
    Files.write(parameters, written.stream()
        .filter(l -> !l.equals(ElasticNetSelection.treesLine(500))).toList());
    assertTrue(refused(new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT),
        context(own())).contains("gives no tree count for the forests"));
    assertFalse(Files.exists(dir.resolve("test")));
    assertEquals(1, runs.size());
  }

  @Test
  @DisplayName("an In Progress matrix under SPLIT_RULE=temporal is refused by every stage that "
      + "checks the split, before anything is written or recorded; a value that names no rule "
      + "too")
  void inProgressIsRandomAlone() throws Exception {
    final Path matrix = SelectionFixture.write(dir.resolve("in-progress.tsv"),
        Population.IN_PROGRESS);
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("elastic-net.tsv").toString());
    settings.put("RETROSPECTIVE_MATRIX", dir.resolve("retrospective.tsv").toString());
    settings.put("REPORT_DIR", dir.resolve("reports").toString());
    settings.put(SplitRule.SETTING, "temporal");
    final Function<String, String> get = settings::get;
    for (final CommandRunner step : List.of(new IssuesSimilarityElasticNetCommand(get),
        new IssuesSimilarityTestCommand(get), new IssuesRetrospectiveCommand(get))) {
      final String why = refused(step, context(defaults()));
      assertTrue(why.contains("SPLIT_RULE=temporal"), step + ": " + why);
    }
    settings.put(SplitRule.SETTING, "weekly");
    assertTrue(refused(new IssuesSimilarityElasticNetCommand(get), context(defaults()))
        .contains("random, temporal or revised"));
    assertTrue(runs.isEmpty());
    assertFalse(Files.exists(dir.resolve("elastic-net.tsv")));
    assertFalse(Files.exists(dir.resolve("retrospective.tsv")));
    assertFalse(Files.exists(dir.resolve("reports")));
  }
  @Test
  @DisplayName("the revised design, stage by stage on a matrix of the temporal sides: the "
      + "selection, the inner report and the test step read it as a temporal design, with the "
      + "analogy estimate and 500 trees, record split_design in the revised design's own "
      + "collection, and the test step its own stage name, never saying the test is read for the "
      + "first time")
  void revisedDesign() throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", SelectionFixture.temporal(dir.resolve("temporal.tsv")).toString());
    final Path parameters = dir.resolve("elastic-net.tsv");
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put(SplitRule.SETTING, "revised");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own()));
    final List<String> lines = Files.readAllLines(parameters);
    assertEquals(SplitRule.REVISED, ElasticNetSelection.headerRule(lines.getFirst()));
    final List<ElasticNetSelection.Parameter> frozen = ElasticNetSelection.read(lines);
    assertTrue(ElasticNetSelection.analogy(frozen) != null);
    assertEquals(500, ElasticNetSelection.trees(frozen).getAsInt());
    final Map<String, Object> selection = runs.getLast();
    assertEquals("revised", selection.get(SplitRule.DESIGN_FIELD));
    assertEquals(SplitRule.REVISED.rule(), selection.get(SplitRule.RULE_FIELD));
    assertEquals(500, selection.get("forest_trees"));
    assertTrue(selection.containsKey("analogy_estimate"));

    settings.put("REPORT_DIR", dir.resolve("inner").toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context(own()));
    assertTrue(Files.readString(dir.resolve("inner").resolve("similarity-inner-report.md"))
        .contains(app.issues.SimilarityScoring.ANALOGY));

    // the temporal design's parameters file is not the revised design's
    settings.put(SplitRule.SETTING, "temporal");
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("temporal-parameters.tsv").toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own()));
    settings.put(SplitRule.SETTING, "revised");
    settings.put("REPORT_DIR", dir.resolve("test").toString());
    final IssuesSimilarityTestCommand step =
        new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT);
    runs.clear();
    assertTrue(refused(step, context(own())).contains("written under the split rule temporal"));

    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    step.run(context(own()));
    final Map<String, Object> test = runs.getLast();
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE, test.get("stage"));
    assertEquals("issues-first-commit-test-revised", test.get("stage"));
    assertEquals("revised", test.get(SplitRule.DESIGN_FIELD));
    assertEquals(SplitRule.REVISED.rule(), test.get(SplitRule.RULE_FIELD));
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_EXAMINED,
        test.get("outer_test_draw"));
    for (final String field : List.of("outer_test", "outer_test_draw")) {
      final String said = (String) test.get(field);
      assertFalse(said.contains("first time"), field + ": " + said);
      assertTrue(said.contains("read under the revised design; the outer test had been read "
          + "once under the design of 02/11/2026"), field + ": " + said);
    }
    assertEquals(15, ((List<?>) test.get("models")).size());
    assertEquals(500, test.get("forest_trees"));
    assertTrue(randomRuns.isEmpty(), "nothing in the random design's collection");
    assertEquals(2, revisedRuns.size(), "the revised selection and test in their own collection");
    assertEquals(1, temporalRuns.size());
    assertTrue(asked.stream().filter(r -> r != SplitRule.RANDOM)
        .allMatch(r -> r == SplitRule.REVISED || r == SplitRule.TEMPORAL));
    assertTrue(asked.contains(SplitRule.REVISED));

    // opened once: a second run under the revised stage name is refused
    Files.delete(dir.resolve("test").resolve(IssuesSimilarityTestCommand.PREDICTIONS));
    assertTrue(refused(step, context(own())).contains("already holds a run of "
        + IssuesSimilarityTestCommand.FIRST_COMMIT_REVISED_STAGE));
  }
}
