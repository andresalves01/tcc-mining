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
import app.issues.IssueUnit;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
import app.issues.TailCut;
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
    return new Context(null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, Progress.silent(), null, (run, rows) -> {
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
   * Four projects; per project 20 inner fitting, 10 inner validation and 8 outer test rows, every
   * one of at least one business day, with every column any model declares. With {@code
   * crossing}, two of the outer test rows of each project are instead outer training rows the
   * inner cut set aside, as a matrix of the temporal split held them.
   */
  private static List<RowCache.Entry> entries(final boolean crossing) {
    // the columns of the nine models before ticket 26 draw from the shared generator as they
    // did; any other model's from a draw of its own per row and column, so none of theirs moves
    final Set<Attribute> declared = new LinkedHashSet<>();
    final Set<Attribute> later = new LinkedHashSet<>();
    for (final Augmentation.Variant v : List.of(Augmentation.Variant.INNER,
        Augmentation.Variant.OUTER)) {
      for (final Cell cell : ElasticNetSelection.everyCell(v)) {
        (cell.label().equals(KIKAS_RETROSPECTIVE) ? later : declared).addAll(cell.attributes());
      }
    }
    later.removeAll(declared);
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
            // the registered matrix stores the missing indicator beside its own columns
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
        values.put(BusinessDays.BOUNDARY, 4.0);
        // never sent back to Open or Reopened: active, the whole interval's business days
        values.put(BusinessDays.ACTIVE, (double) days);
        // no silent day: the target is the active count in both variants
        for (final Augmentation.Variant v : Augmentation.Variant.values()) {
          values.put(BusinessDays.recorded(v), (double) days);
          values.put(BusinessDays.threshold(v), BusinessDays.LEAST_SILENT);
        }
        final String id = "i" + n++;
        final Instant start = created.plusSeconds(3600L * 7 * n);
        final Map<Population, String[]> parts = new EnumMap<>(Population.class);
        parts.put(Population.IN_PROGRESS, part);
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
    RowCache.write(matrix, entries, columns, List.of(Population.IN_PROGRESS));
    final List<String> lines = new ArrayList<>(Files.readAllLines(matrix));
    final int outer = List.of(lines.get(0).split("\t")).indexOf("andamento_externa");
    for (int k = 1; k < lines.size(); k++) {
      if (!"TRAIN".equals(lines.get(k).split("\t", -1)[outer])) {
        lines.set(k, lines.get(k).replace("2016-", "never-").replace("\t4.0", "\tfour\tx"));
      }
    }
    Files.write(matrix, lines);
    final ElasticNetSelection.Result chosen = ElasticNetSelection.run(BusinessDays.population(
        RowCache.read(matrix, Population.IN_PROGRESS, "TRAIN"), Augmentation.Variant.INNER)
        .entries(), 1);
    Files.write(elasticNet,
        ElasticNetSelection.parameters(chosen, IssuesReservedCommand.sha256(matrix)));
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
    assertEquals(23, models.size());
    assertEquals(models.keySet(), all.keySet());
    assertEquals(1 + 2 * 23, modelLines.size());
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
        "has_implementation_commit"), List.of(predictions.get(0).split("\t")).subList(0, 6));
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
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- readings: main, usual flow")
        && l.contains(": " + chosen.validationRows() + " inner validation issues")
        && l.endsWith(": " + validation)), report.toString());
    assertTrue(report.contains("## Main reading: the usual flow (" + chosen.validationRows()
        + " issues)"), report.toString());
    assertTrue(report.contains("## Complementary reading: all issues (" + validation
        + " issues)"), report.toString());
    // what the ninth model is, stated here and not in the test's record (ticket 22)
    assertTrue(report.contains("- own_retrospective_before_pressure: "
        + ElasticNetSelection.RETROSPECTIVE_BEFORE_PRESSURE_ROLE), report.toString());
    assertTrue(report.contains("- kikas_retrospective: "
        + ElasticNetSelection.KIKAS_RETROSPECTIVE_ROLE), report.toString());
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.BANDS,
        IssuesSimilarityInnerReportCommand.COMPARISONS, IssuesSimilarityInnerReportCommand.ORDERING,
        IssuesSimilarityInnerReportCommand.DIAGNOSTICS,
        IssuesSimilarityInnerReportCommand.IMPORTANCE)) {
      assertTrue(Files.readAllLines(reports.resolve(file)).size() > 1, file);
    }
    final List<String> comparisons =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.COMPARISONS));
    for (final String reading : List.of("usual_flow\t", "all\t")) {
      assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading
          + "without implementation commit\t")), reading);
      assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading
          + "with implementation commit\t")), reading);
    }
    assertTrue(comparisons.stream().anyMatch(l -> l.contains("\town_retrospective_forest\t"
        + "project_median\t")));
    // the new start model against the one before ticket 19, read from the same run (item 29 i)
    assertTrue(comparisons.stream().anyMatch(l -> l.contains("\town_with_similarity\t"
        + "own_with_similarity_before_pressure\t")));
    assertTrue(comparisons.stream().anyMatch(l -> l.contains("\town_with_similarity_forest\t"
        + "own_with_similarity_before_pressure_forest\t")));
    // the retrospective model against the one before ticket 19, Elastic Net and forest, and
    // the ninth against the two medians (ticket 20)
    for (final String pair : List.of("\town_retrospective\town_retrospective_before_pressure\t",
        "\town_retrospective_forest\town_retrospective_before_pressure_forest\t",
        "\town_retrospective_before_pressure\tglobal_median\t",
        "\town_retrospective_before_pressure\tproject_median\t",
        "\town_retrospective_before_pressure_forest\tglobal_median\t",
        "\town_retrospective_before_pressure_forest\tproject_median\t",
        // the retrospective model against Kikas's, and Kikas's against the medians (ticket 26)
        "\town_retrospective\tkikas_retrospective\t",
        "\town_retrospective_forest\tkikas_retrospective_forest\t",
        "\tkikas_retrospective\tglobal_median\t",
        "\tkikas_retrospective_forest\tproject_median\t")) {
      for (final String reading : List.of("usual_flow\t", "all\t")) {
        assertTrue(comparisons.stream().anyMatch(l -> l.startsWith(reading + "all scored\t")
            && l.contains(pair)), reading + pair);
      }
    }
    final List<String> ordering =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.ORDERING));
    assertTrue(ordering.stream().noneMatch(l -> l.contains("\tglobal_median\t")));
    for (final String pair : List.of("own_retrospective\town_retrospective_before_pressure\t",
        "own_retrospective_forest\town_retrospective_before_pressure_forest\t",
        "own_retrospective_before_pressure\tproject_median\t",
        "own_retrospective_before_pressure_forest\tproject_median\t",
        "own_retrospective\tkikas_retrospective\t",
        "own_retrospective_forest\tkikas_retrospective_forest\t",
        "kikas_retrospective\tproject_median\t")) {
      assertTrue(ordering.stream().anyMatch(l -> l.startsWith("usual_flow\t" + pair)), pair);
      assertTrue(ordering.stream().anyMatch(l -> l.startsWith("all\t" + pair)), pair);
    }
    final List<String> bands =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.BANDS));
    assertTrue(bands.stream()
        .anyMatch(l -> l.startsWith("usual_flow\town_retrospective_before_pressure\t")));
    assertTrue(bands.stream()
        .anyMatch(l -> l.startsWith("all\town_retrospective_before_pressure_forest\t")));
    assertTrue(Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.IMPORTANCE))
        .stream().anyMatch(l -> l.startsWith("own_retrospective_before_pressure_forest\t")));

    // ticket 24: the bands at the fitting quartiles, the intervals per reading, model and level,
    // overall and per error band, one factor each; the calibration for later use per model and
    // level
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- error bands: bands of the observed "
        + "business days at the quartiles")), report.toString());
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- prediction intervals: split-conformal")
        && l.contains("ceil((n + 1) L)")), report.toString());
    assertTrue(report.stream().anyMatch(l -> l.startsWith("- each model's prediction span")
        && l.contains("own_retrospective_before_pressure_forest ")), report.toString());
    assertEquals(2, report.stream().filter(l -> l.startsWith("### Prediction intervals")).count());
    final List<String> intervals =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.INTERVALS));
    assertEquals(1 + 2 * 23 * 2 * 5, intervals.size());
    assertTrue(intervals.stream().anyMatch(l -> l.startsWith("usual_flow\tglobal_median\t0.8\t"
        + "all\t")));
    // the bands are the error bands, labels and all; one factor for every band of a model and level
    final List<String> bandLabels = bands.stream()
        .filter(l -> l.startsWith("usual_flow\tglobal_median\t")).map(l -> l.split("\t")[2])
        .toList();
    assertEquals(4, bandLabels.size());
    final List<String[]> forest95 = intervals.stream()
        .filter(l -> l.startsWith("all\town_with_similarity_forest\t0.95\t"))
        .map(l -> l.split("\t", -1)).toList();
    assertEquals("all", forest95.get(0)[3]);
    assertEquals(bandLabels, forest95.subList(1, 5).stream().map(f -> f[3]).toList());
    assertEquals(1, forest95.stream().map(f -> f[7]).distinct().count());
    assertEquals(Integer.parseInt(forest95.get(0)[8]), forest95.subList(1, 5).stream()
        .mapToInt(f -> Integer.parseInt(f[8])).sum());
    assertEquals(1 + 23 * 2,
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.CALIBRATION)).size());
    // the check reads half B of the validation, a part of it, whole projects
    final int checkedUsual = Integer.parseInt(intervals.stream()
        .filter(l -> l.startsWith("usual_flow\tglobal_median\t0.8\tall\t")).findFirst()
        .orElseThrow().split("\t", -1)[8]);
    assertTrue(0 < checkedUsual && 2 * checkedUsual <= chosen.validationRows(),
        checkedUsual + " of " + chosen.validationRows());
    // nothing of the outer test reaches any file: not one of its issues' keys, bare, anywhere
    final List<java.util.regex.Pattern> outerTest = entries(false).stream()
        .filter(e -> "TEST".equals(e.outer(Population.IN_PROGRESS)))
        .map(e -> java.util.regex.Pattern.compile(
            "(?<![\\w-])" + java.util.regex.Pattern.quote(e.unit().key()) + "(?!\\w)"))
        .toList();
    assertEquals(4 * 8, outerTest.size());
    // the same search finds an inner validation issue's key where it is written
    final String validationKey = entries(false).stream()
        .filter(e -> "TEST".equals(e.inner(Population.IN_PROGRESS))).findFirst().orElseThrow()
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

  /**
   * Every file of the report of {@link #entries} but the report itself, which names the temporary
   * paths, as the code wrote it before ticket 26, by a hash of its text without the Kikas
   * retrospective model's lines, or, in the predictions, its columns. Captured from 6d7edf1 (main
   * after ticket 24).
   */
  private static final Map<String, String> AT_TICKET_24 = Map.of(
      IssuesSimilarityInnerReportCommand.PREDICTIONS, "41 623fa349",
      IssuesSimilarityInnerReportCommand.MODELS, "43 625c1a12",
      IssuesSimilarityInnerReportCommand.BANDS, "169 4463f337",
      IssuesSimilarityInnerReportCommand.COMPARISONS, "121 c43730c8",
      IssuesSimilarityInnerReportCommand.ORDERING, "73 8d562431",
      IssuesSimilarityInnerReportCommand.DIAGNOSTICS, "368 d222d4c4",
      IssuesSimilarityInnerReportCommand.IMPORTANCE, "172 f1508a21",
      IssuesSimilarityInnerReportCommand.INTERVALS, "421 6f8421c1",
      IssuesSimilarityInnerReportCommand.CALIBRATION, "43 34d3dd5");

  /**
   * Every file of the report of {@link #entries} but the report itself, as the code wrote it
   * before the blocking wait, the comments and the participants left the main retrospective
   * model, by a hash of its text without the lines, or, in the predictions, the columns, that
   * name an own retrospective model. Captured from ef6d555 (ticket 27 with the bot filter).
   */
  private static final Map<String, String> START_AT_TICKET_27 = Map.of(
      IssuesSimilarityInnerReportCommand.PREDICTIONS, "41 e64817c8",
      IssuesSimilarityInnerReportCommand.MODELS, "39 d6d4ad40",
      IssuesSimilarityInnerReportCommand.BANDS, "153 9e8901ef",
      IssuesSimilarityInnerReportCommand.COMPARISONS, "95 cccb79ab",
      IssuesSimilarityInnerReportCommand.ORDERING, "59 e61a1f8f",
      IssuesSimilarityInnerReportCommand.DIAGNOSTICS, "295 b8611bc8",
      IssuesSimilarityInnerReportCommand.IMPORTANCE, "139 98eb3bc2",
      IssuesSimilarityInnerReportCommand.INTERVALS, "381 48d216d6",
      IssuesSimilarityInnerReportCommand.CALIBRATION, "39 47ffd963");

  /** A file's text without the Kikas retrospective model's lines or columns, hashed. */
  private static String withoutKikasRetrospective(final Path file) throws IOException {
    return without(file, KIKAS_RETROSPECTIVE);
  }

  /** A file's text without the lines or, in the predictions, the columns naming {@code name}. */
  private static String without(final Path file, final String name) throws IOException {
    final List<String> lines = Files.readAllLines(file);
    final List<String> kept = new ArrayList<>();
    if (file.getFileName().toString().equals(IssuesSimilarityInnerReportCommand.PREDICTIONS)) {
      final String[] header = lines.get(0).split("\t", -1);
      for (final String line : lines) {
        final String[] f = line.split("\t", -1);
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < f.length; i++) {
          if (!header[i].contains(name)) {
            out.add(f[i]);
          }
        }
        kept.add(String.join("\t", out));
      }
    } else {
      lines.stream().filter(l -> !l.contains(name)).forEach(kept::add);
    }
    return kept.size() + " " + Integer.toHexString(kept.hashCode());
  }

  @Test
  @DisplayName("every file of the report but the report itself is, bit for bit, what it was at "
      + "ticket 24, the Kikas retrospective model's lines and columns aside")
  void filesAsAtTicket24() throws IOException, CommandFailedException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    write(matrix, elasticNet);
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    command().run(context());

    final Map<String, String> now = new HashMap<>();
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.PREDICTIONS,
        IssuesSimilarityInnerReportCommand.MODELS, IssuesSimilarityInnerReportCommand.BANDS,
        IssuesSimilarityInnerReportCommand.COMPARISONS,
        IssuesSimilarityInnerReportCommand.ORDERING,
        IssuesSimilarityInnerReportCommand.DIAGNOSTICS,
        IssuesSimilarityInnerReportCommand.IMPORTANCE,
        IssuesSimilarityInnerReportCommand.INTERVALS,
        IssuesSimilarityInnerReportCommand.CALIBRATION)) {
      now.put(file, withoutKikasRetrospective(reports.resolve(file)));
    }
    assertEquals(AT_TICKET_24, now);
  }

  @Test
  @DisplayName("every file of the report but the report itself is, bit for bit, what it was at "
      + "ticket 27, the own retrospective models' lines and columns aside")
  void filesAsAtTicket27() throws IOException, CommandFailedException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    write(matrix, elasticNet);
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    command().run(context());

    final Map<String, String> now = new HashMap<>();
    for (final String file : START_AT_TICKET_27.keySet()) {
      now.put(file, without(reports.resolve(file), "own_retrospective"));
    }
    assertEquals(START_AT_TICKET_27, now);
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

    // the same matrix as written before ticket 18, without the active business days, its
    // parameters made on it: refused, the stage to run again named
    final Path old = dir.resolve("old.tsv");
    write(old, elasticNet);
    final List<String> lines = Files.readAllLines(old);
    final int active = List.of(lines.get(0).split("\t", -1)).indexOf(BusinessDays.ACTIVE);
    final List<String> without = new ArrayList<>();
    for (final String line : lines) {
      final List<String> fields = new ArrayList<>(List.of(line.split("\t", -1)));
      if (fields.size() > active) {
        fields.remove(active);
      }
      without.add(String.join("\t", fields));
    }
    final String before = IssuesReservedCommand.sha256(old);
    Files.write(old, without);
    Files.writeString(elasticNet, Files.readString(elasticNet)
        .replace(before, IssuesReservedCommand.sha256(old)));
    set(old, elasticNet, reports);
    e = assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains(BusinessDays.ACTIVE)
        && String.join("\n", e.lines()).contains("issues-in-progress-similarity"),
        e.lines().toString());
    assertFalse(Files.exists(reports));

    settings.put("REPORT_DIR", dir.toString());
    settings.put("MATRIX", dir.resolve(IssuesSimilarityInnerReportCommand.REPORT).toString());
    e = assertThrows(CommandFailedException.class, () -> command().run(context()));
    assertTrue(String.join("\n", e.lines()).contains("is an input"), e.lines().toString());
  }
}
