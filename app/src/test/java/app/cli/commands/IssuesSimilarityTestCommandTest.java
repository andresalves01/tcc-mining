package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.ElasticNetSelection;
import app.issues.Selection;
import app.pipeline.port.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test command's refusals, every one reached before the matrix is parsed, so nothing is
 * scored, written or recorded. No row is read here: the matrix is a placeholder file.
 */
class IssuesSimilarityTestCommandTest {

  /** The settings the command reads, never the environment's: a real MATRIX is never seen. */
  private final Map<String, String> settings = new HashMap<>();

  @TempDir
  Path dir;

  private IssuesSimilarityTestCommand command() {
    return new IssuesSimilarityTestCommand(settings::get);
  }

  /** A context whose result store fails the test if anything is recorded. */
  private static Context context() {
    return new Context(null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, Progress.silent(), null, (run, rows) -> {
          throw new AssertionError("recorded " + run);
        });
  }

  private Path matrix() throws IOException {
    return Files.writeString(dir.resolve("matrix.tsv"), "placeholder, never parsed\n");
  }

  private Path similarity() throws IOException {
    return Files.write(dir.resolve("similarity.tsv"), List.of(Selection.HEADER,
        "# step\tname\tparameter\tvalue", "direct\tcluster_median\tcandidate\t32",
        "direct\tembedding_median\tcandidate\t5", "direct\tjaccard_median\tcandidate\t10"));
  }

  private Path elasticNet(final String matrixSha, final String similaritySha) throws IOException {
    final List<String> lines = new java.util.ArrayList<>(List.of(ElasticNetSelection.HEADER,
        "# step\tname\tparameter\tvalue",
        "elastic-net\tinputs\tmatrix_sha256\t" + matrixSha,
        "elastic-net\tinputs\tsimilarity_parameters_sha256\t" + similaritySha));
    for (final String m : List.of("previous_own_start", "revised_nine", "similarity_alone",
        "revised_twelve", "alzubaidi_start", "kikas_start", "union_revised")) {
      lines.add("elastic-net\t" + m + "\talpha\t0.5");
      lines.add("elastic-net\t" + m + "\tlambda\t0.1");
    }
    return Files.write(dir.resolve("elastic-net.tsv"), lines);
  }

  private void set(final Path matrix, final Path similarity, final Path elasticNet) {
    settings.put("MATRIX", matrix.toString());
    settings.put("SIMILARITY_PARAMETERS", similarity.toString());
    settings.put("ELASTIC_NET_PARAMETERS", elasticNet.toString());
    settings.put("REPORT_DIR", dir.resolve("out").toString());
  }

  private String refusal() {
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> command().run(context()));
    assertFalse(Files.exists(dir.resolve("out")), "something was written");
    return String.join("\n", e.lines());
  }

  @Test
  @DisplayName("a missing Elastic Net parameters file refuses the run")
  void refusesWithoutElasticNetParameters() throws IOException {
    set(matrix(), similarity(), dir.resolve("absent.tsv"));
    assertTrue(refusal().contains("absent.tsv does not exist"));
  }

  @Test
  @DisplayName("a missing direct predictors' parameters file refuses the run")
  void refusesWithoutSimilarityParameters() throws IOException {
    final Path matrix = matrix();
    set(matrix, dir.resolve("absent.tsv"), elasticNet("x", "y"));
    assertTrue(refusal().contains("absent.tsv does not exist"));
  }

  @Test
  @DisplayName("parameters chosen with another direct predictors' file refuse the run")
  void refusesAnotherSimilarityFile() throws IOException {
    final Path matrix = matrix();
    final Path similarity = similarity();
    set(matrix, similarity,
        elasticNet(IssuesReservedCommand.sha256(matrix), "0".repeat(64)));
    assertTrue(refusal().contains("were chosen with another"));
  }

  @Test
  @DisplayName("parameters chosen on another matrix refuse the run")
  void refusesAnotherMatrix() throws IOException {
    final Path matrix = matrix();
    final Path similarity = similarity();
    set(matrix, similarity,
        elasticNet("0".repeat(64), IssuesReservedCommand.sha256(similarity)));
    assertTrue(refusal().contains("were chosen on another matrix"));
  }

  @Test
  @DisplayName("a parameters file that is not the step's own refuses the run")
  void refusesAForeignFile() throws IOException {
    final Path matrix = matrix();
    set(matrix, similarity(), similarity());
    assertTrue(refusal().contains("is not the Elastic Net step's parameters file"));
  }

  @Test
  @DisplayName("the outputs never go where an input is")
  void refusesOutputOverAnInput() throws IOException {
    final Path matrix = Files.writeString(dir.resolve(IssuesSimilarityTestCommand.PREDICTIONS),
        "placeholder, never parsed\n");
    final Path similarity = similarity();
    set(matrix, similarity, elasticNet(IssuesReservedCommand.sha256(matrix),
        IssuesReservedCommand.sha256(similarity)));
    settings.put("REPORT_DIR", dir.toString());
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("is an input"), e.lines().toString());
  }

  @Test
  @DisplayName("the run record says the test was examined before and selection effort differs")
  void statements() {
    final Map<String, Object> run = IssuesSimilarityTestCommand.statements();
    assertTrue(run.get("outer_test_examined_before").toString().contains("earlier"));
    assertTrue(run.get("selection_effort").toString().contains("differs"));
  }
}
