package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.FirstCommit;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.model.Fitting;
import app.issues.port.ResultStore;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The revised design on {@link SelectionFixture#revised}: the temporal fixture's rows at the
 * revised verdicts, its tails and the issues started shortly before each cut crossing it. No
 * issue crossing a level's cut is among that level's fitting rows or moves its references and
 * fences; the inner validation and the outer test keep the temporal design's issues.
 */
class RevisedSelectionTest {

  private static final Population FC = Population.FIRST_COMMIT;

  @TempDir Path dir;

  private final Map<String, List<Map<String, Object>>> collections = new HashMap<>();

  private static PipelineConfig own() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  /** Run collections in memory, each design's apart, as the Mongo store keeps them. */
  private ResultStore store(final String collection) {
    return new ResultStore() {
      @Override
      public String record(final Map<String, Object> run, final List<Map<String, Object>> r) {
        collections.computeIfAbsent(collection, k -> new ArrayList<>()).add(run);
        return collection + "-run";
      }

      @Override
      public ResultStore design(final SplitRule rule) {
        return store("runs" + rule.collectionSuffix());
      }

      @Override
      public Optional<Set<SplitRule>> designs() {
        final Set<SplitRule> out = new java.util.HashSet<>();
        collections.getOrDefault(collection, List.of())
            .forEach(r -> out.add(SplitRule.recordedBy(r)));
        return Optional.of(out);
      }

      @Override
      public Optional<Boolean> recorded(final String stage) {
        return Optional.of(collections.getOrDefault(collection, List.of()).stream()
            .anyMatch(r -> stage.equals(r.get("stage"))));
      }
    };
  }

  private Context context() {
    return new Context(own(), null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store("runs"), null);
  }

  private static List<String> ids(final List<IssueUnit> units) {
    return units.stream().map(IssueUnit::issueId).sorted().toList();
  }

  @Test
  @DisplayName("the revised matrix: the revised check accepts it and the temporal one refuses "
      + "it, as the revised check refuses the temporal matrix; it holds cases 2, 3 and 5")
  void checks() throws Exception {
    final Path revised = SelectionFixture.revised(dir.resolve("revised.tsv"));
    final Path temporal = SelectionFixture.temporal(dir.resolve("temporal.tsv"));
    assertEquals(List.of(), SplitRule.REVISED.differences(RowCache.splits(revised), FC));
    assertFalse(SplitRule.TEMPORAL.differences(RowCache.splits(revised), FC).isEmpty());
    assertFalse(SplitRule.REVISED.differences(RowCache.splits(temporal), FC).isEmpty());
    assertEquals(List.of(), SplitRule.TEMPORAL.differences(RowCache.splits(temporal), FC));
    final Map<String, Integer> cases = new HashMap<>();
    RowCache.read(revised).forEach(e -> cases.merge(e.outer(FC) + "/" + e.inner(FC), 1,
        Integer::sum));
    for (final String c : List.of("TRAIN/TRAIN", "TRAIN/CROSSING", "CROSSING/CROSSING",
        "TRAIN/TEST", "CROSSING/TEST", "TEST/")) {
      assertTrue(cases.getOrDefault(c, 0) > 0, c + " in " + cases);
    }
  }

  @Test
  @DisplayName("each level fits only the issues that teach there, never one crossing its cut, "
      + "and scores the same issues as the temporal design: the inner validation, case 5 "
      + "included, and the outer test")
  void fittingRowsAndScoredIssues() throws Exception {
    final List<RowCache.Entry> revised =
        RowCache.read(SelectionFixture.revised(dir.resolve("revised.tsv")));
    final List<RowCache.Entry> temporal =
        RowCache.read(SelectionFixture.temporal(dir.resolve("temporal.tsv")));
    for (final Fitting.Mode mode : List.of(Fitting.Mode.INNER, Fitting.Mode.RESERVED)) {
      final Fitting.Sides r = Fitting.sides(revised, mode, FC);
      final Fitting.Sides t = Fitting.sides(temporal, mode, FC);
      assertEquals(ids(t.scoreUnits()), ids(r.scoreUnits()), mode + ": the same scored issues");
      final Map<String, RowCache.Entry> byId = new HashMap<>();
      revised.forEach(e -> byId.put(e.unit().issueId(), e));
      final int column = mode == Fitting.Mode.INNER ? 1 : 0;
      for (final IssueUnit u : r.fitUnits()) {
        final RowCache.Entry e = byId.get(u.issueId());
        final String verdict = column == 0 ? e.outer(FC) : e.inner(FC);
        assertEquals("TRAIN", verdict, mode + " fits " + u.issueId());
      }
      assertTrue(t.fitUnits().size() > r.fitUnits().size(), mode + ": the crossing issues left");
      assertTrue(ids(t.fitUnits()).containsAll(ids(r.fitUnits())));
    }
    final Fitting.Sides inner = Fitting.sides(revised, Fitting.Mode.INNER, FC);
    final List<String> scored = ids(inner.scoreUnits());
    revised.stream().filter(e -> "CROSSING".equals(e.outer(FC)) && "TEST".equals(e.inner(FC)))
        .forEach(e -> assertTrue(scored.contains(e.unit().issueId()),
            "case 5 is scored in the inner validation: " + e.unit().issueId()));
  }

  /**
   * {@code matrix} written again, the target of every issue whose inner verdict is CROSSING (cases
   * 2 and 3: neither taught nor scored at the inner level) set to one business day when {@code
   * relabel}, the least any issue of the population lasts.
   */
  private static Path relabelled(final Path matrix, final Path out, final boolean relabel)
      throws Exception {
    final List<RowCache.Entry> entries = new ArrayList<>();
    for (final RowCache.Entry e : RowCache.read(matrix)) {
      if (!Verdict.CROSSING.is(e.inner(FC))) {
        entries.add(e);
        continue;
      }
      final Map<String, Double> values = new LinkedHashMap<>(e.row().values());
      if (relabel) {
        values.put(FirstCommit.TARGET_COLUMN, 1.0);
      }
      entries.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values,
          e.row().absent()), e.parts()));
    }
    RowCache.write(out, entries, RowCache.columns(matrix), List.of(FC));
    return out;
  }

  /** The selection and the inner report under SPLIT_RULE=revised, in {@code at}. */
  private void selectAndReport(final Path matrix, final Path at) throws Exception {
    final Map<String, String> settings = new HashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", at.resolve("elastic-net.tsv").toString());
    settings.put(SplitRule.SETTING, "revised");
    new IssuesSimilarityElasticNetCommand(settings::get).run(context());
    settings.put("REPORT_DIR", at.resolve("inner").toString());
    new IssuesSimilarityInnerReportCommand(settings::get).run(context());
  }

  @Test
  @DisplayName("the labels of the issues crossing the inner cut move nothing the inner level "
      + "learns: the selection's choices, the references, the fences and every inner score are "
      + "the same with each of their targets set to one day; the runs go to the revised "
      + "design's collection")
  void crossingLabelsTeachNothing() throws Exception {
    final Path matrix = SelectionFixture.revised(dir.resolve("revised.tsv"));
    final Path same = relabelled(matrix, dir.resolve("same.tsv"), false);
    final Path oneDay = relabelled(matrix, dir.resolve("one.tsv"), true);
    assertNotEquals(Files.readString(same), Files.readString(oneDay));
    final Path a = Files.createDirectories(dir.resolve("a"));
    final Path b = Files.createDirectories(dir.resolve("b"));
    selectAndReport(same, a);
    selectAndReport(oneDay, b);
    final List<String> pa = Files.readAllLines(a.resolve("elastic-net.tsv")).stream()
        .filter(l -> !l.contains("matrix_sha256")).toList();
    final List<String> pb = Files.readAllLines(b.resolve("elastic-net.tsv")).stream()
        .filter(l -> !l.contains("matrix_sha256")).toList();
    assertEquals(pa, pb, "the frozen choices, fences and references");
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.MODELS,
        "similarity-inner-predictions.tsv")) {
      assertEquals(Files.readString(a.resolve("inner").resolve(file)),
          Files.readString(b.resolve("inner").resolve(file)), file);
    }
    final List<Map<String, Object>> revised = collections.get("runs_revised");
    assertEquals(2, revised.size(), "the two selections, in the revised design's collection");
    assertTrue(revised.stream().allMatch(r -> SplitRule.recordedBy(r) == SplitRule.REVISED));
    assertFalse(collections.containsKey("runs"), "nothing in the random design's collection");
    assertFalse(collections.containsKey("runs_temporal"));
  }
}
