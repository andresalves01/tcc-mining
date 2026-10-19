package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
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
        }, null);
  }

  private Path matrix() throws IOException {
    return Files.writeString(dir.resolve("matrix.tsv"), "placeholder, never parsed\n");
  }

  private Path elasticNet(final String matrixSha) throws IOException {
    final List<String> lines = new java.util.ArrayList<>(List.of(ElasticNetSelection.HEADER,
        "# step\tname\tparameter\tvalue", "elastic-net\tinputs\tmatrix_sha256\t" + matrixSha,
        "tail-cut\tchosen\tk\t5.0", "tail-cut\tchosen\tfence_business_days\t30.0",
        "tail-cut\tusual_flow\tk\t3.0", "tail-cut\tusual_flow\tfence_business_days\t20.0"));
    for (final String k : List.of("1.5", "3.0", "5.0", "10.0", "none")) {
      lines.add("tail-cut\tk_" + k + "\tfence_business_days\t10.0");
      lines.add("tail-cut\tk_" + k + "\tfit_rows\t100");
      lines.add("tail-cut\tk_" + k + "\tmae_business_days\t2.0");
    }
    for (final String m : ElasticNetSelection.MODELS) {
      lines.add("elastic-net\t" + m + "\talpha\t0.5");
      lines.add("elastic-net\t" + m + "\tlambda\t0.1");
      if (ElasticNetSelection.WITH_SIMILARITY.contains(m)) {
        lines.add("elastic-net\t" + m + "\tjaccard_n\t10");
        lines.add("elastic-net\t" + m + "\tembedding_n\t5");
        lines.add("elastic-net\t" + m + "\tcluster_k\t32");
      }
    }
    for (final String m : ElasticNetSelection.MODELS) {
      lines.add("forest\t" + m + "\tmtry_share\t" + (1.0 / 3));
      lines.add("forest\t" + m + "\tmtry\t2");
      lines.add("forest\t" + m + "\tnode_size\t25");
    }
    return Files.write(dir.resolve("elastic-net.tsv"), lines);
  }

  private void set(final Path matrix, final Path elasticNet) {
    settings.put("MATRIX", matrix.toString());
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
  @DisplayName("a matrix whose split is not its rule's -- one verdict moved -- refuses the run "
      + "before anything of it is printed, its sizes included; nothing scored, written or recorded")
  void refusesAnotherSplit() throws IOException {
    final Path matrix = MovedSplitFixture.write(dir.resolve("matrix.tsv"));
    set(matrix, elasticNet(IssuesReservedCommand.sha256(matrix)));
    final java.io.PrintStream out = System.out;
    final java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
    final String why;
    try {
      System.setOut(new java.io.PrintStream(printed, true,
          java.nio.charset.StandardCharsets.UTF_8));
      why = refusal();
    } finally {
      System.setOut(out);
    }
    assertTrue(why.contains("not at the verdicts of the split's rule"), why);
    final String shown = printed.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(shown.contains("outer test"), "nothing of the test side printed: " + shown);
    assertFalse(shown.contains("matrix read"), shown);
  }

  @Test
  @DisplayName("a missing Elastic Net parameters file refuses the run")
  void refusesWithoutElasticNetParameters() throws IOException {
    set(matrix(), dir.resolve("absent.tsv"));
    assertTrue(refusal().contains("absent.tsv does not exist"));
  }

  @Test
  @DisplayName("a SIMILARITY_PARAMETERS setting is not read: the run needs and reads only the "
      + "Elastic Net parameters")
  void readsNoDirectPredictorsFile() throws IOException {
    final Path matrix = matrix();
    set(matrix, elasticNet("0".repeat(64)));
    settings.put("SIMILARITY_PARAMETERS", dir.resolve("absent.tsv").toString());
    final String why = refusal();
    assertTrue(why.contains("were chosen on another matrix"), why);
  }

  @Test
  @DisplayName("parameters chosen on another matrix refuse the run")
  void refusesAnotherMatrix() throws IOException {
    set(matrix(), elasticNet("0".repeat(64)));
    assertTrue(refusal().contains("were chosen on another matrix"));
  }

  @Test
  @DisplayName("a model with the similarity medians without its n, n and k refuses the run")
  void refusesAModelWithoutItsCombination() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> lines = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    lines.remove("elastic-net\tunion_own\tcluster_k\t32");
    Files.write(elasticNet, lines);
    set(matrix, elasticNet);
    assertTrue(refusal().contains("only part of its"));
  }

  @Test
  @DisplayName("a parameters file without one of the six models refuses the run before any "
      + "column is checked")
  void refusesAMissingModel() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> lines = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    lines.removeIf(l -> l.startsWith("elastic-net\tkikas_start\t"));
    Files.write(elasticNet, lines);
    set(matrix, elasticNet);
    assertTrue(refusal().contains("the models are"));
  }

  @Test
  @DisplayName("a parameters file without the forest entries, or without one model's forest, "
      + "refuses the run before anything is scored")
  void refusesWithoutTheForests() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> all = Files.readAllLines(elasticNet);
    set(matrix, elasticNet);

    final List<String> none = new java.util.ArrayList<>(all);
    none.removeIf(l -> l.startsWith("forest\t"));
    Files.write(elasticNet, none);
    String why = refusal();
    assertTrue(why.contains("holds the forest parameters for []"), why);

    final List<String> one = new java.util.ArrayList<>(all);
    one.removeIf(l -> l.startsWith("forest\tunion_own\t"));
    Files.write(elasticNet, one);
    why = refusal();
    assertTrue(why.contains("every model has a forest"), why);

    final List<String> partial = new java.util.ArrayList<>(all);
    partial.remove("forest\tunion_own\tnode_size\t25");
    Files.write(elasticNet, partial);
    why = refusal();
    assertTrue(why.contains("union_own's forest has not its mtry share, mtry and leaf"), why);
  }

  @Test
  @DisplayName("a parameters file without the tail cut's k, fences and grid refuses the run, as "
      + "does one written before ticket 23, under its header then")
  void refusesAFileWithoutTheTailCut() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> none = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    none.removeIf(l -> l.startsWith("tail-cut\t"));
    set(matrix, Files.write(dir.resolve("no-cut.tsv"), none));
    String why = refusal();
    assertTrue(why.contains("has not the tail cut's chosen"), why);

    final List<String> grid = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    grid.remove("tail-cut\tk_10.0\tmae_business_days\t2.0");
    set(matrix, Files.write(dir.resolve("short-grid.tsv"), grid));
    why = refusal();
    assertTrue(why.contains("has not the tail cut's k_10.0"), why);

    none.set(0, "# Elastic Net alpha and lambda, the similarity medians' n, n and k, and each "
        + "random forest's column share and leaf, chosen on the inner validation only, the target "
        + "in whole business days on the issues of at least one, for nine models with the "
        + "retrospective one and the own and retrospective ones before ticket 19; the outer test "
        + "was not read");
    set(matrix, Files.write(dir.resolve("ticket-22.tsv"), none));
    why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);
  }

  @Test
  @DisplayName("a parameters file written before the forests, under its header then, refuses the "
      + "run")
  void refusesAFileBeforeTheForests() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> before = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    before.removeIf(l -> l.startsWith("forest\t"));
    before.set(0, "# Elastic Net alpha and lambda, and the similarity medians' n, n and k, chosen "
        + "on the inner validation only; the outer test was not read");
    set(matrix, Files.write(dir.resolve("before-13.tsv"), before));
    final String why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);
  }

  @Test
  @DisplayName("a parameters file that is not the step's own, as one in the format before "
      + "ticket 11, refuses the run")
  void refusesAForeignFile() throws IOException {
    final Path matrix = matrix();
    final Path old = Files.write(dir.resolve("old.tsv"), List.of(
        "# Elastic Net alpha and lambda chosen on the inner validation only; the outer test was "
            + "not read", "elastic-net\tinputs\tmatrix_sha256\t"
            + IssuesReservedCommand.sha256(matrix)));
    set(matrix, old);
    assertTrue(refusal().contains("is not the Elastic Net step's parameters file"));
  }

  @Test
  @DisplayName("a parameters file written before ticket 12, its models named as they were then, "
      + "refuses the run")
  void refusesAFileOfTheEarlierModels() throws IOException {
    final Path matrix = matrix();
    final String sha = IssuesReservedCommand.sha256(matrix);
    final List<String> lines = new java.util.ArrayList<>(List.of(ElasticNetSelection.HEADER,
        "# step\tname\tparameter\tvalue", "elastic-net\tinputs\tmatrix_sha256\t" + sha));
    for (final String m : List.of("previous_own_start", "revised_nine", "similarity_alone",
        "revised_twelve", "alzubaidi_start", "kikas_start", "union_revised")) {
      lines.add("elastic-net\t" + m + "\talpha\t0.5");
      lines.add("elastic-net\t" + m + "\tlambda\t0.1");
      if (List.of("similarity_alone", "revised_twelve", "union_revised").contains(m)) {
        lines.add("elastic-net\t" + m + "\tjaccard_n\t10");
        lines.add("elastic-net\t" + m + "\tembedding_n\t5");
        lines.add("elastic-net\t" + m + "\tcluster_k\t32");
      }
    }
    set(matrix, Files.write(dir.resolve("before-12.tsv"), lines));
    final String why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file for the models "
        + ElasticNetSelection.MODELS), why);
  }

  @Test
  @DisplayName("the outputs never go where an input is")
  void refusesOutputOverAnInput() throws IOException {
    final Path matrix = Files.writeString(dir.resolve(IssuesSimilarityTestCommand.PREDICTIONS),
        "placeholder, never parsed\n");
    set(matrix, elasticNet(IssuesReservedCommand.sha256(matrix)));
    settings.put("REPORT_DIR", dir.toString());
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("is an input"), e.lines().toString());
  }

  @Test
  @DisplayName("the run record says what the outer test is, in exactly André's sentence, and that "
      + "selection effort differs")
  void statements() {
    final Map<String, Object> run = IssuesSimilarityTestCommand.statements();
    assertEquals("the outer test is a new draw of 30% per project, seed 1738.",
        run.get("outer_test_draw"));
    assertTrue(run.get("selection_effort").toString().contains("differs"));
    assertTrue(run.get("forest_role").toString().contains("robustness check"));
  }

  @Test
  @DisplayName("a parameters file chosen for the hours, under the header before business days, "
      + "refuses the run")
  void refusesAFileForTheHours() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(IssuesReservedCommand.sha256(matrix));
    final List<String> hours = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    hours.set(0, "# Elastic Net alpha and lambda, the similarity medians' n, n and k, and each "
        + "random forest's column share and leaf, chosen on the inner validation only; the outer "
        + "test was not read");
    set(matrix, Files.write(dir.resolve("hours.tsv"), hours));
    final String why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);
  }

  @Test
  @DisplayName("the run records of both steps name the target as active business days with "
      + "recorded activity, the population as the issues of at least one, and holidays as ignored")
  void businessDayStatements() {
    for (final Map<String, Object> run : List.of(IssuesSimilarityTestCommand.statements(),
        IssuesSimilarityElasticNetCommand.statements())) {
      assertTrue(run.get("target").toString().startsWith("active business days with recorded "
          + "activity: whole business days"), run.toString());
      assertTrue(run.get("target").toString().contains(BusinessDays.SILENCE), run.toString());
      assertTrue(run.get("population").toString().contains("at least one business day"));
      assertTrue(run.get("holidays").toString().contains("holidays ignored"));
    }
  }

  @Test
  @DisplayName("the run records of both steps name the retrospective scenario and state that its "
      + "counts grow with the duration itself")
  void retrospectiveStatements() {
    for (final Map<String, Object> run : List.of(IssuesSimilarityTestCommand.statements(),
        IssuesSimilarityElasticNetCommand.statements())) {
      final String scenario = run.get("retrospective_scenario").toString();
      assertTrue(scenario.startsWith("retrospective"), scenario);
      assertTrue(scenario.contains("grow with the duration itself"), scenario);
      assertTrue(scenario.contains("not a prediction"), scenario);
    }
  }

  @Test
  @DisplayName("the run record names the ordering intervals as a declared measure, for the agreed "
      + "pairs and every model against the project median, on the error comparisons' draws")
  void orderingStatement() {
    final String ordering =
        IssuesSimilarityTestCommand.statements().get("ordering_intervals").toString();
    assertTrue(ordering.contains("declared measure"), ordering);
    assertTrue(ordering.contains("Spearman") && ordering.contains("pairwise"), ordering);
    assertTrue(ordering.contains("project median"), ordering);
    assertTrue(ordering.contains("global median orders nothing"), ordering);
    assertTrue(ordering.contains("same 600 draws"), ordering);
  }

  @Test
  @DisplayName("the run record states the prediction intervals, the factor's definition and that "
      + "the outer test applies the inner calibration as an approximation (ticket 24)")
  void predictionIntervalStatements() {
    final Map<String, Object> s = IssuesSimilarityTestCommand.statements();
    assertEquals(app.issues.PredictionIntervals.RULE, s.get("prediction_intervals"));
    assertTrue(s.get("prediction_interval_factor").toString().contains("ceil((n + 1) L)"));
    final String outer = s.get("prediction_interval_outer_calibration").toString();
    assertTrue(outer.contains("approximation") && outer.contains("inner validation"), outer);
  }

  @Test
  @DisplayName("nothing about the ninth model reaches the run record: not its n and k among the "
      + "candidates, though the parameters file holds them, nor its role in the statements; the "
      + "selection effort counts the five models with the medians the test scores")
  void nothingOfTheNinthInTheRecord() {
    final Map<String, ElasticNetSelection.Choice> choices = new java.util.LinkedHashMap<>();
    for (final String m : ElasticNetSelection.MODELS) {
      choices.put(m, new ElasticNetSelection.Choice(0.5, 0.1,
          ElasticNetSelection.WITH_SIMILARITY.contains(m)
              ? new ElasticNetSelection.Combination(10, 5, 32) : null));
    }
    final Map<String, Object> candidates = IssuesSimilarityTestCommand.candidates(choices);
    assertEquals(List.of("similarity_alone", "own_with_similarity", "union_own",
        "own_retrospective", "own_with_similarity_before_pressure"),
        List.copyOf(candidates.keySet()));
    final String record = IssuesSimilarityTestCommand.statements() + " " + candidates;
    assertFalse(record.contains(ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE), record);
    assertFalse(record.contains("ticket 19's five"), record);
    assertTrue(IssuesSimilarityTestCommand.statements().get("selection_effort").toString()
        .contains("the 5 models reading the similarity medians"));
  }

  @Test
  @DisplayName("the printed line naming the parameters file counts the eight Elastic Nets the "
      + "test scores, not the eleven the file holds")
  void choicesLineCountsTheScoredModels() {
    final String line = IssuesSimilarityTestCommand.choicesLine(Path.of("en.tsv"), "ab12");
    assertEquals("  Elastic Net choices en.tsv  sha256 ab12 (8 models)", line);
    assertEquals(11, ElasticNetSelection.MODELS.size());
  }
}
