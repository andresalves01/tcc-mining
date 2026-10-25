package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Extractor;
import app.issues.RowCache;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.ExtractionFixture;
import app.pipeline.IssuesInProgressExtractTask;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.port.SilentProgress;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The In Progress stages after the extraction -- the team columns, the similarity stage and the
 * retrospective stage -- pinned to what they gave before the first-commit population reached
 * them: the golden file was written by this same test at commit 6fcdfe1, the base of ticket 03 of
 * the first-commit study, and must not change.
 */
class IssuesInProgressStagesGoldenTest {

  static final String GOLDEN = "in-progress-stages-golden.txt";

  @TempDir Path dir;

  private static PipelineConfig defaults() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", "smartshark_tcc",
        "smartshark_issues", "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  /** The three stages on the fixture's In Progress extraction, their files and records as text. */
  static String stages(final Path dir) throws Exception {
    final ExtractionFixture fixture = new ExtractionFixture();
    final IssuesInProgressExtractTask.Report extraction =
        new IssuesInProgressExtractTask(fixture.source(), SilentProgress.INSTANCE).run();
    final StringBuilder out = new StringBuilder();

    // the team stage's file
    final Path team = dir.resolve("team.tsv");
    TeamColumns.write(team, extraction.team());
    out.append("== team\n").append(Files.readString(team));

    // the similarity stage's matrix and diagnostics
    final List<RowCache.Entry> joined =
        TeamColumns.join(extraction.entries(), TeamColumns.read(team));
    final IssuesSimilarityTask.Report similarity = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans()).run(joined);
    assertEquals(List.of(), similarity.refused());
    final List<String> columns = new ArrayList<>(extraction.columns());
    columns.addAll(Extractor.TEAM_REVISED);
    final Path matrix = dir.resolve("augmented.tsv");
    IssuesSimilarityCommand.writeMatrix(matrix, similarity, columns);
    out.append("== similarity matrix\n").append(Files.readString(matrix));
    for (final Map.Entry<String, List<String>> f :
        IssuesSimilarityCommand.diagnostics(similarity).entrySet()) {
      out.append("== ").append(f.getKey()).append('\n');
      f.getValue().forEach(line -> out.append(line).append('\n'));
    }
    out.append("== sources ").append(IssuesSimilarityCommand.sources(similarity)).append('\n');
    out.append("== changed ").append(IssuesSimilarityCommand.changedByActiveTime(similarity))
        .append('\n');
    out.append("== bots ").append(new java.util.TreeSet<>(similarity.bots())).append('\n');

    // the retrospective stage, through its command
    final Path retrospective = dir.resolve("retrospective.tsv");
    final List<Map<String, Object>> records = new ArrayList<>();
    final ResultStore store = (run, rows) -> {
      records.add(run);
      return "run";
    };
    new IssuesRetrospectiveCommand(Map.of("MATRIX", matrix.toString(), "RETROSPECTIVE_MATRIX",
        retrospective.toString())::get).run(new Context(defaults(), null, null, null, null, null,
        null, null, null, null, null, SilentProgress.INSTANCE, fixture.source(), store,
        StagesFixture.methods()));
    out.append("== retrospective matrix\n").append(Files.readString(retrospective));
    final Map<String, Object> run = new LinkedHashMap<>(records.getFirst());
    run.remove("seconds");
    run.remove("matrix_sha256");
    run.remove("retrospective_matrix_sha256");
    out.append("== retrospective record ").append(new TreeMap<>(run)).append('\n');
    return out.toString();
  }

  static String golden() throws Exception {
    try (InputStream in = IssuesInProgressStagesGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertNotNull(in, "the golden file " + GOLDEN);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("the In Progress team, similarity and retrospective stages write, byte for byte, "
      + "what they wrote before the first-commit population reached them")
  void unchanged() throws Exception {
    final String actual = stages(dir);
    final Path out = Path.of("build", "golden", GOLDEN);
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);
    assertEquals(golden(), actual);
  }
}
