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
import app.issues.FirstCommit;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TemporalSplit;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extraction command: the first-commit population under the revised design, recorded in the
 * study's own databases only, in the revised design's run collection.
 */
class IssuesInProgressExtractCommandTest {

  @TempDir Path dir;

  private final Map<String, List<Map<String, Object>>> collections = new HashMap<>();
  /** The revised design's run collection, where every run is recorded. */
  private final List<Map<String, Object>> records = runs("runs_revised");
  private int loads;
  /** The corpus the source reads. */
  private final ExtractionFixture fixture = new ExtractionFixture();

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
    final StudySource inner = fixture.source();
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
    return context(config, new Store("runs"));
  }

  private Context context(final PipelineConfig config, final ResultStore store) {
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, source(), store, null);
  }

  /** The runs held by the collection {@code name}. */
  private List<Map<String, Object>> runs(final String name) {
    return collections.computeIfAbsent(name, k -> new ArrayList<>());
  }

  /**
   * A store of run collections held in memory, as the Mongo one keeps them: the design's in
   * {@code runs} with the design's suffix.
   */
  private final class Store implements ResultStore {
    private final String collection;

    Store(final String collection) {
      this.collection = collection;
    }

    @Override
    public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
      runs(collection).add(run);
      return collection + "-" + runs(collection).size();
    }

    @Override
    public ResultStore design(final SplitRule rule) {
      return new Store("runs" + rule.collectionSuffix());
    }

    @Override
    public Optional<Long> otherDesignRuns() {
      return Optional.of(runs(collection).stream().filter(r -> !SplitRule.recordedBy(r)).count());
    }
  }

  @Test
  @DisplayName("the extraction refuses the In Progress study's databases before reading "
      + "anything")
  void firstCommitRefusesDefaults() {
    final Path cache = dir.resolve("cache.tsv");
    for (final PipelineConfig c : List.of(defaults(),
        config("smartshark_first_commit", "smartshark_issues", "smartshark_tcc"),
        config("smartshark_tcc", "smartshark_first_commit_issues", "smartshark_tcc"))) {
      assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
          Map.of("CACHE", cache.toString())::get)
          .run(context(c)));
    }
    assertEquals(0, loads, "the corpus was not read");
    assertTrue(records.isEmpty(), "nothing was recorded");
    assertFalse(Files.exists(cache));
  }

  @Test
  @DisplayName("the extraction records its run in the configured store, with the cap and "
      + "the training target only, and writes the cache under its own split columns")
  void firstCommitWritesToConfigured() throws Exception {
    final Path cache = dir.resolve("first-commit.tsv");
    new IssuesInProgressExtractCommand(
        Map.of("CACHE", cache.toString())::get)
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
    assertEquals(sizes.get("outer_training"), sides.get("TRAIN") + sides.get("CROSSING"),
        "the outer training side, crossing issues included");
    assertEquals(sizes.get("outer_test"), sides.get("TEST"));
  }

  @Test
  @DisplayName("WAIT_FENCE_K is recorded in the run when it is not the official 1.5, which leaves "
      + "the record as it was; with none the fence is a word and the target rule says no wait is "
      + "capped")
  void firstCommitRecordsWaitFence() throws Exception {
    new IssuesInProgressExtractCommand(Map.of("CACHE", dir.resolve("a.tsv").toString())::get)
        .run(context(firstCommit()));
    assertFalse(records.getFirst().containsKey("wait_fence_k"));
    assertEquals(FirstCommit.RULE, records.getFirst().get("target_rule"));
    records.clear();
    collections.values().forEach(List::clear);
    new IssuesInProgressExtractCommand(Map.of("CACHE", dir.resolve("b.tsv").toString(),
        "WAIT_FENCE_K", "none")::get)
        .run(context(firstCommit()));
    final Map<String, Object> run = records.getFirst();
    assertEquals("none", run.get("wait_fence_k"));
    assertEquals(FirstCommit.rule(FirstCommit.NO_WAIT_CAP), run.get("target_rule"));
    assertEquals("none", ((Map<?, ?>) run.get("wait_cap")).get("fence"));
    assertEquals("none", ((Map<?, ?>) run.get("wait_cap")).get("cap"),
        "no cap, not the largest wait");
  }

  @Test
  @DisplayName("an invalid WAIT_FENCE_K is refused before anything is read")
  void firstCommitRefusesInvalidWaitFence() {
    for (final String bad : List.of("0", "-2", "NaN", "many")) {
      assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
          Map.of("CACHE", dir.resolve("c.tsv").toString(),
              "WAIT_FENCE_K", bad)::get).run(context(firstCommit())), bad);
    }
    assertEquals(0, loads, "the corpus was not read");
  }

  @Test
  @DisplayName("the first-commit extraction refuses, before reading anything, a run collection "
      + "that already holds a run of another design, or a store that cannot tell")
  void refusesAnotherDesign() {
    final Path cache = dir.resolve("cache.tsv");
    runs("runs_revised").add(new HashMap<>(Map.of("stage", "issues-in-progress-extract",
        "split_design", "temporal")));
    assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
        Map.of("CACHE", cache.toString())::get).run(context(firstCommit())));
    collections.values().forEach(List::clear);
    final ResultStore blind = (run, rows) -> "run";
    assertThrows(CommandFailedException.class, () -> new IssuesInProgressExtractCommand(
        Map.of("CACHE", cache.toString())::get).run(context(firstCommit(), blind)));
    assertEquals(0, loads, "the corpus was not read");
    assertTrue(collections.values().stream().allMatch(List::isEmpty), "nothing was recorded");
    assertFalse(Files.exists(cache));
  }

  @Test
  @DisplayName("the extraction records in the revised design's own "
      + "collection, under its own rule's words and design, the cuts and the issues crossing "
      + "them, and writes a cache whose sides the revised check accepts")
  void firstCommitRevised() throws Exception {
    final Path cache = dir.resolve("revised.tsv");
    new IssuesInProgressExtractCommand(Map.of("CACHE", cache.toString())::get)
        .run(context(firstCommit()));
    assertTrue(runs("runs").isEmpty(), "nothing outside the revised design's collection");
    assertEquals(1, runs("runs_revised").size());
    final Map<String, Object> run = runs("runs_revised").getFirst();
    assertEquals("revised", run.get("split_design"));
    assertEquals(SplitRule.REVISED.rule(), run.get("split_rule"));
    assertTrue(run.containsKey("split_cuts"));
    assertTrue(run.containsKey("crossing_the_cut"));
    assertTrue(run.containsKey("new_projects"));
    assertFalse(run.containsKey("split_seed"));
    assertTrue(SplitRule.recordedBy(run));
    assertEquals(List.of(), SplitRule.REVISED.differences(RowCache.read(cache),
        Population.FIRST_COMMIT));
  }

  @Test
  @DisplayName("the extraction records how many issues each level "
      + "leaves out of what it teaches, that validation and test keep every issue, the outer "
      + "training's target with and without its crossing issues, and the new projects by both "
      + "definitions, each labelled")
  void firstCommitRevisedRecord() throws Exception {
    final Path cache = dir.resolve("revised.tsv");
    new IssuesInProgressExtractCommand(Map.of("CACHE", cache.toString())::get)
        .run(context(firstCommit()));
    final Map<String, Object> run = runs("runs_revised").getFirst();
    final Map<String, Integer> verdicts = new HashMap<>();
    final Map<String, Integer> inner = new HashMap<>();
    RowCache.read(cache).forEach(e -> {
      verdicts.merge(e.outer(Population.FIRST_COMMIT), 1, Integer::sum);
      inner.merge(e.inner(Population.FIRST_COMMIT), 1, Integer::sum);
    });
    final Map<?, ?> left = (Map<?, ?>) run.get("crossing_left_out");
    assertEquals(verdicts.get("CROSSING"), left.get("outer_level"));
    assertEquals(inner.get("CROSSING"), left.get("inner_level"));
    assertEquals("validation and test keep every issue", left.get("scored_sides"));
    final Map<?, ?> crossing = (Map<?, ?>) run.get("crossing_the_cut");
    assertEquals(left.get("outer_level"), crossing.get("outer_training"));
    assertEquals(left.get("inner_level"), crossing.get("inner_fitting"));

    final Map<?, ?> sizes = (Map<?, ?>) run.get("split_sizes");
    final Map<?, ?> whole = (Map<?, ?>) run.get("outer_training_target");
    final Map<?, ?> teaching = (Map<?, ?>) run.get("outer_training_target_without_crossing");
    assertEquals(sizes.get("outer_training"), whole.get("n"));
    assertEquals(verdicts.get("TRAIN"), teaching.get("n"));
    assertTrue(((String) whole.get("issues")).contains("crossing issues included"),
        whole.toString());
    assertTrue(((String) teaching.get("issues")).contains("crossing issues left out"),
        teaching.toString());

    final Map<?, ?> fresh = (Map<?, ?>) run.get("new_projects");
    final Map<?, ?> side = (Map<?, ?>) run.get("new_projects_by_training_side");
    assertTrue(((String) fresh.get("definition")).contains("no issue that teaches"),
        fresh.toString());
    assertTrue(((String) side.get("definition")).contains("crossing issues included"),
        side.toString());
    for (final Map<?, ?> m : List.of(fresh, side)) {
      final Map<?, ?> perProject = (Map<?, ?>) m.get("issues_per_project");
      assertEquals(perProject.size(), m.get("projects"));
    }
  }

}
