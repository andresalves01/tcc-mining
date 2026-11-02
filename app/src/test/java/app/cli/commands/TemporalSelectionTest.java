package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.AnalogyEstimate;
import app.issues.ElasticNetSelection;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.SplitRule;
import app.issues.TemporalSplit;
import app.issues.Universe.Population;
import app.issues.model.Forest;
import app.issues.port.ResultStore;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first-commit study's temporal split, selected and reported on the inner validation (its
 * ticket 06): the selection freezes the analogy estimate's choice and the forests' 500 trees,
 * and the inner report reads them as the fifteenth model and the forests' count.
 */
class TemporalSelectionTest {

  @TempDir Path dir;

  private final List<Map<String, Object>> runs = new ArrayList<>();
  private final List<List<Map<String, Object>>> rows = new ArrayList<>();

  private static PipelineConfig own() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private Context context() {
    final ResultStore store = new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> r) {
        throw new AssertionError("temporal selection recorded in the random collection");
      }

      @Override
      public ResultStore design(final SplitRule rule) {
        assertEquals(SplitRule.TEMPORAL, rule);
        return new ResultStore() {
          @Override
          public String record(final Map<String, Object> run, final List<Map<String, Object>> r) {
            runs.add(run);
            rows.add(r);
            return "temporal-run";
          }

          @Override
          public Optional<java.util.Set<SplitRule>> designs() {
            return Optional.of(runs.stream().map(SplitRule::recordedBy)
                .collect(java.util.stream.Collectors.toSet()));
          }
        };
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(runs.stream().anyMatch(r -> stage.equals(r.get("stage"))));
      }
    };
    return new Context(own(), null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  private static String refused(final CommandRunner step, final Context context) {
    final CommandFailedException e =
        assertThrows(CommandFailedException.class, () -> step.run(context));
    return String.join("\n", e.lines());
  }

  /** The selection under SPLIT_RULE=temporal on {@code matrix}; its settings, for the report. */
  private Map<String, String> select(final Path matrix) throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", dir.resolve("elastic-net.tsv").toString());
    settings.put(SplitRule.SETTING, "temporal");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context());
    return settings;
  }

  /** The rows of a TSV whose first field is {@code reading} and second is {@code model}. */
  private static String[] row(final List<String> tsv, final String reading, final String model) {
    return tsv.stream().map(l -> l.split("\t", -1))
        .filter(f -> f[0].equals(reading) && f[1].equals(model)).findFirst().orElseThrow(
            () -> new AssertionError("no row " + reading + " " + model));
  }

  @Test
  @DisplayName("selection: under the temporal split the parameters file freezes the analogy "
      + "estimate's choice, the lowest MAE on the usual-flow inner validation with the inner "
      + "fitting's global median as fallback, and the forests' 500 trees; the run records both")
  void selectionFreezesTheAnalogyAndTheTrees() throws Exception {
    final Path matrix = SelectionFixture.temporal(dir.resolve("temporal.tsv"));
    select(matrix);
    final List<String> lines = Files.readAllLines(dir.resolve("elastic-net.tsv"));
    assertEquals(SplitRule.TEMPORAL, ElasticNetSelection.headerRule(lines.getFirst()));
    final List<ElasticNetSelection.Parameter> file = ElasticNetSelection.read(lines);
    assertEquals(500, ElasticNetSelection.trees(file).getAsInt());
    assertEquals(1, lines.stream().filter(l -> l.startsWith("analogy\t")).count());

    // the choice, made again by the exploratory stage's own reading of the same rule
    final double k = ElasticNetSelection.tailCut(file).k();
    final AnalogyEstimate.Choice expected = IssuesFirstCommitAnalogyCommand.inner(
        RowCache.read(matrix, Population.FIRST_COMMIT, "TRAIN"), k).choice();
    assertEquals(expected.chosen(), ElasticNetSelection.analogy(file).column());

    final Map<String, Object> run = runs.getFirst();
    assertEquals(500, run.get("forest_trees"));
    assertEquals(500, ((Map<?, ?>) run.get("forest_grid")).get("trees"));
    final Map<?, ?> analogy = (Map<?, ?>) run.get("analogy_estimate");
    assertEquals(expected.chosen(), analogy.get("column"));
    assertEquals(AnalogyEstimate.CANDIDATES.size(),
        ((List<?>) analogy.get("similarity_medians")).size());
  }

  @Test
  @DisplayName("inner report: under the temporal split the analogy estimate is the fifteenth "
      + "model, with MMRE and Pred(25) for every model and its paired comparisons; the forests "
      + "are refitted at 500 trees, as swept; the declared split rule is described")
  void innerReportReadsTheFifteenthModel() throws Exception {
    final Map<String, String> settings =
        select(SelectionFixture.temporal(dir.resolve("temporal.tsv")));
    final Path reports = dir.resolve("inner");
    settings.put("REPORT_DIR", reports.toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context());
    assertEquals(1, runs.size(), "the inner report records nothing");

    final List<String> models =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.MODELS));
    assertTrue(models.getFirst().contains("\tmmre\tpred25"), models.getFirst());
    for (final String reading : List.of("usual_flow", "all")) {
      assertEquals(15, models.stream().filter(l -> l.startsWith(reading + "\t")).count(),
          reading);
    }
    // the analogy estimate's MAE is the one its choice was made on
    final Map<?, ?> analogy = (Map<?, ?>) runs.getFirst().get("analogy_estimate");
    final double chosenMae = ((List<?>) analogy.get("similarity_medians")).stream()
        .map(c -> (Map<?, ?>) c).filter(c -> c.get("median").equals(analogy.get("column")))
        .mapToDouble(c -> (Double) c.get("mae_business_days")).findFirst().orElseThrow();
    final List<String> header = List.of(models.getFirst().split("\t"));
    final String[] a = row(models, "usual_flow", SimilarityScoring.ANALOGY);
    assertEquals(chosenMae, Double.parseDouble(a[header.indexOf("mae_bd")]), 1e-9);
    // each forest as the selection swept it: the same rows, the same 500 trees
    for (final Map<String, Object> m : rows.getFirst()) {
      final Map<?, ?> forest = (Map<?, ?>) m.get("forest");
      final String[] f =
          row(models, "usual_flow", SimilarityScoring.forest((String) m.get("model")));
      assertEquals((Double) forest.get("mae_business_days"),
          Double.parseDouble(f[header.indexOf("mae_bd")]), 1e-9, (String) m.get("model"));
    }

    final List<String> comparisons =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.COMPARISONS));
    for (final String b : SimilarityScoring.ANALOGY_AGAINST) {
      assertTrue(comparisons.stream().anyMatch(l -> l.startsWith("usual_flow\tall scored\t")
          && l.contains("\t" + SimilarityScoring.ANALOGY + "\t" + b + "\t")), b);
    }
    final String report =
        Files.readString(reports.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertTrue(report.contains("- split: " + TemporalSplit.RULE), report);
    assertTrue(report.contains("500 trees"), report);
    assertTrue(report.contains("| MMRE | Pred(25) |"), report);
    assertTrue(report.contains("new projects in the inner validation: 0"), report);
    assertFalse(report.contains("without the issues of new projects"), report);
  }

  @Test
  @DisplayName("inner report: with a new project in the inner validation, both readings are "
      + "given again without its issues, with their count")
  void innerReportWithoutNewProjects() throws Exception {
    final Map<String, String> settings =
        select(SelectionFixture.temporal(dir.resolve("temporal.tsv"), 3));
    final Path reports = dir.resolve("inner");
    settings.put("REPORT_DIR", reports.toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context());

    final String report =
        Files.readString(reports.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertTrue(report.contains("new projects in the inner validation: 1, with 3 issues"), report);
    assertTrue(report.contains("without the issues of new projects"), report);
    final List<String> models =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.MODELS));
    final List<String> header = List.of(models.getFirst().split("\t"));
    final int all =
        Integer.parseInt(row(models, "all", SimilarityScoring.GLOBAL)[header.indexOf("n")]);
    final int without = Integer.parseInt(
        row(models, "all_without_new_projects", SimilarityScoring.GLOBAL)[header.indexOf("n")]);
    assertEquals(all - 3, without);
    assertEquals(15, models.stream()
        .filter(l -> l.startsWith("usual_flow_without_new_projects\t")).count());
  }

  @Test
  @DisplayName("inner report: a parameters file of another split rule than the run's is refused, "
      + "as is a temporal one without the analogy estimate's choice or the trees, before anything "
      + "is scored or written")
  void innerReportRefusesAnotherDesign() throws Exception {
    final Map<String, String> settings =
        select(SelectionFixture.temporal(dir.resolve("temporal.tsv")));
    final Path reports = dir.resolve("inner");
    settings.put("REPORT_DIR", reports.toString());
    final Path parameters = dir.resolve("elastic-net.tsv");
    final List<String> temporal = Files.readAllLines(parameters);

    settings.remove(SplitRule.SETTING);
    assertTrue(refused(new IssuesSimilarityInnerReportCommand(settings::get), context())
        .contains("written under the split rule temporal"));
    settings.put(SplitRule.SETTING, "temporal");
    Files.write(parameters, temporal.stream().filter(l -> !l.startsWith("analogy\t")).toList());
    assertTrue(refused(new IssuesSimilarityInnerReportCommand(settings::get), context())
        .contains("freezes no choice of the analogy estimate"));
    Files.write(parameters, temporal.stream()
        .filter(l -> !l.equals(ElasticNetSelection.treesLine(500))).toList());
    assertTrue(refused(new IssuesSimilarityInnerReportCommand(settings::get), context())
        .contains("gives no tree count for the forests"));
    assertFalse(Files.exists(reports));
  }

  @Test
  @DisplayName("the forests of the temporal split are the library's 500 trees; the random "
      + "split's stay at 300")
  void treeCounts() {
    assertEquals(500, ElasticNetSelection.trees(SplitRule.TEMPORAL));
    assertEquals(Forest.TREES, ElasticNetSelection.trees(SplitRule.RANDOM));
    assertEquals(300, Forest.TREES);
  }
}
