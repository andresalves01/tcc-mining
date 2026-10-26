package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.issues.port.StudySource;
import app.pipeline.ExtractionFixture;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.port.SilentProgress;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extraction command: the In Progress population by default, as before; the first-commit
 * population with POPULATION=FIRST_COMMIT, recorded in the configured databases only.
 */
class IssuesInProgressExtractCommandTest {

  @TempDir Path dir;

  private final List<Map<String, Object>> records = new ArrayList<>();
  private int loads;

  private static PipelineConfig config(final String target, final String issues,
      final String prepared) {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", target, issues,
        prepared, Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  /** The defaults of the In Progress study. */
  private static PipelineConfig defaults() {
    return config("smartshark_tcc", "smartshark_issues", "smartshark_tcc");
  }

  /** The first-commit study's own databases, the prepared collections read from smartshark_tcc. */
  private static PipelineConfig firstCommit() {
    return config("smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc");
  }

  /** The fixture's source, counting how often the corpus is loaded. */
  private StudySource source() {
    final StudySource inner = new ExtractionFixture().source();
    return new StudySource() {
      @Override
      public Corpus load() {
        loads++;
        return inner.load();
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        return inner.texts(ids);
      }

      @Override
      public Map<String, DurationEvidence> durationEvidence(final List<RowCache.Entry> rows,
          final Set<String> bots) {
        return inner.durationEvidence(rows, bots);
      }

      @Override
      public FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachments) {
        return inner.firstCommitFacts(commitIds, shas, attachments);
      }

      @Override
      public String describe() {
        return inner.describe();
      }
    };
  }

  private Context context(final PipelineConfig config) {
    final ResultStore store = (run, rows) -> {
      records.add(run);
      return "run-" + records.size();
    };
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, source(), store, null);
  }

  private static String goldenCache() throws Exception {
    try (InputStream in = app.pipeline.ExtractionFixture.class
        .getResourceAsStream("in-progress-extract-golden.txt")) {
      assertNotNull(in);
      final String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return all.substring(0, all.indexOf("split Split["));
    }
  }

  @Test
  @DisplayName("without POPULATION the command extracts the In Progress population as before: "
      + "the same cache, byte for byte, and the same run record")
  void inProgressByDefault() throws Exception {
    final Path cache = dir.resolve("cache.tsv");
    new IssuesInProgressExtractCommand(Map.of("CACHE", cache.toString())::get)
        .run(context(defaults()));
    assertEquals(goldenCache(), Files.readString(cache));
    assertEquals(1, records.size());
    final Map<String, Object> run = records.getFirst();
    assertEquals(List.of("stage", "gate_time_travel", "rows", "population", "split_rule",
        "split_seed", "split_share", "split_sizes"), List.copyOf(run.keySet()));
    assertEquals("issues-in-progress-extract", run.get("stage"));
    assertEquals(app.issues.BusinessDays.POPULATION, run.get("population"));
    assertEquals(26, run.get("rows"));
    assertEquals(List.of("in_progress", "zero_business_days", "drawn", "projects",
        "outer_training", "outer_test", "inner_fitting", "inner_validation"),
        List.copyOf(((Map<?, ?>) run.get("split_sizes")).keySet()));
  }

  @Test
  @DisplayName("POPULATION=IN_PROGRESS is the default spelled out")
  void inProgressNamed() throws Exception {
    final Path cache = dir.resolve("cache.tsv");
    new IssuesInProgressExtractCommand(
        Map.of("CACHE", cache.toString(), "POPULATION", "IN_PROGRESS")::get)
        .run(context(defaults()));
    assertEquals(goldenCache(), Files.readString(cache));
  }

  @Test
  @DisplayName("POPULATION=FIRST_COMMIT refuses the In Progress study's databases before reading "
      + "anything")
  void firstCommitRefusesDefaults() {
    final Path cache = dir.resolve("cache.tsv");
    for (final PipelineConfig c : List.of(defaults(),
        config("smartshark_first_commit", "smartshark_issues", "smartshark_tcc"),
        config("smartshark_tcc", "smartshark_first_commit_issues", "smartshark_tcc"))) {
      assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
          Map.of("CACHE", cache.toString(), "POPULATION", "FIRST_COMMIT")::get)
          .run(context(c)));
    }
    assertEquals(0, loads, "the corpus was not read");
    assertTrue(records.isEmpty(), "nothing was recorded");
    assertFalse(Files.exists(cache));
  }

  @Test
  @DisplayName("POPULATION=FIRST_COMMIT records its run in the configured store, with the cap and "
      + "the training target only, and writes the cache under its own split columns")
  void firstCommitWritesToConfigured() throws Exception {
    final Path cache = dir.resolve("first-commit.tsv");
    new IssuesInProgressExtractCommand(
        Map.of("CACHE", cache.toString(), "POPULATION", "first_commit")::get)
        .run(context(firstCommit()));
    assertEquals(1, loads);
    assertEquals(1, records.size());
    final Map<String, Object> run = records.getFirst();
    assertEquals("FIRST_COMMIT", run.get("study_population"));
    assertEquals("smartshark_tcc", run.get("prepared_db"));
    assertEquals(true, run.get("gate_time_travel"));
    final Map<?, ?> sizes = (Map<?, ?>) run.get("split_sizes");
    final Map<?, ?> target = (Map<?, ?>) run.get("outer_training_target");
    assertEquals(sizes.get("outer_training"), target.get("n"),
        "the target summarised is the outer training's");
    assertTrue(((Map<?, ?>) run.get("wait_cap")).containsKey("cap"));
    assertEquals(IssuesFirstCommitExtractTask.TARGET_COLUMNS, run.get("target_columns"));

    final List<String> header = List.of(Files.readAllLines(cache).getFirst().split("\t"));
    assertTrue(header.contains("primeiro_commit_externa"));
    assertFalse(header.contains("andamento_externa"));
    final Map<String, Integer> sides = new HashMap<>();
    RowCache.read(cache).forEach(e -> sides.merge(e.outer(Population.FIRST_COMMIT), 1,
        Integer::sum));
    assertEquals(sizes.get("outer_training"), sides.get("TRAIN"));
    assertEquals(sizes.get("outer_test"), sides.get("TEST"));
  }

  @Test
  @DisplayName("an unknown POPULATION is refused")
  void unknownPopulation() {
    assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
        Map.of("CACHE", dir.resolve("x.tsv").toString(), "POPULATION", "CREATION")::get)
        .run(context(firstCommit())));
    assertEquals(0, loads);
  }
}
