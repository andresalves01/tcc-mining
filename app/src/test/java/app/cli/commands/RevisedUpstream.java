package app.cli.commands;

import app.issues.SplitRule;
import app.issues.Universe.Population;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The run records the revised design's earlier stages leave in its collection for a matrix built
 * by hand: an extraction that leaves the crossing issues out, the similarity run that wrote the
 * columns the matrix was made from, with point-in-time histories, and the retrospective run that
 * wrote the matrix. The revised test step opens only on such a matrix ({@link
 * IssuesSimilarityTestCommand#revisedUnready}).
 */
final class RevisedUpstream {

  private RevisedUpstream() {}

  /** The three records for {@code matrix}, oldest first, as the stages would record them. */
  static List<Map<String, Object>> of(final Path matrix) throws IOException {
    final String similarityMatrix = "similarity-matrix-of-" + Sha256.of(matrix);
    final Map<String, Object> extraction = new LinkedHashMap<>();
    extraction.put("stage", IssuesInProgressExtractCommand.STAGE);
    extraction.put("study_population", Population.FIRST_COMMIT.name());
    extraction.put(SplitRule.DESIGN_FIELD, SplitRule.REVISED.setting());
    extraction.put(SplitRule.RULE_FIELD, SplitRule.REVISED.rule());
    extraction.put("crossing_left_out", Map.of("outer_level", 1, "inner_level", 1));
    final Map<String, Object> similarity = new LinkedHashMap<>();
    similarity.put("stage", IssuesSimilarityCommand.STAGE);
    similarity.put(SplitRule.DESIGN_FIELD, SplitRule.REVISED.setting());
    similarity.put(SplitRule.RULE_FIELD, SplitRule.REVISED.rule());
    similarity.put("history", IssuesSimilarityCommand.POINT_IN_TIME_HISTORY);
    similarity.put("written_sha256", Map.of("matrix", similarityMatrix));
    final Map<String, Object> retrospective = new LinkedHashMap<>();
    retrospective.put("stage", IssuesRetrospectiveCommand.STAGE);
    retrospective.put(SplitRule.DESIGN_FIELD, SplitRule.REVISED.setting());
    retrospective.put(SplitRule.RULE_FIELD, SplitRule.REVISED.rule());
    retrospective.put("matrix_sha256", similarityMatrix);
    retrospective.put("retrospective_matrix_sha256", Sha256.of(matrix));
    return List.of(extraction, similarity, retrospective);
  }

  /** The runs of {@code stage} among {@code runs}, newest first, as a run store lists them. */
  static Optional<List<Map<String, Object>>> runs(final List<Map<String, Object>> runs,
      final String stage) {
    final List<Map<String, Object>> out = new ArrayList<>(runs.stream()
        .filter(r -> stage.equals(r.get("stage"))).toList());
    java.util.Collections.reverse(out);
    return Optional.of(List.copyOf(out));
  }
}
