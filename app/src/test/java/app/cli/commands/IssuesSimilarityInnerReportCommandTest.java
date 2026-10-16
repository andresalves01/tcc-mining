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
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.SimilarityScoring;
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
   * Four projects; per project 20 inner fitting, 10 inner validation, 6 outer test and 2 crossing
   * rows, every one of at least one business day, with every column any model declares.
   */
  private static List<RowCache.Entry> entries() {
    final Set<Attribute> declared = new LinkedHashSet<>();
    for (final Cell cell : ElasticNetSelection.everyCell(Augmentation.Variant.INNER)) {
      declared.addAll(cell.attributes());
    }
    for (final Cell cell : ElasticNetSelection.everyCell(Augmentation.Variant.OUTER)) {
      declared.addAll(cell.attributes());
    }
    final Random random = new Random(3);
    final Instant created = Instant.parse("2016-02-01T00:00:00Z");
    final List<RowCache.Entry> out = new ArrayList<>();
    int n = 0;
    for (int p = 0; p < 4; p++) {
      for (int k = 0; k < 38; k++) {
        final String[] part = k < 20 ? new String[] {"TRAIN", "TRAIN"}
            : k < 30 ? new String[] {"TRAIN", "TEST"}
            : k < 36 ? new String[] {"TEST", ""} : new String[] {"CROSSES", ""};
        final int days = 1 + (int) Math.floor(Math.exp(3 * random.nextDouble()));
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
        values.put(BusinessDays.BOUNDARY, 4.0);
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
   * The matrix on disk, its outer test and crossing rows corrupted after their split field -- a
   * value, a date and a field count no read could parse -- and the selection's choices on its
   * outer training rows, made on that very file.
   */
  private ElasticNetSelection.Result write(final Path matrix, final Path elasticNet)
      throws IOException {
    final List<RowCache.Entry> entries = entries();
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
        RowCache.read(matrix, Population.IN_PROGRESS, "TRAIN")).entries(), 1);
    Files.write(elasticNet,
        ElasticNetSelection.parameters(chosen, IssuesReservedCommand.sha256(matrix)));
    return chosen;
  }

  private IssuesSimilarityInnerReportCommand command() {
    return new IssuesSimilarityInnerReportCommand(settings::get,
        new RegistrationInProgress.Sizes(4 * 30, 999, 4 * 20, 4 * 10));
  }

  private void set(final Path matrix, final Path elasticNet, final Path reports) {
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", elasticNet.toString());
    settings.put("REPORT_DIR", reports.toString());
  }

  private static Map<String, String[]> byModel(final List<String> tsv) {
    final Map<String, String[]> out = new LinkedHashMap<>();
    tsv.subList(1, tsv.size()).forEach(l -> out.put(l.split("\t")[0], l.split("\t", -1)));
    return out;
  }

  @Test
  @DisplayName("on the outer training rows alone, the outer test never parsed and nothing "
      + "recorded, every model's error is, to the bit, the selection's inner validation error")
  void reportsTheSelectionsErrors() throws IOException, CommandFailedException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    final ElasticNetSelection.Result chosen = write(matrix, elasticNet);
    assertThrows(RuntimeException.class, () -> RowCache.read(matrix), "the corruption parses");
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    command().run(context());

    final Map<String, String[]> models =
        byModel(Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.MODELS)));
    assertEquals(17, models.size());
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
    assertEquals(List.of("id", "key", "project", "observed_business_days",
        "has_implementation_commit"), List.of(predictions.get(0).split("\t")).subList(0, 5));
    assertEquals(1 + chosen.validationRows(), predictions.size());
    final List<String> report =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.REPORT));
    assertTrue(report.get(2).contains("optimistic") && report.get(2).contains("same inner "
        + "validation"), report.get(2));
    for (final String file : List.of(IssuesSimilarityInnerReportCommand.BANDS,
        IssuesSimilarityInnerReportCommand.COMPARISONS, IssuesSimilarityInnerReportCommand.ORDERING,
        IssuesSimilarityInnerReportCommand.DIAGNOSTICS,
        IssuesSimilarityInnerReportCommand.IMPORTANCE)) {
      assertTrue(Files.readAllLines(reports.resolve(file)).size() > 1, file);
    }
    final List<String> comparisons =
        Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.COMPARISONS));
    assertTrue(comparisons.stream().anyMatch(l -> l.startsWith("without implementation commit\t")));
    assertTrue(comparisons.stream().anyMatch(l -> l.startsWith("with implementation commit\t")));
    assertTrue(comparisons.stream().anyMatch(l -> l.contains("\town_retrospective_forest\t"
        + "project_median\t")));
    assertTrue(Files.readAllLines(reports.resolve(IssuesSimilarityInnerReportCommand.ORDERING))
        .stream().noneMatch(l -> l.contains("\tglobal_median\t")));
  }

  @Test
  @DisplayName("parameters chosen on another matrix, or split sizes not the registered study's, "
      + "refuse the report before anything is scored or written")
  void refuses() throws IOException {
    final Path matrix = dir.resolve("matrix.tsv");
    final Path elasticNet = dir.resolve("elastic-net.tsv");
    write(matrix, elasticNet);
    final Path reports = dir.resolve("out");
    set(matrix, elasticNet, reports);

    final IssuesSimilarityInnerReportCommand sizes = new IssuesSimilarityInnerReportCommand(
        settings::get, RegistrationInProgress.SIZES);
    CommandFailedException e =
        assertThrows(CommandFailedException.class, () -> sizes.run(context()));
    assertTrue(String.join("\n", e.lines()).contains("registered study's"), e.lines().toString());
    assertFalse(Files.exists(reports));

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
