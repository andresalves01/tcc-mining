package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The guard on the revised design's outer test ({@link IssuesSimilarityTestCommand#revisedUnready}):
 * it opens only on a matrix whose extraction run declares the crossing issues left out of what the
 * training side teaches, and whose similarity run, found through the retrospective run that wrote
 * the matrix, declares point-in-time histories. It reads run records alone.
 */
class RevisedOpeningGuardTest {

  private static final String MATRIX = "retrospective-sha";
  private static final String SIMILARITY = "similarity-sha";

  /** A run collection, newest first, as {@link ResultStore#recordedRuns} returns it. */
  private static ResultStore store(final List<Map<String, Object>> runs) {
    return new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        throw new AssertionError("the guard records nothing");
      }

      @Override
      public Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
        return Optional.of(runs.stream().filter(r -> stage.equals(r.get("stage"))).toList());
      }
    };
  }

  private static Map<String, Object> extraction(final boolean crossingLeftOut) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", IssuesInProgressExtractCommand.STAGE);
    run.put("study_population", Population.FIRST_COMMIT.name());
    if (crossingLeftOut) {
      run.put("crossing_left_out", Map.of("outer_level", 3, "inner_level", 5));
    }
    return run;
  }

  private static Map<String, Object> similarity(final String history, final String written) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", IssuesSimilarityCommand.STAGE);
    run.put("history", history);
    run.put("written_sha256", Map.of("matrix", written));
    return run;
  }

  private static Map<String, Object> retrospective(final String in, final String out) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("stage", IssuesRetrospectiveCommand.STAGE);
    run.put("matrix_sha256", in);
    run.put("retrospective_matrix_sha256", out);
    return run;
  }

  private static List<Map<String, Object>> ready() {
    return new ArrayList<>(List.of(extraction(true),
        similarity(IssuesSimilarityCommand.POINT_IN_TIME_HISTORY, SIMILARITY),
        retrospective(SIMILARITY, MATRIX)));
  }

  @Test
  void opensOnTheRevisedDesignsMatrix() {
    assertNull(IssuesSimilarityTestCommand.revisedUnready(store(ready()), MATRIX));
  }

  @Test
  void refusesAStoreThatCannotTell() {
    final ResultStore blind = (run, rows) -> "run";
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(blind, MATRIX)
        .contains("cannot tell"));
  }

  @Test
  void refusesAnExtractionThatKeptTheCrossingIssues() {
    final List<Map<String, Object>> runs = ready();
    runs.set(0, extraction(false));
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("crossing_left_out"));
  }

  @Test
  void readsTheNewestExtractionRun() {
    // newest first: a later extraction that kept the crossing issues is the matrix's
    final List<Map<String, Object>> runs = ready();
    runs.add(0, extraction(false));
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("crossing_left_out"));
  }

  @Test
  void refusesNoExtractionRun() {
    final List<Map<String, Object>> runs = ready();
    runs.remove(0);
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("no first-commit extraction run"));
  }

  @Test
  void refusesAMatrixNoRetrospectiveRunWrote() {
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(ready()), "another-sha")
        .contains("no retrospective run"));
  }

  @Test
  void refusesAMatrixNoSimilarityRunWrote() {
    final List<Map<String, Object>> runs = ready();
    runs.set(2, retrospective("unknown-sha", MATRIX));
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("no similarity run"));
  }

  @Test
  void refusesWholeSideHistories() {
    final List<Map<String, Object>> runs = ready();
    runs.set(1, similarity(IssuesSimilarityCommand.HISTORY, SIMILARITY));
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("point-in-time"));
  }

  @Test
  void readsTheSimilarityRunThatWroteTheMatrix() {
    // a point-in-time similarity run of another matrix does not stand for this one's
    final List<Map<String, Object>> runs = ready();
    runs.set(1, similarity(IssuesSimilarityCommand.HISTORY, SIMILARITY));
    runs.add(similarity(IssuesSimilarityCommand.POINT_IN_TIME_HISTORY, "other-sha"));
    assertTrue(IssuesSimilarityTestCommand.revisedUnready(store(runs), MATRIX)
        .contains("point-in-time"));
  }
}
