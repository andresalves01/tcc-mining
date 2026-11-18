package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.cli.Context;
import app.issues.Extractor;
import app.issues.SplitRule;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.ExtractionFixture;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.IssuesInProgressExtractTask;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first-commit study's temporal design as it was opened on 02/11/2026, pinned so it stays
 * reproducible once the revised design is added beside it: the extraction under {@code
 * SPLIT_RULE=temporal} (its cache and run record), the similarity stage's columns on that
 * extraction, and the selection and the inner report on {@link SelectionFixture#temporal}. The
 * golden files were written by this same test at commit 20e50ad, the base of the revised design,
 * before any of its code, and must not change.
 */
class TemporalDesignGoldenTest {

  static final String EXTRACT_GOLDEN = "first-commit-temporal-extract-golden.txt";
  static final String SIMILARITY_GOLDEN = "first-commit-temporal-similarity-golden.txt";
  static final String SELECTION_GOLDEN = "first-commit-temporal-selection-golden.txt";

  @TempDir Path dir;

  /**
   * A store of the temporal design's runs held in memory: the random design's store refuses to
   * record, so a run that lands there fails the test.
   */
  private static final class Store implements ResultStore {
    final List<Map<String, Object>> runs = new ArrayList<>();
    final List<List<Map<String, Object>>> rows = new ArrayList<>();

    @Override
    public String record(final Map<String, Object> run, final List<Map<String, Object>> r) {
      throw new AssertionError("a temporal run recorded in the random design's collection");
    }

    @Override
    public ResultStore design(final SplitRule rule) {
      assertEquals(SplitRule.TEMPORAL, rule);
      return new ResultStore() {
        @Override
        public String record(final Map<String, Object> run, final List<Map<String, Object>> r) {
          runs.add(run);
          rows.add(r);
          return "temporal-run-" + runs.size();
        }

        @Override
        public Optional<Set<SplitRule>> designs() {
          final Set<SplitRule> out = new HashSet<>();
          runs.forEach(r -> out.add(SplitRule.recordedBy(r)));
          return Optional.of(out);
        }

        @Override
        public Optional<Boolean> recorded(final String stage) {
          return Optional.of(runs.stream().anyMatch(r -> stage.equals(r.get("stage"))));
        }
      };
    }
  }

  private static Context context(final Store store) {
    return new Context(IssuesSelectionStagesGoldenTest.own(), null, null, null, null, null, null,
        null, null, null, null, SilentProgress.INSTANCE, new ExtractionFixture().source(), store,
        null);
  }

  private static void check(final String golden, final String actual) throws Exception {
    final Path out = Path.of("build", "golden", golden);
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);
    assertEquals(IssuesSelectionStagesGoldenTest.golden(golden), actual);
  }

  @Test
  @DisplayName("the first-commit extraction under SPLIT_RULE=temporal writes and records, byte "
      + "for byte, what it did when the temporal test was opened on 02/11/2026")
  void extractionUnchanged() throws Exception {
    final Path cache = dir.resolve("temporal.tsv");
    final Store store = new Store();
    new IssuesInProgressExtractCommand(Map.of("CACHE", cache.toString(), "POPULATION",
        "FIRST_COMMIT", SplitRule.SETTING, "temporal")::get).run(context(store));
    assertEquals(1, store.runs.size());
    check(EXTRACT_GOLDEN, Files.readString(cache) + "== run " + store.runs.getFirst() + "\n");
  }

  @Test
  @DisplayName("the similarity stage on the temporal extraction writes, byte for byte, the "
      + "columns, diagnostics, cluster fits and histories of 02/11/2026")
  void similarityUnchanged() throws Exception {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report r = new IssuesFirstCommitExtractTask(
        fixture.source(), SilentProgress.INSTANCE, SplitRule.TEMPORAL).run().extraction();
    final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans(), Population.FIRST_COMMIT,
        SplitRule.TEMPORAL).run(TeamColumns.join(r.entries(), r.team()));
    assertEquals(List.of(), s.refused());
    final List<String> columns = new ArrayList<>(r.columns());
    columns.addAll(Extractor.TEAM_REVISED);
    final Path matrix = dir.resolve("augmented.tsv");
    IssuesSimilarityCommand.writeMatrix(matrix, s, columns, Population.FIRST_COMMIT);
    final StringBuilder out = new StringBuilder();
    out.append("== similarity matrix\n").append(Files.readString(matrix));
    for (final Map.Entry<String, List<String>> f :
        IssuesSimilarityCommand.diagnostics(s, Population.FIRST_COMMIT).entrySet()) {
      out.append("== ").append(f.getKey()).append('\n');
      f.getValue().forEach(line -> out.append(line).append('\n'));
    }
    out.append("== sizes ").append(s.sizes()).append('\n');
    out.append("== cluster fits ").append(IssuesSimilarityCommand.fits(s.result().clusterFits()))
        .append('\n');
    out.append("== histories ").append(s.result().histories()).append('\n');
    out.append("== coverage ").append(s.result().coverage()).append('\n');
    check(SIMILARITY_GOLDEN, out.toString());
  }

  @Test
  @DisplayName("the selection and the inner report under SPLIT_RULE=temporal write and record, "
      + "byte for byte, what they did when the temporal test was opened on 02/11/2026")
  void selectionUnchanged() throws Exception {
    final Path matrix = SelectionFixture.temporal(dir.resolve("matrix.tsv"));
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new LinkedHashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put(SplitRule.SETTING, "temporal");
    final Function<String, String> get = settings::get;
    final Store store = new Store();
    final StringBuilder out = new StringBuilder();

    new IssuesSimilarityElasticNetCommand(get).run(context(store));
    out.append("== parameters\n").append(Files.readString(parameters));
    out.append(IssuesSelectionStagesGoldenTest.record("elastic-net", store.runs.get(0),
        store.rows.get(0)));

    settings.put("REPORT_DIR", dir.resolve("inner").toString());
    new IssuesSimilarityInnerReportCommand(get).run(context(store));
    assertEquals(1, store.runs.size(), "the inner report records nothing");
    out.append(IssuesSelectionStagesGoldenTest.files(dir.resolve("inner"), dir));
    check(SELECTION_GOLDEN, out.toString());
  }
}
