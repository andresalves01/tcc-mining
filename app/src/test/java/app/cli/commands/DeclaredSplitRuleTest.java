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
        return "run";
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(runs.stream().anyMatch(r -> stage.equals(r.get("stage"))));
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

    settings.remove(SplitRule.SETTING);
    final IssuesSimilarityTestCommand step =
        new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT);
    assertTrue(refused(step, context(own())).contains("seed " + ProjectSplit.SEED));
    assertFalse(Files.exists(dir.resolve("test")));
    assertEquals(1, runs.size());

    settings.put(SplitRule.SETTING, "temporal");
    step.run(context(own()));
    assertEquals(2, runs.size());
    assertTrue(Files.exists(dir.resolve("test").resolve(IssuesSimilarityTestCommand.PREDICTIONS)));
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
        .contains("random or temporal"));
    assertTrue(runs.isEmpty());
    assertFalse(Files.exists(dir.resolve("elastic-net.tsv")));
    assertFalse(Files.exists(dir.resolve("retrospective.tsv")));
    assertFalse(Files.exists(dir.resolve("reports")));
  }
}
