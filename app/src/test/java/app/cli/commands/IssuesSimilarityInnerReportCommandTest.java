package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.Family;
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.TailCut;
import app.issues.SplitRule;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.pipeline.port.Progress;
import app.study.Construct;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import app.pipeline.port.SilentProgress;

/**
 * The inner report on a synthetic matrix written to disk: what it scores, that it equals the
 * selection on the same rows, that it leaves the outer test unparsed and the database untouched.
 * The tests hand their own settings, so no real matrix is ever read.
 */
class IssuesSimilarityInnerReportCommandTest {

  /** The Kikas retrospective model, ticket 26's. */
  private static final String KIKAS_RETROSPECTIVE = "kikas_retrospective";

  private final Map<String, String> settings = new HashMap<>();

  @TempDir
  Path dir;

  /** A context with no port at all but a result store that fails the test if anything is recorded. */
  private static Context context() {
    return new Context(null, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, (FreshStore) (run, rows) -> {
          throw new AssertionError("recorded " + run);
        }, null);
  }

  /** The issue {@code days} business days after {@code start}, at the boundary 04:00 UTC. */
  private static Instant after(final Instant start, final int days) {
    Instant end = start;
    while (BusinessDays.count(start.toEpochMilli(), end.toEpochMilli(), 4) < days) {
      end = end.plus(Duration.ofDays(1));
    }
    return end;
  }

  /**
   * The retrospective columns of the matrix as the generator drew them before the comments and
   * the participants left the main retrospective model: the ten, the wait on blocking issues
   * among them, which a matrix of an earlier run holds and no model reads, all where the first of
   * them stood and in their old order, so no other column's draw moves.
   */
  private static Set<Attribute> asBeforeTheCeiling(final Set<Attribute> drawn) {
    final List<String> ten = List.of("RETRO_java_lines", "RETRO_mean_mccc",
        "RETRO_mean_nesting", "RETRO_files", "RETRO_familiarity", "RETRO_comments",
        "RETRO_participants", "RETRO_assignee_changes", "RETRO_blocker_wait",
        "RETRO_urgency_days");
    final Map<String, Attribute> retrospective = new HashMap<>();
    drawn.stream().filter(a -> a.name().startsWith("RETRO_"))
        .forEach(a -> retrospective.put(a.name(), a));
    retrospective.put("RETRO_blocker_wait", new Attribute("RETRO_blocker_wait",
        "RETRO_blocker_wait", app.issues.Family.OWN, app.issues.Scenario.Block.EXPOSURE,
        Construct.Kind.NUMERIC, Construct.Transform.LOG1P, true));
    retrospective.put("RETRO_urgency_days", new Attribute("RETRO_urgency_days",
        "RETRO_urgency_days", app.issues.Family.OWN, app.issues.Scenario.Block.EXPOSURE,
        Construct.Kind.NUMERIC, Construct.Transform.NONE, true));
    final Set<Attribute> out = new LinkedHashSet<>();
    for (final Attribute a : drawn) {
      if (a.name().startsWith("RETRO_")) {
        ten.forEach(name -> out.add(retrospective.get(name)));
      } else {
        out.add(a);
      }
    }
    return out;
  }

  /**
   * The columns the generator drew before the fourteen models were settled: those of the six
   * models, with the three start pressure columns, which the own specification with similarity
   * read then and no model reads now, where they stood: the fix version, the release cycle and
   * the inner Blocker pace right after the executor's open workload, the outer Blocker pace right
   * after the outer reporter median. So no other column's draw moves.
   */
  private static Set<Attribute> asBeforeTheFourteen(final Set<Attribute> drawn) {
    final Set<Attribute> out = new LinkedHashSet<>();
    for (final Attribute a : drawn) {
      out.add(a);
      if (a.name().equals("A13_exec_open_load")) {
        out.add(new Attribute("START_fix_version", "START_fix_version", Family.OWN, null,
            Construct.Kind.BINARY, Construct.Transform.NONE, false));
        out.add(new Attribute("START_release_cycle_days", "START_release_cycle_days", Family.OWN, null,
            Construct.Kind.NUMERIC, Construct.Transform.NONE, true));
        out.add(pace(Augmentation.Variant.INNER));
      } else if (a.name().equals(Augmentation.reporter(Augmentation.Variant.OUTER))) {
        out.add(pace(Augmentation.Variant.OUTER));
      }
    }
    return out;
  }

  /** The Blocker pace column of the given variant, as the own specification declared it. */
  private static Attribute pace(final Augmentation.Variant v) {
    final String name = "START_blocker_pace_days" + v.suffix();
    return new Attribute(name, name, Family.OWN, null,
        Construct.Kind.NUMERIC, Construct.Transform.LOG1P, true);
  }

  /**
   * Four projects; per project 20 inner fitting, 10 inner validation and 8 outer test rows, every
   * one of at least one business day, with every column any model declares. With {@code
   * crossing}, two of the outer test rows of each project are instead outer training rows the
   * inner cut set aside, as a matrix of the temporal split held them.
   */
  private static List<RowCache.Entry> entries(final boolean crossing) {
    // the columns of the nine models before ticket 26 draw from the shared generator as they
    // did; any other model's from a draw of its own per row and column, so none of theirs moves
    final Set<Attribute> drawn = new LinkedHashSet<>();
    final Set<Attribute> later = new LinkedHashSet<>();
    for (final Augmentation.Variant v : List.of(Augmentation.Variant.INNER,
        Augmentation.Variant.OUTER)) {
      for (final Cell cell : ElasticNetSelection.everyCell(v, Population.FIRST_COMMIT)) {
        (cell.label().equals(KIKAS_RETROSPECTIVE) ? later : drawn).addAll(cell.attributes());
      }
    }
    later.removeAll(drawn);
    final Set<Attribute> declared = asBeforeTheCeiling(asBeforeTheFourteen(drawn));
    final Random random = new Random(3);
    final Instant created = Instant.parse("2016-02-01T00:00:00Z");
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 4; p++) {
      for (int k = 0; k < 38; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 36 || !crossing ? new String[] {"TEST", ""}
            : new String[] {"TRAIN", "CROSSES"};
        // a tail: every ninth issue from the fifth of each project lasts 60 business days more
        final int days = 1 + (int) Math.floor(Math.exp(3 * random.nextDouble()))
            + (k % 9 == 4 ? 60 : 0);
        final Map<String, Double> values = new HashMap<>();
        for (final Attribute a : declared) {
          final boolean similarity = a.name().startsWith("jaccard_")
              || a.name().startsWith("embedding_") || a.name().startsWith("cluster_");
          if (a.name().startsWith("RETRO_") && n % 5 == 2 && !a.name().contains("comments")
              && !a.name().contains("participants") && !a.name().contains("assignee")) {
            continue;
          }
          values.put(a.name(), similarity ? days * (0.6 + 0.8 * random.nextDouble())
              : a.kind() == Construct.Kind.CATEGORICAL ? random.nextInt(3)
              : random.nextInt(40));
          if (Attributes.all().contains(a)) {
            // the extracted matrix stores the missing indicator beside its own columns
            values.put(a.name() + "_ausente", 0.0);
          }
        }
        for (final Attribute a : later) {
          values.put(a.name(), (double) new Random(1_000_003L * n + a.name().hashCode())
              .nextInt(40));
          if (a.absenceIndicator()) {
            values.put(a.absenceName(), 0.0);
          }
        }
        values.put(app.issues.FirstCommit.BOUNDARY_COLUMN, 4.0);
        values.put(app.issues.FirstCommit.TARGET_COLUMN, (double) Math.max(1, days));
        final String id = "i" + n++;
        final Instant start = created.plusSeconds(3600L * 7 * n);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.FIRST_COMMIT, part);
        out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, "p" + p, "r", "e" + (k % 3),
            created, after(start, days), "Fixed", false, start), new Row(id, values, Set.of()),
            parts));
      }
    }
    return out;
  }

  /**
   * The matrix on disk, its outer test rows corrupted after their split field -- a value, a date
   * and a field count no read could parse -- and the selection's choices on its outer training
   * rows, made on that very file.
   */
  private ElasticNetSelection.Result write(final Path matrix, final Path elasticNet)
      throws IOException {
    return write(matrix, elasticNet, false);
  }

  /** The same, with the temporal split's inner crossing rows when {@code crossing}. */
  private ElasticNetSelection.Result write(final Path matrix, final Path elasticNet,
      final boolean crossing) throws IOException {
    final List<RowCache.Entry> entries = entries(crossing);
    final List<String> columns = new ArrayList<>(new LinkedHashSet<>(entries.stream()
        .flatMap(e -> e.row().values().keySet().stream()).sorted().toList()));
    RowCache.write(matrix, entries, columns, List.of(Population.FIRST_COMMIT));
    final List<String> lines = new ArrayList<>(Files.readAllLines(matrix));
    final int outer = List.of(lines.get(0).split("\t")).indexOf("primeiro_commit_externa");
    for (int k = 1; k < lines.size(); k++) {
      if (!"TRAIN".equals(lines.get(k).split("\t", -1)[outer])) {
        lines.set(k, lines.get(k).replace("2016-", "never-").replace("\t4.0", "\tfour\tx"));
      }
    }
    Files.write(matrix, lines);
    final ElasticNetSelection.Result chosen = ElasticNetSelection.run(BusinessDays.population(
        RowCache.training(matrix, Population.FIRST_COMMIT), Augmentation.Variant.INNER)
        .entries(), 1, Population.FIRST_COMMIT, SplitRule.REVISED);
    Files.write(elasticNet, ElasticNetSelection.parameters(chosen, Sha256.of(matrix),
        Population.FIRST_COMMIT, SplitRule.REVISED));
    return chosen;
  }

  private IssuesSimilarityInnerReportCommand command() {
    return new IssuesSimilarityInnerReportCommand(settings::get);
  }

  private void set(final Path matrix, final Path elasticNet, final Path reports) {
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", elasticNet.toString());
    settings.put("REPORT_DIR", reports.toString());
  }

  /** One reading's lines of a file, by model, the reading's column left out. */
  private static Map<String, String[]> byModel(final List<String> tsv, final String reading) {
    final Map<String, String[]> out = new LinkedHashMap<>();
    tsv.subList(1, tsv.size()).stream().filter(l -> l.startsWith(reading + "\t")).forEach(l -> {
      final String[] f = l.substring(reading.length() + 1).split("\t", -1);
      out.put(f[0], f);
    });
    return out;
  }

  @Test
  @DisplayName("on the outer training rows alone, the outer test never parsed and nothing "
      + "recorded, every model's error in the usual-flow reading is, to the bit, the selection's "
      + "inner validation error; the all-issues reading holds every validation issue")
  void reportsTheSelectionsErrors() throws IOException, CommandFailedException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    final ElasticNetSelection.Result chosen = write(matrix, elasticNet);
    assertThrows(RuntimeException.class, () -> RowCache.read(matrix), "the corruption parses");
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    command().run(context());

    final List<String> modelLines =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.MODELS));
    final Map<String, String[]> models = byModel(modelLines, "usual_flow");
    final Map<String, String[]> all = byModel(modelLines, "all");
    // the six models, their forests, the two medians and the analogy estimate
    assertEquals(15, models.size(), models.keySet().toString());
    assertEquals(models.keySet(), all.keySet());
    assertEquals(1 + 2 * 15, modelLines.size());
    assertEquals(chosen.validationRows(), Integer.parseInt(models.get("global_median")[2]));
    for (final ElasticNetSelection.Model m : chosen.models()) {
      final String name = m.cell().label();
      assertEquals(m.fitted().score().mae(), Double.parseDouble(models.get(name)[3]), name);
      assertEquals(m.forest().score().mae(),
          Double.parseDouble(models.get(SimilarityScoring.forest(name))[3]), name);
    }
    assertEquals(chosen.projectMae(), Double.parseDouble(models.get("project_median")[3]));
    assertEquals(chosen.constantMae(), Double.parseDouble(models.get("global_median")[3]));

    final List<String> predictions =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.PREDICTIONS));
    assertEquals(List.of("id", "key", "project", "observed_business_days", "usual_flow",
        "has_java_code"), List.of(predictions.get(0).split("\t")).subList(0, 6));
    final int validation = Integer.parseInt(all.get("global_median")[2]);
    assertEquals(1 + validation, predictions.size());
    assertTrue(chosen.validationRows() < validation, "the fixture's validation has a tail");
    assertEquals(chosen.validationRows(), predictions.stream().skip(1)
        .filter(l -> "true".equals(l.split("\t")[4])).count());
    final List<String> report =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertTrue(report.get(2).contains("optimistic") && report.get(2).contains("same inner "
        + "validation"), report.get(2));
    // the header states the chosen k, both fences and each reading's count of issues
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- chosen k: "
        + TailCut.label(chosen.cut().k()) + ", fence " + chosen.cut().fence())
        && l.contains("usual flow: k 3.0, fence " + chosen.cut().usualFence())),
        report.toString());
    // the revised design's main reading is every issue; the usual flow is the complementary one
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- readings: main, all issues")
        && l.contains(": " + validation + " inner validation issues")
        && l.endsWith(": " + chosen.validationRows())), report.toString());
    assertTrue(report.contains("## Main reading: all issues (" + validation + " issues)"),
        report.toString());
    assertTrue(report.contains("## Complementary reading: the usual flow ("
        + chosen.validationRows() + " issues)"), report.toString());
    // the roles of the retrospective readings and of Kikas's, the model the k was chosen on
    assertTrue(report.contains("- kikas_retrospective: "
        + ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE), report.toString());
    assertTrue(report.contains("- " + ElasticNetSelection.TAIL_CUT_MODEL), report.toString());
    assertTrue(report.contains("## Estimation uncertainty"));
    assertTrue(report.contains("## SPSP uncertainty context"));
    final List<String> uncertainty = Files.readAllLines(reports.resolve(
        IssuesSimilarityInnerReportCommand.UNCERTAINTY));
    // the fifteen rows of the models file: the analogy estimate among them
    assertEquals(1 + 2 * 15 * 9 * 6, uncertainty.size());
    assertEquals(1 + 2 * 15 * 5, Files.readAllLines(reports.resolve(
        IssuesSimilarityInnerReportCommand.PERCENTILES)).size());
    for (final String reading : List.of("usual_flow", "all")) {
      assertEquals(15, uncertainty.stream().skip(1).map(l -> l.split("\t", -1))
          .filter(f -> f[0].equals(reading) && f[2].equals("overall")
              && f[9].equals("mae_bd")).count());
      assertTrue(uncertainty.stream().anyMatch(l -> l.startsWith(reading + "\t")
          && l.contains("\tstart_description\tunavailable_or_blank\t")));
    }
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- each k on "
        + "own_with_similarity_before_pressure, its error")), report.toString());
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.BANDS,
        IssuesSimilarityInnerReportCommand.COMPARISONS, IssuesSimilarityInnerReportCommand.ORDERING,
        IssuesSimilarityInnerReportCommand.DIAGNOSTICS,
        IssuesSimilarityInnerReportCommand.IMPORTANCE,
        IssuesSimilarityInnerReportCommand.RELEVANCE)) {
      assertTrue(Files.readAllLines(reports.resolve(file)).size() > 1, file);
    }
    final List<String> comparisons =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.COMPARISONS));
    for (final String reading : List.of("usual_flow\t", "all\t")) {
      assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading
          + "without Java code\t")), reading);
      assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading
          + "with Java code\t")), reading);
    }
    final List<String> agreed = List.of(
        "\town_with_similarity_before_pressure\talzubaidi_start\t",
        "\town_with_similarity_before_pressure\tkikas_start\t",
        "\town_retrospective\town_with_similarity_before_pressure\t",
        "\town_retrospective_with_discussion\town_retrospective\t",
        "\town_retrospective_with_discussion\tkikas_retrospective\t",
        "\town_retrospective\tkikas_retrospective\t",
        "\town_with_similarity_before_pressure_forest\talzubaidi_start_forest\t",
        "\town_retrospective_forest\town_with_similarity_before_pressure_forest\t",
        "\town_retrospective_with_discussion_forest\tkikas_retrospective_forest\t",
        "\tkikas_retrospective\tglobal_median\t",
        "\tkikas_retrospective_forest\tproject_median\t");
    for (final String pair : agreed) {
      for (final String reading : List.of("usual_flow\t", "all\t")) {
        assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading + "all scored\t")
            && l.contains(pair)), reading + pair);
      }
    }
    // the comparisons: 6 agreed pairs, 7 against the global median, 6 against the project
    // median, then the forests' 6, 6 and 6, and the analogy estimate against the six start
    // models and the two medians, per reading
    assertEquals(2 * 45, comparisons.stream().filter(l -> l.contains("\tall scored\t"))
        .count());
    final List<String> ordering =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.ORDERING));
    assertTrue(ordering.stream().noneMatch(l -> l.contains("\tglobal_median\t")));
    // no pair with the global median, which ranks nothing: its 7, the forests' 6 and the analogy's
    assertEquals(1 + 2 * (45 - 7 - 6 - 1), ordering.size());
    for (final String pair : agreed.stream().filter(p -> !p.contains("global_median")).toList()) {
      assertTrue(ordering.stream().anyMatch(l -> l.startsWith("usual_flow" + pair)), pair);
      assertTrue(ordering.stream().anyMatch(l -> l.startsWith("all" + pair)), pair);
    }
    final List<String> bands =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.BANDS));
    assertTrue(Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.IMPORTANCE))
        .stream().anyMatch(l -> l.startsWith("kikas_retrospective_forest\t")));
    // no model the fourteen leave out, nor a forest against its own Elastic Net, in any file
    try (var files = Files.list(reports)) {
      for (final Path file : files.toList()) {
        for (final String line : Files.readAllLines(file)) {
          final List<String> f = List.of(line.split("[\t |,;:()\\[\\]]+"));
          for (final String name : LEFT_OUT) {
            assertFalse(f.contains(name), file.getFileName() + ": " + line);
          }
        }
      }
    }
    assertTrue(comparisons.stream().noneMatch(l -> {
      final String[] f = l.split("\t");
      return f.length > 4 && f[3].equals(f[4] + "_forest");
    }));

    // ticket 24: the bands at the fitting quartiles, the intervals per reading, model and level,
    // overall and per error band, one factor each; the calibration for later use per model and
    // level
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- error bands: bands of the observed "
        + "business days at the quartiles")), report.toString());
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- prediction intervals: split-conformal")
        && l.contains("ceil((n + 1) L)")), report.toString());
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- each model's prediction span")
        && l.contains("own_retrospective_with_discussion_forest ")), report.toString());
    assertEquals(2, report.stream().filter(l -> l.startsWith("### Prediction intervals")).count());
    // both retrospective readings are named, the one with the discussion as a ceiling
    assertTrue(report.contains("- own_retrospective: " + ElasticNetSelection.RETROSPECTIVE_ROLE),
        report.toString());
    assertTrue(report.contains("- own_retrospective_with_discussion: "
        + ElasticNetSelection.WITH_DISCUSSION_ROLE), report.toString());
    assertTrue(ElasticNetSelection.WITH_DISCUSSION_ROLE.startsWith("a CEILING, not a competitor"));
    final List<String> intervals =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.INTERVALS));
    assertEquals(1 + 2 * 15 * 2 * 5, intervals.size());
    assertTrue(intervals.stream().anyMatch(l -> l.startsWith("usual_flow\tglobal_median\t0.8\t"
        + "all\t")));
    // the bands are the error bands, labels and all; one factor for every band of a model and level
    final List<String> bandLabels = bands.stream()
        .filter(l -> l.startsWith("usual_flow\tglobal_median\t")).map(l -> l.split("\t")[2])
        .toList();
    assertEquals(4, bandLabels.size());
    final List<String[]> forest95 = intervals.stream()
        .filter(l -> l.startsWith("all\town_with_similarity_before_pressure_forest\t0.95\t"))
        .map(l -> l.split("\t", -1)).toList();
    assertEquals("all", forest95.get(0)[3]);
    assertEquals(bandLabels, forest95.subList(1, 5).stream().map(f -> f[3]).toList());
    assertEquals(1, forest95.stream().map(f -> f[7]).distinct().count());
    assertEquals(Integer.parseInt(forest95.get(0)[8]), forest95.subList(1, 5).stream()
        .mapToInt(f -> Integer.parseInt(f[8])).sum());
    assertEquals(1 + 15 * 2,
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.CALIBRATION)).size());
    // the check reads half B of the validation, a part of it, whole projects
    final int checkedUsual = Integer.parseInt(intervals.stream()
        .filter(l -> l.startsWith("usual_flow\tglobal_median\t0.8\tall\t")).findFirst()
        .orElseThrow().split("\t", -1)[8]);
    assertTrue(0 < checkedUsual && 2 * checkedUsual <= chosen.validationRows(),
        checkedUsual + " of " + chosen.validationRows());
    // nothing of the outer test reaches any file: not one of its issues' keys, bare, anywhere
    final List<java.util.regex.Pattern> outerTest = entries(false).stream()
        .filter(e -> "TEST".equals(e.outer(Population.FIRST_COMMIT)))
        .map(e -> java.util.regex.Pattern.compile(
            "(?<![\\w-])" + java.util.regex.Pattern.quote(e.unit().key()) + "(?!\\w)"))
        .toList();
    assertEquals(4 * 8, outerTest.size());
    // the same search finds an inner validation issue's key where it is written
    final String validationKey = entries(false).stream()
        .filter(e -> "TEST".equals(e.inner(Population.FIRST_COMMIT))).findFirst().orElseThrow()
        .unit().key();
    assertTrue(java.util.regex.Pattern.compile("(?<![\\w-])"
        + java.util.regex.Pattern.quote(validationKey) + "(?!\\w)").matcher(String.join("\n",
        predictions)).find(), validationKey);
    try (var files = Files.list(reports)) {
      for (final Path file : files.toList()) {
        final String text = Files.readString(file);
        assertTrue(outerTest.stream().noneMatch(p -> p.matcher(text).find()), file.toString());
      }
    }
  }

  /** The models and forests the scope of fourteen models leaves out. */
  private static final Set<String> LEFT_OUT = leftOut();

  private static Set<String> leftOut() {
    final Set<String> out = new LinkedHashSet<>();
    for (final String m : List.of("own_without_similarity", "similarity_alone",
        "own_with_similarity", "union_own", "own_retrospective_before_pressure")) {
      out.add(m);
      out.add(m + "_forest");
    }
    out.add("executor_project_median");
    return out;
  }

  /**
   * A file's text with only what the fourteen models had before they were settled: without the
   * lines, or, in the predictions, the columns, of a model they leave out, and without a forest
   * against its own Elastic Net or the own start model against a literature start model, a pair
   * the fourteen do not have or add; hashed with its lines sorted, and, in the predictions, its
   * columns, so the models' order does not count.
   */
  private static String ofTheFourteen(final Path file) throws IOException {
    final List<String> lines = Files.readAllLines(file);
    final List<String> kept = new ArrayList<>();
    if (file.getFileName().toString().equals(IssuesSimilarityInnerReportCommand.PREDICTIONS)) {
      final String[] header = lines.get(0).split("\t", -1);
      for (int i = 0; i < header.length; i++) {
        if (!LEFT_OUT.contains(header[i])) {
          final List<String> column = new ArrayList<>();
          for (final String line : lines) {
            column.add(line.split("\t", -1)[i]);
          }
          kept.add(String.join("\t", column));
        }
      }
    } else {
      for (final String line : lines) {
        final List<String> f = List.of(line.split("\t", -1));
        boolean pair = false;
        for (int i = 0; i + 1 < f.size(); i++) {
          pair |= f.get(i).equals(f.get(i + 1) + "_forest")
              || f.get(i).startsWith("own_with_similarity_before_pressure")
              && (f.get(i + 1).startsWith("alzubaidi_start")
                  || f.get(i + 1).startsWith("kikas_start"));
        }
        if (!pair && f.stream().noneMatch(LEFT_OUT::contains)) {
          kept.add(line);
        }
      }
    }
    java.util.Collections.sort(kept);
    return kept.size() + " " + Integer.toHexString(kept.hashCode());
  }

  /**
   * Every file of the report of {@link #entries} but the report itself, by {@link #ofTheFourteen},
   * as the code of 728207b wrote it on the first-commit population under the revised design, the
   * last code before the In Progress study was removed.
   */
  private static final Map<String, String> FOURTEEN_FINAL = Map.of(
      IssuesSimilarityInnerReportCommand.PREDICTIONS, "21 d47fcc62",
      IssuesSimilarityInnerReportCommand.MODELS, "31 3a398574",
      IssuesSimilarityInnerReportCommand.BANDS, "121 3480174a",
      IssuesSimilarityInnerReportCommand.COMPARISONS, "91 e700342",
      IssuesSimilarityInnerReportCommand.ORDERING, "55 e266f53f",
      IssuesSimilarityInnerReportCommand.DIAGNOSTICS, "223 2480d934",
      IssuesSimilarityInnerReportCommand.IMPORTANCE, "121 5d6788e3",
      IssuesSimilarityInnerReportCommand.INTERVALS, "301 7f247bd5",
      IssuesSimilarityInnerReportCommand.CALIBRATION, "31 f4d177d6");

  @Test
  @DisplayName("every file of the final fourteen-model report but the report itself is bit for bit")
  void fourteenFilesFinal() throws IOException, CommandFailedException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    write(matrix, elasticNet);
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    command().run(context());

    final Map<String, String> now = new HashMap<>();
    for (final String file : FOURTEEN_FINAL.keySet()) {
      now.put(file, ofTheFourteen(reports.resolve(file)));
    }
    assertEquals(FOURTEEN_FINAL, now);
  }

  @Test
  @DisplayName("parameters chosen on another matrix, a fence the selection did not record, or "
      + "outer training rows the split set aside, refuse the report before anything is scored "
      + "or written")
  void refuses() throws IOException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    // a matrix of the temporal split, its parameters made on it: refused on its crossing rows
    write(matrix, elasticNet, true);
    CommandFailedException e =
        assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("8 of 128 outer training rows are neither"),
        e.lines().toString());
    assertFalse(Files.exists(reports));

    final ElasticNetSelection.Result chosen = write(matrix, elasticNet);

    // a fence the selection did not take on this inner fitting set: refused before any fitting
    final List<String> parameters = Files.readAllLines(elasticNet);
    final String fenceLine = "tail-cut\tchosen\tfence_business_days\t" + chosen.cut().fence();
    assertTrue(parameters.contains(fenceLine), parameters.toString());
    final double other = chosen.cut().fence() == 12.5 ? 13.5 : 12.5;
    final List<String> changed = new ArrayList<>(parameters);
    changed.set(parameters.indexOf(fenceLine), "tail-cut\tchosen\tfence_business_days\t" + other);
    Files.write(elasticNet, changed);
    e = assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertEquals(List.of("  REFUSED, nothing was scored or written: the fences on the inner "
        + "fitting set, " + chosen.cut().fence() + " and " + chosen.cut().usualFence()
        + " business days, are not those the selection recorded, " + other + " and "
        + chosen.cut().usualFence()), e.lines());
    assertFalse(Files.exists(reports));
    Files.write(elasticNet, parameters);

    Files.writeString(matrix, "\n", java.nio.file.StandardOpenOption.APPEND);
    e = assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("chosen on another matrix"),
        e.lines().toString());
    assertFalse(Files.exists(reports));

    settings.put("REPORT_DIR", dir.toString());
    settings.put("MATRIX", dir.resolve(IssuesSimilarityInnerReportCommand.REPORT).toString());
    e = assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("is an input"), e.lines().toString());
  }
}
