package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step that opens the first-commit study's outer test (its ticket 05): it scores that
 * population with the frozen parameters, records the opening, and runs once.
 */
class FirstCommitTestStageTest {

  @TempDir Path dir;

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

  private static Context context(final PipelineConfig config, final ResultStore store) {
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  /** A store that tells whether the step ran, and keeps what it records. */
  private static class Store implements ResultStore {
    final List<Map<String, Object>> runs = new ArrayList<>();
    final Optional<Boolean> before;

    Store(final Optional<Boolean> before) {
      this.before = before;
    }

    @Override
    public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
      runs.add(run);
      return "run";
    }

    @Override
    public Optional<Boolean> recorded(final String stage) {
      assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_STAGE, stage);
      return runs.isEmpty() ? before : Optional.of(true);
    }
  }

  private final Map<String, String> settings = new HashMap<>();

  /** The fixture's first-commit matrix and the selection's parameters chosen on it. */
  private void selected() throws Exception {
    final Path matrix = SelectionFixture.write(dir.resolve("matrix.tsv"), Population.FIRST_COMMIT);
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("elastic-net.tsv").toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own(),
        (run, rows) -> "run"));
    settings.put("REPORT_DIR", dir.resolve("test").toString());
  }

  private IssuesSimilarityTestCommand step() {
    return new IssuesSimilarityTestCommand(settings::get, Population.FIRST_COMMIT);
  }

  private String refused(final PipelineConfig config, final ResultStore store) {
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> step().run(context(config, store)));
    return String.join("\n", e.lines());
  }

  @Test
  @DisplayName("scores the first-commit population once, recording the opening under its own "
      + "stage; a second run refuses, by the predictions there or by the run recorded")
  void opensOnce() throws Exception {
    selected();
    final Store store = new Store(Optional.of(false));
    step().run(context(own(), store));
    assertEquals(1, store.runs.size());
    final Map<String, Object> run = store.runs.getFirst();
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_STAGE, run.get("stage"));
    assertTrue(run.containsKey("outer_test_opened_at"));
    assertEquals(IssuesSimilarityTestCommand.FIRST_COMMIT_EXAMINED, run.get("outer_test_draw"));
    assertTrue(run.containsKey("scored_rows_with_java_code"));
    assertTrue(Files.exists(dir.resolve("test").resolve(IssuesSimilarityTestCommand.PREDICTIONS)));

    assertTrue(refused(own(), store).contains("opened once"));
    settings.put("REPORT_DIR", dir.resolve("again").toString());
    assertTrue(refused(own(), store).contains("already holds a run"));
    assertFalse(Files.exists(dir.resolve("again")));
    assertEquals(1, store.runs.size());
  }

  @Test
  @DisplayName("refuses the In Progress study's databases, a store that cannot tell whether it "
      + "ran, and an In Progress matrix, before reading anything")
  void refusesBeforeReading() throws Exception {
    selected();
    final Store never = new Store(Optional.of(false)) {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        throw new AssertionError("recorded " + run);
      }
    };
    assertTrue(refused(defaults(), never).contains("databases of its own"));
    assertTrue(refused(own(), new Store(Optional.empty())).contains("cannot tell"));
    settings.put("MATRIX", SelectionFixture.write(dir.resolve("in-progress.tsv"),
        Population.IN_PROGRESS).toString());
    assertTrue(refused(own(), never).contains("not a matrix of the first-commit"));
    assertFalse(Files.exists(dir.resolve("test")));
  }

  @Test
  @DisplayName("the In Progress study's step still refuses a first-commit matrix")
  void inProgressStepStaysSealed() throws Exception {
    selected();
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> new IssuesSimilarityTestCommand(settings::get).run(context(own(),
            new Store(Optional.of(false)))));
    assertTrue(String.join("\n", e.lines()).contains(IssuesSimilarityTestCommand.SEALED));
  }
}
