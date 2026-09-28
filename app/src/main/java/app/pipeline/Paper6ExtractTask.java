package app.pipeline;

import app.labels.SplitPart;
import app.paper5.Row;
import app.paper6.Attribute;
import app.paper6.Attributes;
import app.paper6.Extractor;
import app.paper6.IssueUnit;
import app.paper6.Population;
import app.paper6.Replay;
import app.paper6.RowCache;
import app.paper6.Tables;
import app.paper6.TemporalClass;
import app.paper6.port.IssueTableSource;
import app.paper6.port.IssueUnitSource;
import app.pipeline.port.Progress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Builds every construct for the whole population, and checks the three things the results rest on
 * before any model sees a row.
 *
 * <ul>
 *   <li><b>the changelog</b>: per field, how often its last recorded change lands on the stored
 *       value, and how often the value at creation differs from the final one;
 *   <li><b>the author's creation columns</b>: how often the stored final value differs from the
 *       value at creation that this study uses instead;
 *   <li><b>time travel</b>: for a seeded sample of issues, the corpus is truncated at each one's
 *       creation and every construct the two ex ante profiles admit is recomputed. Any difference is
 *       a construct reading the future, and the count must be zero.
 * </ul>
 */
public final class Paper6ExtractTask {

  public static final long SEED = 20260927L;

  /** How many issues the time-travel check truncates the corpus for. Each costs a full copy. */
  public static final int TIME_TRAVEL_SAMPLE = 60;

  public record Coverage(String column, int withValue, int absent, int zero, int absentTrain,
      int absentTest) {}

  /** One creation column of the author's specification: how often final and creation differ. */
  public record Deviation(String column, int compared, int differ) {}

  public record Report(Population.Report population, List<RowCache.Entry> entries,
      List<Coverage> coverage, List<Replay.Fidelity> fidelity, List<Deviation> deviations,
      int timeTravelIssues, int timeTravelChecks, List<String> timeTravelMismatches,
      int withoutOwnColumns) {}

  private final IssueUnitSource units;
  private final IssueTableSource tables;
  private final Progress progress;

  public Paper6ExtractTask(final IssueUnitSource units, final IssueTableSource tables,
      final Progress progress) {
    this.units = units;
    this.tables = tables;
    this.progress = progress;
  }

  public Report run(final double trainShare) {
    progress.detail("lendo %s", units.describe());
    final Population.Report population = Population.of(units.readAll(), trainShare);
    final List<IssueUnit> usable = new ArrayList<>(population.train());
    usable.addAll(population.test());
    final List<String> ids = usable.stream().map(IssueUnit::issueId).toList();

    final Tables corpus = tables.load(ids);
    final Map<String, Map<String, Double>> own = units.ownColumns(ids);

    final List<RowCache.Entry> entries = new ArrayList<>(usable.size());
    final Replay.Audit audit = new Replay.Audit();
    int withoutOwn = 0;
    int done = 0;
    for (final IssueUnit unit : usable) {
      if (++done % 5_000 == 0) {
        progress.item(done, usable.size(), "extraindo", unit.project());
      }
      final Map<String, Double> ownValues = own.get(unit.issueId());
      if (ownValues == null) {
        withoutOwn++;
      }
      final Row row = Extractor.of(unit, corpus, ownValues);
      audit.add(corpus.snapshotOf(unit.issueId()), corpus.changesOf(unit.issueId()),
          unit.createdAt().toEpochMilli());
      final SplitPart outer = population.outerParts().get(unit.issueId());
      final SplitPart inner = population.innerParts().get(unit.issueId());
      entries.add(new RowCache.Entry(unit, row, outer.name(), inner == null ? "" : inner.name()));
    }

    final List<Deviation> deviations = List.of(
        deviation(entries, own, "description_length_at_open", "extra_desc_length"),
        deviation(entries, own, "linked_issues_at_open", "extra_linked_issues"),
        deviation(entries, own, "issue_type_at_open", "extra_issue_type"));

    // -- viagem no tempo --------------------------------------------------------------------------
    final List<String> exAnte = Attributes.all().stream()
        .filter(a -> a.temporalClass() == TemporalClass.LIMPO
            || a.temporalClass() == TemporalClass.RECONSTRUIDO_DO_CHANGELOG)
        .filter(a -> !a.id().startsWith("P"))
        .map(Attribute::name).toList();
    final Random random = new Random(SEED);
    final List<String> mismatches = new ArrayList<>();
    int checks = 0;
    for (int s = 0; s < TIME_TRAVEL_SAMPLE; s++) {
      final IssueUnit unit = usable.get(random.nextInt(usable.size()));
      progress.item(s + 1, TIME_TRAVEL_SAMPLE, "viagem no tempo", unit.key());
      final Tables past = corpus.truncatedAt(unit.createdAt().toEpochMilli());
      final Row full = Extractor.of(unit, corpus, own.get(unit.issueId()));
      final Row cut = Extractor.of(unit, past, own.get(unit.issueId()));
      for (final String name : exAnte) {
        checks++;
        if (!Objects.equals(full.values().get(name), cut.values().get(name))
            || full.isAbsent(name) != cut.isAbsent(name)) {
          mismatches.add(unit.key() + " " + name + ": " + full.values().get(name) + " -> "
              + cut.values().get(name));
        }
      }
    }

    return new Report(population, entries, coverage(entries), audit.result(), deviations,
        TIME_TRAVEL_SAMPLE, checks, mismatches, withoutOwn);
  }

  private static Deviation deviation(final List<RowCache.Entry> entries,
      final Map<String, Map<String, Double>> own, final String replayed, final String stored) {

    int compared = 0;
    int differ = 0;
    for (final RowCache.Entry entry : entries) {
      final Map<String, Double> values = own.get(entry.unit().issueId());
      final Double atOpen = entry.row().values().get(replayed);
      final Double atEnd = values == null ? null : values.get(stored);
      if (atOpen == null || atEnd == null) {
        continue;
      }
      compared++;
      if (!atOpen.equals(atEnd)) {
        differ++;
      }
    }
    return new Deviation(replayed + " vs " + stored, compared, differ);
  }

  private static List<Coverage> coverage(final List<RowCache.Entry> entries) {
    final Map<String, int[]> counts = new LinkedHashMap<>();
    for (final Attribute attribute : Attributes.all()) {
      counts.put(attribute.name(), new int[5]);
    }
    for (final RowCache.Entry entry : entries) {
      final boolean train = "TRAIN".equals(entry.outer());
      counts.forEach((name, c) -> {
        final Double value = entry.row().values().get(name);
        if (value == null) {
          c[1]++;
          c[train ? 3 : 4]++;
        } else {
          c[0]++;
          if (value == 0.0) {
            c[2]++;
          }
        }
      });
    }
    final List<Coverage> out = new ArrayList<>();
    counts.forEach((name, c) -> out.add(new Coverage(name, c[0], c[1], c[2], c[3], c[4])));
    return List.copyOf(out);
  }
}
