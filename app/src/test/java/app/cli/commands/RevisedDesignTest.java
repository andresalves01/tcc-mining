package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.FirstCommit;
import app.issues.PredictionIntervals;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.TeamColumns;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.port.ResultStore;
import app.pipeline.ExtractionFixture;
import app.pipeline.IssuesFirstCommitExtractTask;
import app.pipeline.IssuesInProgressExtractTask;
import app.pipeline.IssuesSimilarityTask;
import app.pipeline.clustering.SmileKMeans;
import app.pipeline.port.SilentProgress;
import app.study.Row;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The revised design end to end: the extraction and the similarity stage on a fixture whose first
 * commits are spread over thirty weeks, so the issues do not all overlap in time and the
 * point-in-time histories hold issues; then the selection and the test step on the synthetic
 * matrix at the revised verdicts. An issue crossing a level's cut teaches nothing at that level:
 * no fitting row, reference, fence or history reads its label, which the tests show by changing
 * those labels and finding every output of that level the same.
 */
class RevisedDesignTest {

  private static final Population FC = Population.FIRST_COMMIT;

  @TempDir Path dir;

  private static IssuesInProgressExtractTask.Report extraction(final ExtractionFixture fixture,
      final SplitRule rule) {
    return new IssuesFirstCommitExtractTask(fixture.source(), SilentProgress.INSTANCE, rule).run()
        .extraction();
  }

  private static IssuesSimilarityTask.Report similarity(final ExtractionFixture fixture,
      final List<RowCache.Entry> entries) {
    final IssuesSimilarityTask.Report s = new IssuesSimilarityTask(fixture.source(),
        SilentProgress.INSTANCE, StagesFixture.WORDS, new SmileKMeans(), FC, SplitRule.REVISED)
        .run(entries);
    assertEquals(List.of(), s.refused());
    return s;
  }

  /** The ids whose verdicts {@code which} accepts. */
  private static Set<String> ids(final List<RowCache.Entry> entries,
      final Predicate<RowCache.Entry> which) {
    return entries.stream().filter(which).map(e -> e.unit().issueId())
        .collect(Collectors.toSet());
  }

  /** The entries with the target of those {@code which} accepts moved by a hundred days. */
  private static List<RowCache.Entry> retargeted(final List<RowCache.Entry> entries,
      final Predicate<RowCache.Entry> which) {
    return retargeted(entries, which, days -> days + 100);
  }

  /** The entries with the target of those {@code which} accepts set to {@code to} of it. */
  private static List<RowCache.Entry> retargeted(final List<RowCache.Entry> entries,
      final Predicate<RowCache.Entry> which, final java.util.function.DoubleUnaryOperator to) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      if (!which.test(e)) {
        out.add(e);
        continue;
      }
      final Map<String, Double> values = new LinkedHashMap<>(e.row().values());
      values.put(FirstCommit.TARGET_COLUMN, to.applyAsDouble(values.get(FirstCommit.TARGET_COLUMN)));
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, e.row().absent()),
          e.parts()));
    }
    return out;
  }

  /** The similarity columns of every row whose name ends with {@code suffix}. */
  private static Map<String, Map<String, Double>> columns(final IssuesSimilarityTask.Report s,
      final String suffix) {
    final Map<String, Map<String, Double>> out = new HashMap<>();
    s.result().columns().forEach((id, values) -> out.put(id, values.entrySet().stream()
        .filter(c -> c.getKey().endsWith(suffix))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
    return out;
  }

  private static boolean outerCrossing(final RowCache.Entry e) {
    return Verdict.CROSSING.is(e.outer(FC));
  }

  @Test
  @DisplayName("the revised extraction keeps the temporal design's validation and test, and marks "
      + "issues crossing each cut on its training side")
  void sidesAreTheTemporalDesigns() {
    final ExtractionFixture fixture = new ExtractionFixture(1);
    final List<RowCache.Entry> revised = extraction(fixture, SplitRule.REVISED).entries();
    final List<RowCache.Entry> temporal = extraction(fixture, SplitRule.TEMPORAL).entries();
    final Predicate<RowCache.Entry> test = e -> Verdict.TEST.is(e.outer(FC));
    final Predicate<RowCache.Entry> validation =
        e -> Verdict.scored(Variant.INNER, e, FC);
    assertEquals(ids(temporal, test), ids(revised, test), "the outer test");
    assertEquals(ids(temporal, validation), ids(revised, validation), "the inner validation");
    assertEquals(ids(temporal, e -> Verdict.trainingSide(Variant.INNER, e, FC)),
        ids(revised, e -> Verdict.trainingSide(Variant.INNER, e, FC)), "the inner fitting side");
    assertTrue(revised.stream().anyMatch(RevisedDesignTest::outerCrossing),
        "an issue crosses the outer cut");
    assertTrue(revised.stream().anyMatch(e -> Verdict.CROSSING.is(e.inner(FC))
        && Verdict.TRAIN.is(e.outer(FC))), "an issue crosses the inner cut alone (case 2)");
    assertTrue(temporal.stream().noneMatch(e -> Verdict.CROSSING.is(e.outer(FC))
        || Verdict.CROSSING.is(e.inner(FC))), "the temporal design marks none");
  }

  @Test
  @DisplayName("the revised similarity: point-in-time histories hold issues, none crossing its "
      + "level's cut; the labels of crossing issues move no column of the level they cross; the "
      + "inner and outer columns agree on the rows of case 1")
  void crossingIssuesTeachNoHistory() {
    final ExtractionFixture fixture = new ExtractionFixture(1);
    final IssuesInProgressExtractTask.Report r = extraction(fixture, SplitRule.REVISED);
    final List<RowCache.Entry> entries = TeamColumns.join(r.entries(), r.team());
    final Map<String, RowCache.Entry> byId = new HashMap<>();
    entries.forEach(e -> byId.put(e.unit().issueId(), e));
    final IssuesSimilarityTask.Report s = similarity(fixture, entries);

    // every neighbour teaches at the query's level and was resolved before the query's start
    int read = 0;
    for (final Augmentation.Query q : s.result().queries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final RowCache.Entry m = byId.get(n.id());
        assertTrue(Verdict.teaches(q.variant(), m, FC), q + " " + n);
        assertTrue(m.unit().resolvedAt().isBefore(byId.get(q.id()).unit().startedAt()),
            q + " " + n);
        read++;
      }
    }
    for (final Augmentation.EmbeddingQuery q : s.result().embeddingQueries()) {
      for (final Augmentation.Neighbour n : q.nearest()) {
        final RowCache.Entry m = byId.get(n.id());
        assertTrue(Verdict.teaches(q.variant(), m, FC), q + " " + n);
        assertTrue(m.unit().resolvedAt().isBefore(byId.get(q.id()).unit().startedAt()),
            q + " " + n);
        read++;
      }
    }
    for (final Augmentation.ClusterFit f : s.result().clusterFits()) {
      for (final String m : f.members().stream().flatMap(List::stream).toList()) {
        assertTrue(Verdict.teaches(f.variant(), byId.get(m), FC), f.variant() + " " + m);
      }
    }
    assertTrue(read > 0, "at least one point-in-time history holds an issue");

    // the labels of the issues crossing the outer cut (cases 3 and 5) teach at no level
    final IssuesSimilarityTask.Report outerMoved =
        similarity(fixture, retargeted(entries, RevisedDesignTest::outerCrossing));
    assertEquals(s.result().columns(), outerMoved.result().columns(),
        "no column of any row moved");
    // the labels of the inner fitting issues crossing the inner cut alone (case 2) teach the
    // outer level, never the inner one
    final Predicate<RowCache.Entry> case2 =
        e -> Verdict.TRAIN.is(e.outer(FC)) && Verdict.CROSSING.is(e.inner(FC));
    final IssuesSimilarityTask.Report innerMoved = similarity(fixture, retargeted(entries, case2));
    assertEquals(columns(s, Variant.INNER.suffix()), columns(innerMoved, Variant.INNER.suffix()),
        "no inner column moved");

    // case 1: the issues resolved before its start that teach are the same at both levels, so
    // the neighbour and reporter medians agree; the clusters' centres differ by level
    final List<String> paired = new ArrayList<>();
    Augmentation.JACCARD_N.forEach(n -> paired.add("jaccard_median_" + n));
    Augmentation.EMBEDDING_N.forEach(n -> paired.add("embedding_median_" + n));
    paired.add("reporter_median");
    int compared = 0;
    for (final RowCache.Entry e : entries) {
      if (!(Verdict.TRAIN.is(e.outer(FC)) && Verdict.TRAIN.is(e.inner(FC)))) {
        continue;
      }
      final Map<String, Double> values = s.result().columns().get(e.unit().issueId());
      for (final String column : paired) {
        assertEquals(values.get(column + Variant.INNER.suffix()),
            values.get(column + Variant.OUTER.suffix()), e.unit().issueId() + " " + column);
        compared += values.get(column + Variant.INNER.suffix()) == null ? 0 : 1;
      }
    }
    assertTrue(compared > 0, "some case-1 row has a value to compare");
  }

  // ------------------------------------------------------------------ selection and test step

  private final List<Map<String, Object>> recorded = new ArrayList<>();
  private final List<List<Map<String, Object>>> recordedRows = new ArrayList<>();
  private final List<Map<String, Object>> upstream = new ArrayList<>();

  private Context context() {
    final PipelineConfig own = new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
    final ResultStore revised = new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        recorded.add(run);
        recordedRows.add(rows);
        return "revised-run";
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(recorded.stream().anyMatch(r -> stage.equals(r.get("stage"))));
      }

      @Override
      public Optional<Set<SplitRule>> designs() {
        return Optional.of(recorded.stream().map(SplitRule::recordedBy)
            .collect(Collectors.toSet()));
      }

      @Override
      public Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
        final List<Map<String, Object>> all = new ArrayList<>(upstream);
        all.addAll(recorded);
        return RevisedUpstream.runs(all, stage);
      }
    };
    final ResultStore random = new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
        throw new AssertionError("the revised design records in its own collection");
      }

      @Override
      public ResultStore design(final SplitRule rule) {
        assertEquals(SplitRule.REVISED, rule);
        return revised;
      }
    };
    return new Context(own, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, random, null);
  }

  /** The test step on {@code matrix} with the frozen {@code parameters}, in a store of its own. */
  private Map<String, Object> test(final Path matrix, final Path parameters, final Path reports)
      throws Exception {
    recorded.clear();
    recordedRows.clear();
    upstream.clear();
    upstream.addAll(RevisedUpstream.of(matrix));
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put("REPORT_DIR", reports.toString());
    settings.put(SplitRule.SETTING, "revised");
    new IssuesSimilarityTestCommand(settings::get, FC).run(context());
    return recorded.getLast();
  }

  @Test
  @DisplayName("selection and test step under the revised rule: the labels of the issues crossing "
      + "the outer cut leave the reserved fit's references, fences and the test's predictions "
      + "unchanged")
  void reservedFitReadsNoCrossingLabel() throws Exception {
    final Path fixture = SelectionFixture.revised(dir.resolve("fixture.tsv"));
    final List<String> columns = RowCache.columns(fixture);
    final List<RowCache.Entry> entries = RowCache.read(fixture);
    assertTrue(entries.stream().anyMatch(RevisedDesignTest::outerCrossing));
    // both matrices through the same writer, one with the crossing labels moved
    final Path matrix = dir.resolve("matrix.tsv");
    RowCache.write(matrix, entries, columns, List.of(FC));
    // the test step checks each target against its calendar's business days: one day is valid
    final Path moved = dir.resolve("moved.tsv");
    final List<RowCache.Entry> retargeted =
        retargeted(entries, RevisedDesignTest::outerCrossing, days -> 1);
    assertTrue(entries.stream().filter(RevisedDesignTest::outerCrossing).anyMatch(e -> e.row()
        .values().get(FirstCommit.TARGET_COLUMN) > 1), "some crossing label moves");
    RowCache.write(moved, retargeted, columns, List.of(FC));

    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put(SplitRule.SETTING, "revised");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context());

    // the same frozen choices on the moved matrix: only its checksum line names the other file
    final Path movedParameters = dir.resolve("elastic-net-moved.tsv");
    final String sha = Sha256.of(matrix);
    final String movedSha = Sha256.of(moved);
    Files.write(movedParameters, Files.readAllLines(parameters).stream()
        .map(l -> l.replace(sha, movedSha)).toList());

    final Map<String, Object> first = test(matrix, parameters, dir.resolve("test"));
    final Map<String, Object> second = test(moved, movedParameters, dir.resolve("test-moved"));
    for (final String file : List.of(IssuesSimilarityTestCommand.PREDICTIONS,
        IssuesSimilarityTestCommand.DIAGNOSTICS, IssuesSimilarityTestCommand.RELEVANCE)) {
      assertEquals(Files.readString(dir.resolve("test").resolve(file)),
          Files.readString(dir.resolve("test-moved").resolve(file)), file);
    }
    for (final String field : List.of("tail_cut", "fit_rows", "scored_rows",
        "usual_flow_scored_rows", "error_band_tops_business_days", "inner_fit_rows")) {
      assertEquals(first.get(field), second.get(field), field);
    }
  }

  // ------------------------------------------------------------------ the main reading

  private interface Step {
    void run() throws Exception;
  }

  /** What {@code step} prints while it runs, line by line. */
  private static List<String> printed(final Step step) throws Exception {
    final java.io.PrintStream out = System.out;
    final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try {
      System.setOut(new java.io.PrintStream(bytes, true,
          java.nio.charset.StandardCharsets.UTF_8));
      step.run();
    } finally {
      System.setOut(out);
    }
    return bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
  }

  /** The reading names of a file's first column, in the order they first appear. */
  private static List<String> readingOrder(final List<String> lines) {
    return lines.stream().skip(1).map(l -> l.split("\t", 2)[0]).distinct().toList();
  }

  /** The readings of recorded rows, in the order they first appear. */
  private static List<String> readingOrderOfRows(final List<Map<String, Object>> rows) {
    return rows.stream().filter(r -> r.containsKey("reading"))
        .map(r -> (String) r.get("reading")).distinct().toList();
  }

  /**
   * Whether a printed line says the intervals are calibrated on the usual flow, as every rule but
   * the revised one does ({@link PredictionIntervals#RULE}, {@link PredictionIntervals#OUTER}),
   * or gives the calibration halves or prediction spans of the usual-flow validation issues.
   */
  private static boolean calibratesOnTheUsualFlow(final String line) {
    return line.contains("calibrated on the usual-flow inner validation")
        || line.contains("calibrated on the whole usual-flow inner validation")
        || line.contains("calibration halves of the") && line.contains("usual-flow validation")
        || line.contains("prediction span on the usual-flow");
  }

  /** All issues before the usual flow, and likewise without the issues of new projects. */
  private static void allFirst(final List<String> names, final String where) {
    assertTrue(names.size() >= 2, where + ": " + names);
    assertEquals(List.of("all", "usual_flow"), names.subList(0, 2), where);
    if (names.contains("usual_flow_without_new_projects")) {
      assertTrue(names.indexOf("all_without_new_projects")
          < names.indexOf("usual_flow_without_new_projects"), where + ": " + names);
    }
  }

  @Test
  @DisplayName("under the revised rule all issues are the main reading and the usual flow the "
      + "complementary one: the selection's record still says the choices are scored on the "
      + "usual flow; the inner report and the test step give all issues first in their sections, "
      + "files and rows, the machine names unchanged; the usual-flow fence is read from the log; "
      + "the intervals are calibrated on every validation issue, the test step's labelled the "
      + "approximation for comparison")
  void fullPopulationIsTheMainReading() throws Exception {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path fixture = SelectionFixture.revised(dir.resolve("fixture.tsv"));
    RowCache.write(matrix, RowCache.read(fixture), RowCache.columns(fixture), List.of(FC));
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    settings.put(SplitRule.SETTING, "revised");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context());
    assertEquals("usual flow (complementary reading): the scored issues at or below the k = 3 "
        + "fence", recorded.getLast().get("validation_scored"), "the selection's record");

    // the inner report: all issues first in its header, its sections and its files
    final Path inner = dir.resolve("inner");
    settings.put("REPORT_DIR", inner.toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context());
    final List<String> report =
        Files.readAllLines(inner.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- readings: main, all issues (main "
        + "reading): every scored issue, those above the k = 3 fence included: ")
        && l.contains("; complementary, usual flow (complementary reading): the scored issues "
            + "at or below the k = 3 fence: ")), String.join("\n", report));
    final List<String> sections = report.stream()
        .filter(l -> l.startsWith("## Main reading") || l.startsWith("## Complementary reading"))
        .toList();
    assertTrue(sections.size() >= 2, sections.toString());
    assertTrue(sections.get(0).startsWith("## Main reading: all issues ("), sections.toString());
    assertTrue(sections.get(1).startsWith("## Complementary reading: the usual flow ("),
        sections.toString());
    assertTrue(report.stream().noneMatch(l -> l.contains("Main reading: the usual flow")
        || l.contains("usual flow (main reading)") || l.contains("all issues (complementary")
        || l.contains("illustrated on the complementary reading")),
        "no line calls the usual flow the main reading");
    // the intervals are calibrated on every validation issue, the tail above the usual flow's
    // fence included: the halves and the factors for later use
    final List<String> innerPredictions =
        Files.readAllLines(inner.resolve(IssuesSimilarityInnerReportCommand.PREDICTIONS));
    final int validation = innerPredictions.size() - 1;
    assertEquals("usual_flow", innerPredictions.getFirst().split("\t")[4]);
    final long tail = innerPredictions.stream().skip(1)
        .filter(l -> l.split("\t")[4].equals("false")).count();
    assertTrue(tail > 0, "some validation issue above the usual flow's fence");
    final List<String> calibration =
        Files.readAllLines(inner.resolve(IssuesSimilarityInnerReportCommand.CALIBRATION));
    assertEquals(Set.of(Integer.toString(validation)), calibration.stream().skip(1)
        .map(l -> l.split("\t")[2]).collect(Collectors.toSet()), "every factor's issues");
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- calibration halves of the "
        + validation + " validation issues, the " + tail + " above the usual flow's k = 3 fence "
        + "included: ")), String.join("\n", report));
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- prediction intervals: "
        + PredictionIntervals.rule(1, SplitRule.REVISED))), String.join("\n", report));
    assertTrue(report.stream().noneMatch(RevisedDesignTest::calibratesOnTheUsualFlow),
        "no line calibrates on the usual flow");
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.MODELS,
        IssuesSimilarityInnerReportCommand.BANDS, IssuesSimilarityInnerReportCommand.COMPARISONS,
        IssuesSimilarityInnerReportCommand.ORDERING, IssuesSimilarityInnerReportCommand.INTERVALS,
        IssuesSimilarityInnerReportCommand.UNCERTAINTY,
        IssuesSimilarityInnerReportCommand.PERCENTILES)) {
      allFirst(readingOrder(Files.readAllLines(inner.resolve(file))), file);
    }

    // the test step: all issues first in its readings line, its sections, files and rows
    final Path reports = dir.resolve("test");
    final List<Map<String, Object>> run = new ArrayList<>();
    final List<String> log = printed(() -> run.add(test(matrix, parameters, reports)));
    final List<String> line = log.stream().filter(l -> l.startsWith("  readings: ")).toList();
    assertEquals(1, line.size(), String.join("\n", log));
    assertTrue(line.get(0).startsWith("  readings: all issues (main reading): every scored "
        + "issue, those above the k = 3 fence included, "), line.get(0));
    assertTrue(line.get(0).contains("; usual flow (complementary reading): the scored issues at "
        + "or below the k = 3 fence, "), line.get(0));
    final List<String> read = log.stream().filter(l -> l.startsWith("  READING: ")).toList();
    assertTrue(read.size() >= 2, read.toString());
    assertTrue(read.get(0).startsWith("  READING: all issues (main reading)"), read.toString());
    assertTrue(read.get(1).startsWith("  READING: usual flow (complementary reading)"),
        read.toString());
    if (read.size() > 2) {
      assertTrue(read.get(2).startsWith("  READING: all issues (main reading)"), read.toString());
    }
    assertTrue(log.stream().noneMatch(l -> l.contains("usual flow (main reading)")
        || l.contains("all issues (complementary")
        || l.contains("illustrated on the complementary reading")),
        "no line calls the usual flow the main reading");
    assertTrue(!run.getFirst().get("prediction_percentiles").toString()
        .contains("illustrated on the complementary reading"), "the record's percentile rule");
    allFirst(readingOrderOfRows(recordedRows.getLast()), "the test step's rows");
    // the test step's intervals are the approximation, for comparison, and say so; the official
    // ones are the same-model step's, calibrated on the same issues
    assertEquals(PredictionIntervals.rule(1, SplitRule.REVISED),
        run.getFirst().get("prediction_intervals"));
    assertEquals(PredictionIntervals.outer(SplitRule.REVISED),
        run.getFirst().get("prediction_interval_outer_calibration"));
    assertTrue(run.getFirst().get("prediction_interval_official").toString()
        .contains(IssuesFirstCommitSameModelCalibrationCommand.STAGE));
    assertTrue(log.stream().anyMatch(l -> l.startsWith("  prediction intervals, the inner "
        + "calibration applied (the approximation, for comparison; the official intervals are "
        + IssuesFirstCommitSameModelCalibrationCommand.STAGE + "'s)")), String.join("\n", log));
    assertTrue(log.stream().noneMatch(RevisedDesignTest::calibratesOnTheUsualFlow),
        "no line calibrates on the usual flow");
    final String percentiles = run.getFirst().get("prediction_percentiles").toString();
    assertTrue(percentiles.contains("they illustrate the approximation"), percentiles);
    assertFalse(percentiles.contains("usual-flow coverage"), percentiles);
    @SuppressWarnings("unchecked")
    final Map<String, Object> newProjects = (Map<String, Object>) run.getFirst()
        .get("new_projects");
    if (newProjects.get("readings_without_them") instanceof List<?> names) {
      assertEquals(List.of("all_without_new_projects", "usual_flow_without_new_projects"), names);
    }
    for (final String file : List.of(IssuesSimilarityTestCommand.UNCERTAINTY,
        IssuesSimilarityTestCommand.PERCENTILES)) {
      allFirst(readingOrder(Files.readAllLines(reports.resolve(file))), file);
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> cut = (Map<String, Object>) run.getFirst().get("tail_cut");
    assertEquals(Math.round((double) cut.get("usual_flow_fence_business_days") * 1000) / 1000.0,
        app.issues.PredictedBands.usualFence(log), 1e-9, "the fence, read from the log");
  }
}
