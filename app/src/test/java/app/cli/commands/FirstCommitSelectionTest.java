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
import app.issues.ElasticNetSelection;
import app.issues.FirstCommit;
import app.issues.LargestErrors;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The selection, the inner report and the test on a matrix of the first-commit population: the
 * split, sets and target of that population; its outer test never parsed past its split field;
 * nothing recorded outside its own databases; the test sealed.
 */
class FirstCommitSelectionTest {

  @TempDir Path dir;

  /** The first-commit study's own databases. */
  private static PipelineConfig own() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  /** The In Progress study's databases, the defaults. */
  private static PipelineConfig defaults() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", "smartshark_tcc",
        "smartshark_issues", "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private static Context context(final PipelineConfig config, final ResultStore store) {
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  private static ResultStore never() {
    return (run, rows) -> {
      throw new AssertionError("recorded " + run);
    };
  }

  /**
   * The fixture's first-commit matrix with every outer test row corrupted past its split field:
   * each value a word no read could parse, so any stage that parses one fails.
   */
  private Path sealedMatrix() throws Exception {
    final Path matrix = SelectionFixture.write(dir.resolve("matrix.tsv"),
        Population.FIRST_COMMIT);
    final List<String> lines = new ArrayList<>(Files.readAllLines(matrix));
    final List<String> header = List.of(lines.get(0).split("\t", -1));
    final int outer = header.indexOf("primeiro_commit_externa");
    final int values = outer + 2;
    for (int k = 1; k < lines.size(); k++) {
      final String[] f = lines.get(k).split("\t", -1);
      if ("TEST".equals(f[outer])) {
        for (int i = values; i < f.length; i++) {
          f[i] = "label?";
        }
        f[5] = "never";
        f[6] = "never";
        lines.set(k, String.join("\t", f));
      }
    }
    Files.write(matrix, lines);
    assertThrows(RuntimeException.class, () -> RowCache.read(matrix), "the corruption parses");
    return matrix;
  }

  @Test
  @DisplayName("the selection and the inner report run on the first-commit split, sets and "
      + "target, never parsing the outer test past its split field, recording only in the "
      + "study's own databases; the inner report equals the selection's errors and lists the "
      + "ten largest errors on each side of the own retrospective model")
  void selectsAndReports() throws Exception {
    final Path matrix = sealedMatrix();
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    final List<Map<String, Object>> runs = new ArrayList<>();
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own(), (run, rows) -> {
      runs.add(run);
      return "run";
    }));

    final List<String> written = Files.readAllLines(parameters);
    assertEquals(ElasticNetSelection.HEADER_FIRST_COMMIT, written.get(0));
    assertEquals(1, runs.size());
    final Map<String, Object> run = runs.get(0);
    assertEquals("FIRST_COMMIT", run.get("study"));
    assertEquals(FirstCommit.RULE, run.get("target"));
    assertEquals(FirstCommit.POPULATION, run.get("population"));
    assertEquals("not read", run.get("outer_test"));
    // the retrospective window is the first commit's to the first resolution
    assertEquals(app.issues.Retrospective.scenario(Population.FIRST_COMMIT),
        run.get("retrospective_scenario"));
    assertTrue(((String) run.get("retrospective_scenario")).contains("between the first commit "
        + "that counts and the first resolution"));

    // the selection is the one on the outer training rows of the first-commit split
    final List<RowCache.Entry> training =
        RowCache.read(matrix, Population.FIRST_COMMIT, "TRAIN");
    final ElasticNetSelection.Result direct = ElasticNetSelection.run(BusinessDays.population(
        training, Augmentation.Variant.INNER).entries(), 1, Population.FIRST_COMMIT);
    assertEquals(ElasticNetSelection.parameters(direct, Sha256.of(matrix),
        Population.FIRST_COMMIT), written);
    // the own models read the first commit's size; the retrospective readings its share too
    for (final ElasticNetSelection.Model m : direct.models()) {
      final List<String> declared =
          m.cell().attributes().stream().map(app.issues.Attribute::name).toList();
      final String name = m.cell().label();
      final boolean own = List.of(ElasticNetSelection.BEFORE_PRESSURE,
          ElasticNetSelection.RETROSPECTIVE, ElasticNetSelection.WITH_DISCUSSION).contains(name);
      assertEquals(own, declared.contains(FirstCommit.FIRST_LINES), name);
      assertEquals(own && !name.equals(ElasticNetSelection.BEFORE_PRESSURE),
          declared.contains(FirstCommit.FIRST_SHARE), name);
    }

    final Path reports = dir.resolve("inner");
    settings.put("REPORT_DIR", reports.toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context(own(), never()));

    final List<String> models =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.MODELS));
    for (final ElasticNetSelection.Model m : direct.models()) {
      final String name = m.cell().label();
      final String[] usual = models.stream().filter(l -> l.startsWith("usual_flow\t" + name
          + "\t")).findFirst().orElseThrow().split("\t");
      assertEquals(m.fitted().score().mae(), Double.parseDouble(usual[4]), name);
      final String[] forest = models.stream().filter(l -> l.startsWith("usual_flow\t"
          + SimilarityScoring.forest(name) + "\t")).findFirst().orElseThrow().split("\t");
      assertEquals(m.forest().score().mae(), Double.parseDouble(forest[4]), name);
    }
    final List<String> report =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertEquals("# Issue duration from the first commit: inner report", report.get(0));
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- target: " + FirstCommit.RULE)),
        report.toString());
    // the side reading's issues are those with Java code
    final List<String> comparisons =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.COMPARISONS));
    assertTrue(comparisons.stream().anyMatch(l -> l.startsWith("usual_flow\twith Java code\t")));
    assertTrue(comparisons.stream().anyMatch(l -> l.startsWith("all\twithout Java code\t")));
    assertTrue(comparisons.stream().noneMatch(l -> l.contains("implementation commit")));
    assertTrue(report.stream().anyMatch(l -> l.startsWith("### Side reading:")
        && l.endsWith(" issues with Java code")), report.toString());
    assertTrue(report.stream().noneMatch(l -> l.contains("implementation commit")));
    final List<String> relevance =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.RELEVANCE));
    assertTrue(relevance.stream().map(l -> l.split("\t")).anyMatch(f -> f[0].equals(
        ElasticNetSelection.RETROSPECTIVE) && f[3].equals(FirstCommit.FIRST_SHARE)),
        relevance.toString());

    // the ten largest errors on each side of the own retrospective model, from its predictions
    final List<String> predictions =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.PREDICTIONS));
    final List<String> head = List.of(predictions.get(0).split("\t"));
    assertEquals("has_java_code", head.get(5));
    final int retrospective = head.indexOf(ElasticNetSelection.RETROSPECTIVE);
    final Map<String, Double> error = new LinkedHashMap<>();
    for (final String line : predictions.subList(1, predictions.size())) {
      final String[] f = line.split("\t");
      error.put(f[1], Double.parseDouble(f[3]) - Double.parseDouble(f[retrospective]));
    }
    final List<String> under = error.entrySet().stream().filter(e -> e.getValue() > 0)
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(10)
        .map(Map.Entry::getKey).toList();
    final List<String> over = error.entrySet().stream().filter(e -> e.getValue() < 0)
        .sorted(Map.Entry.comparingByValue()).limit(10).map(Map.Entry::getKey).toList();
    final List<String> largest =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.LARGEST_ERRORS));
    assertEquals(1 + 20, largest.size());
    assertEquals(under, largest.stream().skip(1).map(l -> l.split("\t", -1))
        .filter(f -> f[1].equals("under")).map(f -> f[4]).toList());
    assertEquals(over, largest.stream().skip(1).map(l -> l.split("\t", -1))
        .filter(f -> f[1].equals("over")).map(f -> f[4]).toList());
    final List<String> columns = List.of(largest.get(0).split("\t"));
    assertEquals(LargestErrors.COLUMNS.stream().map(LargestErrors.Column::name).toList(),
        columns.subList(11, columns.size()));
    assertTrue(report.stream().anyMatch(l -> l.startsWith("## The largest errors of "
        + ElasticNetSelection.RETROSPECTIVE)), report.toString());
    assertTrue(report.getLast().endsWith(IssuesSimilarityInnerReportCommand.LARGEST_ERRORS + "."),
        report.getLast());

    // nothing of the outer test reaches any file: not one of its issues' keys
    final List<Pattern> outerTest = SelectionFixture.entries(Population.FIRST_COMMIT).stream()
        .filter(e -> "TEST".equals(e.outer(Population.FIRST_COMMIT)))
        .map(e -> Pattern.compile("(?<![\\w-])" + Pattern.quote(e.unit().key()) + "(?!\\w)"))
        .toList();
    assertFalse(outerTest.isEmpty());
    try (var files = Files.list(reports)) {
      for (final Path file : files.sorted(Comparator.naturalOrder()).toList()) {
        final String text = Files.readString(file);
        assertTrue(outerTest.stream().noneMatch(p -> p.matcher(text).find()), file.toString());
      }
    }
  }

  @Test
  @DisplayName("on the first-commit matrix the selection refuses the In Progress study's "
      + "databases before reading a row, writing or recording anything")
  void refusesTheInProgressDatabases() throws Exception {
    final Path matrix = sealedMatrix();
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = Map.of("MATRIX", matrix.toString(),
        "ELASTIC_NET_PARAMETERS", parameters.toString());
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> new IssuesSimilarityElasticNetCommand(settings::get).run(context(defaults(),
            never())));
    assertTrue(String.join("\n", e.lines()).contains("databases of its own"), e.lines()
        .toString());
    assertFalse(Files.exists(parameters));
  }

  @Test
  @DisplayName("the test refuses a first-commit matrix on its header alone: the study's outer "
      + "test is sealed, nothing is read, scored, written or recorded")
  void sealsTheOuterTest() throws Exception {
    final Path matrix = sealedMatrix();
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own(), (run, rows) -> "run"));
    final Path reports = dir.resolve("test");
    settings.put("REPORT_DIR", reports.toString());
    for (final PipelineConfig config : List.of(own(), defaults())) {
      final CommandFailedException e = assertThrows(CommandFailedException.class,
          () -> new IssuesSimilarityTestCommand(settings::get).run(context(config, never())));
      assertTrue(String.join("\n", e.lines()).contains(IssuesSimilarityTestCommand.SEALED),
          e.lines().toString());
      assertFalse(Files.exists(reports));
    }
  }

  @Test
  @DisplayName("an In Progress parameters file is refused on a first-commit matrix, and the other "
      + "way round, before anything is scored")
  void refusesAnotherPopulationsParameters() throws Exception {
    final Path matrix = sealedMatrix();
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own(), (run, rows) -> "run"));
    final List<String> lines = new ArrayList<>(Files.readAllLines(parameters));
    lines.set(0, ElasticNetSelection.HEADER);
    Files.write(parameters, lines);
    final Path reports = dir.resolve("inner");
    settings.put("REPORT_DIR", reports.toString());
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> new IssuesSimilarityInnerReportCommand(settings::get).run(context(own(), never())));
    assertTrue(String.join("\n", e.lines()).contains("was written for IN_PROGRESS"),
        e.lines().toString());
    assertFalse(Files.exists(reports));
  }

  @Test
  @DisplayName("the share ablation, a diagnostic: the variant with the share is the selection's "
      + "own choice, both are read on the inner validation only, nothing of the outer test is "
      + "written, and a matrix of In Progress is refused")
  void ablatesTheShare() throws Exception {
    final Path matrix = sealedMatrix();
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    new IssuesSimilarityElasticNetCommand(settings::get).run(context(own(), (run, rows) -> "run"));
    final Path out = dir.resolve("ablation");
    settings.put("REPORT_DIR", out.toString());
    new IssuesFirstCommitShareAblationCommand(settings::get).run(context(own(), never()));

    final List<String> table =
        Files.readAllLines(out.resolve(IssuesFirstCommitShareAblationCommand.TABLE));
    assertEquals(1 + 4, table.size());
    // the variant with the share reads, on the usual flow, the selection's own error
    final List<RowCache.Entry> training =
        RowCache.read(matrix, Population.FIRST_COMMIT, "TRAIN");
    final ElasticNetSelection.Result direct = ElasticNetSelection.run(BusinessDays.population(
        training, Augmentation.Variant.INNER).entries(), 1, Population.FIRST_COMMIT);
    final double own = direct.models().get(ElasticNetSelection.MODELS.indexOf(
        ElasticNetSelection.RETROSPECTIVE)).fitted().score().mae();
    final String[] usual = table.get(1).split("\t");
    assertEquals(List.of("usual_flow", ElasticNetSelection.RETROSPECTIVE),
        List.of(usual[0], usual[2]));
    assertEquals(own, Double.parseDouble(usual[3]));
    assertEquals(app.issues.ShareAblation.WITHOUT, table.get(2).split("\t")[2]);
    final List<String> md =
        Files.readAllLines(out.resolve(IssuesFirstCommitShareAblationCommand.REPORT));
    assertTrue(md.get(0).contains("a diagnostic"), md.get(0));
    final List<Pattern> outerTest = SelectionFixture.entries(Population.FIRST_COMMIT).stream()
        .filter(e -> "TEST".equals(e.outer(Population.FIRST_COMMIT)))
        .map(e -> Pattern.compile("(?<![\\w-])" + Pattern.quote(e.unit().key()) + "(?!\\w)"))
        .toList();
    for (final String text : List.of(String.join("\n", table), String.join("\n", md))) {
      assertTrue(outerTest.stream().noneMatch(p -> p.matcher(text).find()));
    }

    final Path inProgress = SelectionFixture.write(dir.resolve("in-progress.tsv"),
        Population.IN_PROGRESS);
    settings.put("MATRIX", inProgress.toString());
    settings.put("REPORT_DIR", dir.resolve("refused").toString());
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitShareAblationCommand(settings::get).run(context(own(),
            never())));
    assertTrue(String.join("\n", e.lines()).contains("not of the first-commit population"),
        e.lines().toString());
    assertFalse(Files.exists(dir.resolve("refused")));
  }
}
