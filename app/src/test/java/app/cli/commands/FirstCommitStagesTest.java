package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.DescriptionCleaner;
import app.issues.DurationEvidence;
import app.issues.Embedder;
import app.issues.Extractor;
import app.issues.FirstCommit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.issues.port.StudySource;
import app.pipeline.ExtractionFixture;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.IssuesInProgressExtractTask;
import app.pipeline.IssuesRetrospectiveTask;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.port.SilentProgress;
import app.study.Row;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The team, similarity and retrospective stages on the first-commit population of {@link
 * ExtractionFixture}: read at the first commit, the histories on the target of the extraction,
 * the retrospective window from the first commit to the first resolution, and the study's own
 * databases.
 */
class FirstCommitStagesTest {

  private static final long H = 3_600_000L;

  @TempDir Path dir;

  private final List<Map<String, Object>> records = new ArrayList<>();
  private int loads;

  private static PipelineConfig config(final String target, final String issues) {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", target, issues,
        "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private static PipelineConfig defaults() {
    return config("smartshark_tcc", "smartshark_issues");
  }

  private static PipelineConfig firstCommit() {
    return config("smartshark_first_commit", "smartshark_first_commit_issues");
  }

  /** The fixture's source, counting how often the corpus is loaded. */
  private StudySource counted(final StudySource inner) {
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
      public app.issues.FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachments) {
        return inner.firstCommitFacts(commitIds, shas, attachments);
      }

      @Override
      public String describe() {
        return inner.describe();
      }
    };
  }

  private Context context(final PipelineConfig config, final ExtractionFixture fixture) {
    final ResultStore store = (run, rows) -> {
      records.add(run);
      return "run-" + records.size();
    };
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, counted(fixture.source()), store, StagesFixture.methods());
  }

  private static IssuesInProgressExtractTask.Report extraction(final ExtractionFixture fixture) {
    return new IssuesFirstCommitExtractTask(fixture.source(), SilentProgress.INSTANCE).run()
        .extraction();
  }

  /** The similarity stage on the fixture's first-commit extraction. */
  private static IssuesSimilarityTask.Report similarity(final ExtractionFixture fixture,
      final IssuesInProgressExtractTask.Report r, final Embedder embedder) {
    final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, embedder, new SmileKMeans(), Population.FIRST_COMMIT)
        .run(TeamColumns.join(r.entries(), r.team()));
    assertEquals(List.of(), s.refused());
    return s;
  }

  /** The similarity stage's matrix of the first-commit population, written to {@code path}. */
  private static void matrix(final ExtractionFixture fixture, final Path path)
      throws Exception {
    final IssuesInProgressExtractTask.Report r = extraction(fixture);
    final List<String> columns = new ArrayList<>(r.columns());
    columns.addAll(Extractor.TEAM_REVISED);
    IssuesSimilarityCommand.writeMatrix(path, similarity(fixture, r, StagesFixture.WORDS),
        columns, Population.FIRST_COMMIT);
  }

  private static RowCache.Entry entry(final List<RowCache.Entry> entries, final String id) {
    return entries.stream().filter(e -> e.unit().issueId().equals(id)).findFirst().orElseThrow();
  }

  // ------------------------------------------------------------------ team

  @Test
  @DisplayName("team, POPULATION=FIRST_COMMIT: refuses the In Progress study's databases before "
      + "reading anything; otherwise writes the population's team columns, read at the first "
      + "commit, and records the population")
  void teamOfTheFirstCommit() throws Exception {
    final Path file = dir.resolve("team.tsv");
    final Map<String, String> settings = Map.of("TEAM_COLUMNS", file.toString(), "POPULATION",
        "FIRST_COMMIT");
    for (final PipelineConfig c : List.of(defaults(),
        config("smartshark_first_commit", "smartshark_issues"),
        config("smartshark_tcc", "smartshark_first_commit_issues"))) {
      assertThrows(CommandFailedException.class, () -> new IssuesInProgressTeamCommand(
          settings::get).run(context(c, new ExtractionFixture())));
    }
    assertEquals(0, loads);
    assertTrue(records.isEmpty());
    assertFalse(Files.exists(file));

    final ExtractionFixture fixture = new ExtractionFixture();
    new IssuesInProgressTeamCommand(settings::get).run(context(firstCommit(), fixture));
    final Map<String, Map<String, Double>> team = TeamColumns.read(file);
    final IssuesInProgressExtractTask.Report r = extraction(new ExtractionFixture());
    assertEquals(new java.util.TreeMap<>(r.team()), new java.util.TreeMap<>(team));
    assertEquals(r.entries().stream().map(e -> e.unit().issueId()).collect(Collectors.toSet()),
        team.keySet());
    assertEquals("FIRST_COMMIT", records.getFirst().get("study_population"));
  }

  @Test
  @DisplayName("team without POPULATION: the In Progress columns, as the golden file has them")
  void teamOfInProgress() throws Exception {
    final Path file = dir.resolve("team.tsv");
    new IssuesInProgressTeamCommand(Map.of("TEAM_COLUMNS", file.toString())::get)
        .run(context(defaults(), new ExtractionFixture()));
    final String golden = IssuesInProgressStagesGoldenTest.golden();
    assertEquals(golden.substring("== team\n".length(), golden.indexOf("== similarity matrix")),
        Files.readString(file));
    assertFalse(records.getFirst().containsKey("study_population"));
  }

  // ------------------------------------------------------------------ similarity

  @Test
  @DisplayName("similarity: the description is the one at the first commit, an edit after it "
      + "never read")
  void descriptionAtTheFirstCommit() {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report r = extraction(fixture);
    final String id = r.entries().getFirst().unit().issueId();
    final String before = fixture.description(id);
    fixture.describedLater(id, "zebra quokka narwhal", r.entries().getFirst().unit().startedAt()
        .toEpochMilli() + H);
    final List<String> embedded = new ArrayList<>();
    final Embedder recording = text -> {
      embedded.add(text);
      return StagesFixture.WORDS.embed(text);
    };
    similarity(fixture, r, recording);
    assertTrue(embedded.contains(DescriptionCleaner.clean(before)), embedded.toString());
    assertTrue(embedded.stream().noneMatch(t -> t.contains("zebra")), embedded.toString());

    // an edit before the first commit is read
    final ExtractionFixture earlier = new ExtractionFixture();
    earlier.describedLater(id, "zebra quokka narwhal", r.entries().getFirst().unit().startedAt()
        .toEpochMilli() - H);
    embedded.clear();
    similarity(earlier, extraction(earlier), recording);
    assertTrue(embedded.contains("zebra quokka narwhal"), embedded.toString());
  }

  @Test
  @DisplayName("similarity: every median reads the first-commit target of its training side "
      + "only; the inner ones never a validation or test label, the outer ones never a test "
      + "label; no day boundary or active business days are written")
  void mediansReadTheTrainingTarget() {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report r = extraction(fixture);
    // labels by side: inner fitting 1000s, inner validation 5000s, outer test 9000s
    final List<RowCache.Entry> labelled = new ArrayList<>();
    int n = 0;
    for (final RowCache.Entry e : r.entries()) {
      final String outer = e.outer(Population.FIRST_COMMIT);
      final String inner = e.inner(Population.FIRST_COMMIT);
      final int base = "TEST".equals(outer) ? 9000 : "TEST".equals(inner) ? 5000 : 1000;
      final Map<String, Double> values = new HashMap<>(e.row().values());
      values.put(FirstCommit.TARGET_COLUMN, (double) (base + n++));
      labelled.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans(), Population.FIRST_COMMIT)
        .run(TeamColumns.join(labelled, r.team()));
    assertEquals(List.of(), s.refused());
    int inner = 0;
    int outer = 0;
    for (final RowCache.Entry e : s.entries()) {
      assertFalse(e.row().values().containsKey(BusinessDays.ACTIVE));
      assertFalse(e.row().values().containsKey(BusinessDays.BOUNDARY));
      for (final String column : Augmentation.columns()) {
        final Double v = e.row().values().get(column);
        if (v == null) {
          continue;
        }
        if (column.endsWith(Augmentation.Variant.INNER.suffix())) {
          assertTrue(v >= 1000 && v < 5000, column + " of " + e.unit().key() + " is " + v);
          inner++;
        } else {
          assertTrue(v >= 1000 && v < 9000, column + " of " + e.unit().key() + " is " + v);
          outer++;
        }
      }
    }
    assertTrue(inner > 50 && outer > 50, inner + " " + outer);
  }

  @Test
  @DisplayName("similarity: a first-commit row without its target or the attributes of "
      + "decision 16a refuses before the corpus is read")
  void similarityRefusesWithoutTarget() {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report r = extraction(fixture);
    for (final String column : List.of(FirstCommit.TARGET_COLUMN, FirstCommit.FIRST_SHARE)) {
      final List<RowCache.Entry> without = new ArrayList<>();
      for (final RowCache.Entry e : r.entries()) {
        final Map<String, Double> values = new HashMap<>(e.row().values());
        values.remove(column);
        without.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
            e.row().absent()), e.parts()));
      }
      loads = 0;
      final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(counted(fixture.source()),
          SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans(),
          Population.FIRST_COMMIT).run(without);
      assertFalse(s.refused().isEmpty(), column);
      assertEquals(0, loads);
    }
  }

  @Test
  @DisplayName("similarity command, a first-commit matrix: refuses the In Progress study's "
      + "databases before the model or the corpus is read")
  void similarityCommandRefusesDefaults() throws Exception {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report r = extraction(fixture);
    final Path cache = dir.resolve("cache.tsv");
    final Path team = dir.resolve("team.tsv");
    final Path matrix = dir.resolve("matrix.tsv");
    RowCache.write(cache, r.entries(), r.columns(), List.of(Population.FIRST_COMMIT));
    TeamColumns.write(team, r.team());
    final Map<String, String> settings = Map.of("CACHE", cache.toString(), "TEAM_COLUMNS",
        team.toString(), "MATRIX", matrix.toString(), "REPORT_DIR", dir.resolve("r").toString(),
        "MODEL_DIR", dir.resolve("no-model").toString(), "EMBEDDING_CACHE",
        dir.resolve("e").toString());
    final CommandFailedException refused = assertThrows(CommandFailedException.class,
        () -> new IssuesSimilarityCommand(settings::get).run(context(defaults(), fixture)));
    assertTrue(String.join("\n", refused.lines()).contains("databases of its own"),
        refused.lines().toString());
    // with the study's databases it goes on, to the model the test does not have
    final CommandFailedException model = assertThrows(CommandFailedException.class,
        () -> new IssuesSimilarityCommand(settings::get).run(context(firstCommit(), fixture)));
    assertTrue(String.join("\n", model.lines()).contains("MODEL_DIR"), model.lines().toString());
    assertEquals(0, loads);
    assertFalse(Files.exists(matrix));
    assertTrue(records.isEmpty());
  }

  // ------------------------------------------------------------------ retrospective

  /** The retrospective columns of {@code fixture}'s first-commit population. */
  private static List<RowCache.Entry> retrospective(final ExtractionFixture fixture,
      final Path dir) throws Exception {
    final Path matrix = dir.resolve("m-" + System.nanoTime() + ".tsv");
    matrix(fixture, matrix);
    final IssuesRetrospectiveTask.Report r = new IssuesRetrospectiveTask(fixture.source(),
        StagesFixture.methods(), SilentProgress.INSTANCE, Population.FIRST_COMMIT)
        .run(RowCache.read(matrix));
    assertEquals(List.of(), r.refused());
    return r.entries();
  }

  @Test
  @DisplayName("retrospective: the window runs from the first commit to the first resolution; "
      + "a comment between the first In Progress and the first commit counts for In Progress "
      + "only, one after the first commit for both")
  void retrospectiveWindow() throws Exception {
    final ExtractionFixture plain = new ExtractionFixture();
    final List<RowCache.Entry> base = retrospective(plain, dir);
    final String id = base.stream().map(e -> e.unit().issueId())
        .filter(i -> i.equals(ExtractionFixture.id(2))).findFirst()
        .orElse(base.getFirst().unit().issueId());
    final RowCache.Entry row = entry(base, id);
    final long start = row.unit().startedAt().toEpochMilli();

    final ExtractionFixture between = new ExtractionFixture();
    between.comment(id, "zed", start - 30 * 60_000L);
    final ExtractionFixture inside = new ExtractionFixture();
    inside.comment(id, "zed", start + H);
    final double comments = row.row().values().get(Retrospective.COMMENTS);
    assertEquals(comments, entry(retrospective(between, dir), id).row().values()
        .get(Retrospective.COMMENTS), "before the first commit: outside the window");
    assertEquals(comments + 1, entry(retrospective(inside, dir), id).row().values()
        .get(Retrospective.COMMENTS), "after the first commit: inside");

    // the In Progress stage counts the comment before the first commit, which is after its start
    final IssuesInProgressExtractTask.Report ip = new IssuesInProgressExtractTask(plain.source(),
        SilentProgress.INSTANCE).run();
    final long ipStart = entry(ip.entries(), id).unit().startedAt().toEpochMilli();
    assertTrue(ipStart < start - 30 * 60_000L, "the comment is after the first In Progress");
    assertEquals(inProgressComments(plain, id) + 1, inProgressComments(between, id));

    // the first-commit rows keep their target, its boundary and the attributes of 16a
    for (final RowCache.Entry e : base) {
      assertTrue(e.row().values().keySet().containsAll(BusinessDays.columns(
          Population.FIRST_COMMIT)));
      assertTrue(e.row().values().keySet().containsAll(FirstCommit.ATTRIBUTE_COLUMNS));
    }
  }

  private static double inProgressComments(final ExtractionFixture fixture, final String id) {
    final IssuesInProgressExtractTask.Report r =
        new IssuesInProgressExtractTask(fixture.source(), SilentProgress.INSTANCE).run();
    final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans())
        .run(TeamColumns.join(r.entries(), r.team()));
    final IssuesRetrospectiveTask.Report t = new IssuesRetrospectiveTask(fixture.source(),
        StagesFixture.methods(), SilentProgress.INSTANCE).run(s.entries());
    return entry(t.entries(), id).row().values().get(Retrospective.COMMENTS);
  }

  @Test
  @DisplayName("retrospective command, a first-commit matrix: refuses the In Progress study's "
      + "databases; otherwise writes the nine columns under the first-commit split and records "
      + "the population and the window")
  void retrospectiveCommand() throws Exception {
    final ExtractionFixture fixture = new ExtractionFixture();
    final Path matrix = dir.resolve("matrix.tsv");
    final Path out = dir.resolve("retrospective.tsv");
    matrix(fixture, matrix);
    final Map<String, String> settings = Map.of("MATRIX", matrix.toString(),
        "RETROSPECTIVE_MATRIX", out.toString());
    assertThrows(CommandFailedException.class, () -> new IssuesRetrospectiveCommand(
        settings::get).run(context(defaults(), fixture)));
    assertEquals(0, loads);
    assertFalse(Files.exists(out));

    new IssuesRetrospectiveCommand(settings::get).run(context(firstCommit(), fixture));
    final List<String> header = List.of(Files.readAllLines(out).getFirst().split("\t"));
    assertTrue(header.contains("primeiro_commit_externa"));
    assertFalse(header.contains("andamento_externa"));
    assertTrue(header.containsAll(Retrospective.COLUMNS));
    assertTrue(header.containsAll(FirstCommit.ATTRIBUTE_COLUMNS));
    assertFalse(header.contains(IssuesInProgressExtractTask.PAUSED));
    assertFalse(header.contains(BusinessDays.ACTIVE));
    final Map<String, Object> run = records.getFirst();
    assertEquals("FIRST_COMMIT", run.get("study_population"));
    assertTrue(run.get("window").toString().startsWith("[first commit"));

    // the coverage and every later stage read the first-commit target as the business days
    final BusinessDays.Population population = BusinessDays.population(RowCache.read(out),
        Augmentation.Variant.INNER);
    for (final RowCache.Entry e : population.entries()) {
      assertEquals(e.row().values().get(FirstCommit.TARGET_COLUMN).intValue(),
          e.unit().businessDays());
    }
    assertEquals(RowCache.read(out).size(), population.entries().size());
  }
}
