package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.PredictionIntervals;
import app.issues.SplitRule;
import app.issues.TailCut;
import app.issues.Universe.Population;
import app.issues.model.ProjectBootstrap;
import app.pipeline.port.SilentProgress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test command's refusals, every one reached before a row is scored, so nothing is scored,
 * written or recorded: on the study's own databases, a first-commit matrix of the revised design
 * and its earlier runs in the store, each test breaks one input.
 */
class IssuesSimilarityTestCommandTest {

  /** The settings the command reads, never the environment's: a real MATRIX is never seen. */
  private final Map<String, String> settings = new HashMap<>();

  @TempDir
  Path dir;

  private IssuesSimilarityTestCommand command() {
    return new IssuesSimilarityTestCommand(settings::get);
  }

  /**
   * A context on the study's own databases whose run store holds the revised design's earlier
   * runs for the MATRIX set and no run of this step, and fails the test if anything is recorded.
   */
  private Context context() throws IOException {
    final String matrix = settings.get("MATRIX");
    final List<Map<String, Object>> upstream = matrix == null || !Files.exists(Path.of(matrix))
        ? List.of() : RevisedUpstream.of(Path.of(matrix));
    final FreshStore store = new FreshStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        throw new AssertionError("recorded " + run);
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(false);
      }

      @Override
      public Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
        return RevisedUpstream.runs(upstream, stage);
      }
    };
    return new Context(OwnDatabases.config(), null, null, null, null, null, null, null, null, null,
        null, SilentProgress.INSTANCE, null, store, null);
  }

  /** A first-commit matrix of the revised design; its rows are never scored here. */
  private Path matrix() throws IOException {
    return SelectionFixture.write(dir.resolve("matrix.tsv"), Population.FIRST_COMMIT);
  }

  private Path elasticNet(final String matrixSha) throws IOException {
    final List<String> lines = new java.util.ArrayList<>(List.of(ElasticNetSelection.header(
        Population.FIRST_COMMIT, SplitRule.REVISED),
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
    set(matrix, elasticNet(Sha256.of(matrix)));
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
    final Path elasticNet = elasticNet(Sha256.of(matrix));
    final List<String> lines = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    lines.remove("elastic-net\town_retrospective\tcluster_k\t32");
    Files.write(elasticNet, lines);
    set(matrix, elasticNet);
    assertTrue(refusal().contains("only part of its"));
  }

  @Test
  @DisplayName("a parameters file without one of the six models refuses the run before any "
      + "column is checked")
  void refusesAMissingModel() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(Sha256.of(matrix));
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
    final Path elasticNet = elasticNet(Sha256.of(matrix));
    final List<String> all = Files.readAllLines(elasticNet);
    set(matrix, elasticNet);

    final List<String> none = new java.util.ArrayList<>(all);
    none.removeIf(l -> l.startsWith("forest\t"));
    Files.write(elasticNet, none);
    String why = refusal();
    assertTrue(why.contains("holds the forest parameters for []"), why);

    final List<String> one = new java.util.ArrayList<>(all);
    one.removeIf(l -> l.startsWith("forest\tkikas_retrospective\t"));
    Files.write(elasticNet, one);
    why = refusal();
    assertTrue(why.contains("every model has a forest"), why);

    final List<String> partial = new java.util.ArrayList<>(all);
    partial.remove("forest\tkikas_retrospective\tnode_size\t25");
    Files.write(elasticNet, partial);
    why = refusal();
    assertTrue(why.contains("kikas_retrospective's forest has not its mtry share, mtry and leaf"),
        why);
  }

  @Test
  @DisplayName("a parameters file without the tail cut's k, fences and grid refuses the run, as "
      + "does one written before ticket 23, under its header then")
  void refusesAFileWithoutTheTailCut() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(Sha256.of(matrix));
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
    final Path elasticNet = elasticNet(Sha256.of(matrix));
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
            + Sha256.of(matrix)));
    set(matrix, old);
    assertTrue(refusal().contains("is not the Elastic Net step's parameters file"));
  }

  @Test
  @DisplayName("a parameters file written before ticket 12, its models named as they were then, "
      + "refuses the run")
  void refusesAFileOfTheEarlierModels() throws IOException {
    final Path matrix = matrix();
    final String sha = Sha256.of(matrix);
    final List<String> lines = new java.util.ArrayList<>(List.of(ElasticNetSelection.header(
        Population.FIRST_COMMIT, SplitRule.REVISED),
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
    set(matrix, elasticNet(Sha256.of(matrix)));
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
    assertTrue(run.get("selection_effort").toString().contains("differs"));
    assertTrue(run.get("forest_role").toString().contains("robustness check"));
  }

  @Test
  @DisplayName("a parameters file chosen for the hours, under the header before business days, "
      + "refuses the run")
  void refusesAFileForTheHours() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(Sha256.of(matrix));
    final List<String> hours = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    hours.set(0, "# Elastic Net alpha and lambda, the similarity medians' n, n and k, and each "
        + "random forest's column share and leaf, chosen on the inner validation only; the outer "
        + "test was not read");
    set(matrix, Files.write(dir.resolve("hours.tsv"), hours));
    final String why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);
  }

  @Test
  @DisplayName("the run records of both steps name the retrospective scenario and state that its "
      + "counts grow with the duration itself")
  void retrospectiveStatements() {
    for (final Map<String, Object> run : List.of(IssuesSimilarityTestCommand.statements(),
        IssuesSimilarityElasticNetCommand.statements(Population.FIRST_COMMIT))) {
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
    final String outer = PredictionIntervals.outer(SplitRule.REVISED);
    assertTrue(outer.contains("approximation") && outer.contains("inner validation"), outer);
  }

  /** The header of the parameters files written for the eleven models of ticket 29. */
  static final String ELEVEN_HEADER = "# the tail cut's k and fences, Elastic Net alpha and "
      + "lambda, the similarity medians' n, n and k, and each random forest's column share and "
      + "leaf, chosen on the usual flow of the inner validation only, the target in whole "
      + "business days on the issues of at least one, for eleven models with the retrospective "
      + "one with and without the comments and participants, the own and retrospective ones "
      + "without the pressure attributes and Kikas's retrospective one; the outer test was not "
      + "read";

  @Test
  @DisplayName("a parameters file written for the eleven models before the fourteen were settled "
      + "refuses the run: under its own header, or under today's with a left-out model's lines")
  void refusesAFileOfTheElevenModels() throws IOException {
    final Path matrix = matrix();
    final Path elasticNet = elasticNet(Sha256.of(matrix));
    final List<String> eleven = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    eleven.set(0, ELEVEN_HEADER);
    set(matrix, Files.write(dir.resolve("eleven.tsv"), eleven));
    String why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);

    final List<String> union = new java.util.ArrayList<>(Files.readAllLines(elasticNet));
    union.add("elastic-net\tunion_own\talpha\t0.5");
    union.add("elastic-net\tunion_own\tlambda\t0.1");
    set(matrix, Files.write(dir.resolve("union.tsv"), union));
    why = refusal();
    assertTrue(why.contains("is not the Elastic Net step's parameters file"), why);
  }

  @Test
  @DisplayName("the run record names the fourteen models alone: the n and k of the three with the "
      + "similarity medians among the candidates, the roles of both retrospective readings and "
      + "of Kikas's in the statements, no left-out model; the selection effort counts the three "
      + "with the medians")
  void theFourteenInTheRecord() {
    final Map<String, ElasticNetSelection.Choice> choices = new java.util.LinkedHashMap<>();
    for (final String m : ElasticNetSelection.MODELS) {
      choices.put(m, new ElasticNetSelection.Choice(0.5, 0.1,
          ElasticNetSelection.WITH_SIMILARITY.contains(m)
              ? new ElasticNetSelection.Combination(10, 5, 32) : null));
    }
    final Map<String, Object> candidates = IssuesSimilarityTestCommand.candidates(choices);
    assertEquals(List.of("own_with_similarity_before_pressure", "own_retrospective",
        "own_retrospective_with_discussion"), List.copyOf(candidates.keySet()));
    final Map<String, Object> statements = IssuesSimilarityTestCommand.statements();
    assertEquals(ElasticNetSelection.RETROSPECTIVE_ROLE, statements.get("own_retrospective"));
    assertEquals(ElasticNetSelection.WITH_DISCUSSION_ROLE,
        statements.get("own_retrospective_with_discussion"));
    assertEquals(ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE,
        statements.get("kikas_retrospective"));
    final String record = statements + " " + candidates;
    for (final String name : List.of("own_without_similarity", "similarity_alone",
        "union_own", "own_retrospective_before_pressure", "executor_project_median")) {
      assertFalse(record.contains(name), name);
    }
    // the own specification with the pressure columns, not as the own start model's prefix
    assertFalse(java.util.regex.Pattern.compile("own_with_similarity(?!_before_pressure)")
        .matcher(record).find(), record);
    assertTrue(statements.get("selection_effort").toString()
        .contains("the 3 models reading the similarity medians"));
  }

  @Test
  @DisplayName("the printed line naming the parameters file counts the six Elastic Nets, which "
      + "the test scores and the file holds alike")
  void choicesLineCountsTheScoredModels() {
    final String line = IssuesSimilarityTestCommand.choicesLine(Path.of("en.tsv"), "ab12");
    assertEquals("  Elastic Net choices en.tsv  sha256 ab12 (6 models)", line);
    assertEquals(6, ElasticNetSelection.MODELS.size());
  }

  @Test
  @DisplayName("the prediction intervals are printed and recorded one row per model, level and "
      + "group: overall, then each band of the observed duration by its label; an empty band "
      + "has its count and no figure, an infinite width is printed as such")
  void printIntervalsRows() {
    final double[] tops = {2, 4, 11};
    final ProjectBootstrap.Share share = new ProjectBootstrap.Share(0.8, 0.7, 0.9);
    final PredictionIntervals.Group overall =
        new PredictionIntervals.Group(40, share, 6.5, 1.25, 3.2);
    final List<PredictionIntervals.Group> bands = List.of(
        new PredictionIntervals.Group(10, share, 2.0, 0.5, 1.0),
        new PredictionIntervals.Group(0, null, Double.NaN, Double.NaN, Double.NaN),
        new PredictionIntervals.Group(20, share, Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY, 4.0),
        new PredictionIntervals.Group(10, share, 9.0, 1.5, 7.0));
    final List<PredictionIntervals.Read> reads = List.of(
        new PredictionIntervals.Read(TailCut.Reading.USUAL_FLOW, "kikas_start", 0.8, overall,
            bands, Map.of()),
        new PredictionIntervals.Read(TailCut.Reading.USUAL_FLOW, "kikas_start", 0.95, overall,
            bands, Map.of()));
    final List<Map<String, Object>> rows = new java.util.ArrayList<>();
    final java.io.PrintStream out = System.out;
    final java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
    try {
      System.setOut(new java.io.PrintStream(printed, true,
          java.nio.charset.StandardCharsets.UTF_8));
      IssuesSimilarityTestCommand.printIntervals(reads, tops, rows,
          IssuesSimilarityTestCommand.intervalsLabel(SplitRule.REVISED));
    } finally {
      System.setOut(out);
    }
    assertEquals(2 * 5, rows.size());
    final String[] labels = BusinessDays.bandLabels(tops);
    for (int level = 0; level < 2; level++) {
      for (int g = 0; g < 5; g++) {
        final Map<String, Object> row = rows.get(5 * level + g);
        assertEquals("prediction_interval", row.get("kind"));
        assertEquals("kikas_start", row.get("model"));
        assertEquals(level == 0 ? 0.8 : 0.95, row.get("level"));
        assertEquals(g == 0 ? "all" : labels[g - 1], row.get("observed_band"));
      }
    }
    final Map<String, Object> all = rows.get(0);
    assertEquals(40, all.get("n"));
    assertEquals(0.8, all.get("coverage"));
    assertEquals(List.of(0.7, 0.9), all.get("coverage_ci_projects"));
    assertEquals(6.5, all.get("median_width_business_days"));
    assertEquals(1.25, all.get("width_over_global"));
    assertEquals(3.2, all.get("mae_business_days"));
    // the empty band: its count, nothing else
    assertEquals(Map.of("kind", "prediction_interval", "model", "kikas_start", "level", 0.8,
        "observed_band", labels[1], "n", 0), rows.get(2));
    assertEquals(Double.POSITIVE_INFINITY, rows.get(3).get("median_width_business_days"));
    final List<String> lines = printed.toString(java.nio.charset.StandardCharsets.UTF_8)
        .lines().filter(l -> l.startsWith("  kikas_start")).toList();
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains(" 80%"), lines.get(0));
    assertTrue(lines.get(1).contains(" 95%"), lines.get(1));
    assertTrue(lines.get(0).contains("all 0.800 [0.700, 0.900] w 6.50 x1.25 mae 3.200 (n 40)"),
        lines.get(0));
    assertTrue(lines.get(0).contains(labels[1] + " (n 0)"), lines.get(0));
    assertTrue(lines.get(0).contains(labels[2] + " 0.800 [0.700, 0.900] w ∞ x∞ mae 4.000 (n 20)"),
        lines.get(0));
  }
}
