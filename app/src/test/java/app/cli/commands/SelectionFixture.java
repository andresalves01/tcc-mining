package app.cli.commands;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.ElasticNetSelection;
import app.issues.FirstCommit;
import app.issues.IssueUnit;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.issues.model.Cell;
import app.study.Construct;
import app.study.Row;
import java.io.IOException;
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

/**
 * A synthetic matrix the selection, the inner report and the test can all run on end to end: four
 * projects of 38 issues, the split drawn by its rule ({@link ProjectSplit#draw}) so the stages
 * that check it pass, every column any model declares in both variants, and the target columns of
 * one population -- the day boundary and the active business days of {@link
 * Population#IN_PROGRESS}, or the target, its day boundary and the first commit's size and share
 * of {@link Population#FIRST_COMMIT}. Every ninth issue from the fifth of each project lasts 60
 * business days more, a tail.
 */
final class SelectionFixture {

  private SelectionFixture() {}

  private static final Instant CREATED = Instant.parse("2016-02-01T00:00:00Z");

  /** The issue {@code days} business days after {@code start}, at the boundary 04:00 UTC. */
  static Instant after(final Instant start, final int days) {
    Instant end = start;
    while (BusinessDays.count(start.toEpochMilli(), end.toEpochMilli(), 4) < days) {
      end = end.plus(Duration.ofDays(1));
    }
    return end;
  }

  /** The rows of {@code population}'s matrix, in their order. */
  static List<RowCache.Entry> entries(final Population population) {
    final Set<Attribute> declared = new LinkedHashSet<>();
    for (final Augmentation.Variant v : List.of(Augmentation.Variant.INNER,
        Augmentation.Variant.OUTER)) {
      for (final Cell cell : ElasticNetSelection.everyCell(v, population)) {
        declared.addAll(cell.attributes());
      }
    }
    final Map<String, String> projects = new LinkedHashMap<>();
    int n = 0;
    for (int p = 0; p < 4; p++) {
      for (int k = 0; k < 38; k++) {
        projects.put("i" + n++, "p" + p);
      }
    }
    final ProjectSplit.Parts parts = ProjectSplit.draw(projects);
    final Random random = new Random(3);
    final List<RowCache.Entry> out = new ArrayList<>();
    n = 0;
    for (final Map.Entry<String, String> issue : projects.entrySet()) {
      final String id = issue.getKey();
      final int k = n % 38;
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
        final double value = similarity ? days * (0.6 + 0.8 * random.nextDouble())
            : a.name().equals(FirstCommit.FIRST_SHARE) ? 0.49 * random.nextDouble()
            : a.name().equals(FirstCommit.FIRST_LINES) ? 1 + random.nextInt(400) + 3 * days
            : a.kind() == Construct.Kind.CATEGORICAL ? random.nextInt(3)
            : random.nextInt(40);
        values.put(a.name(), value);
        if (Attributes.all().contains(a) && a.absenceIndicator()) {
          // the extracted matrix stores the missing indicator beside its own columns
          values.put(a.absenceName(), 0.0);
        }
      }
      if (population == Population.FIRST_COMMIT) {
        values.put(FirstCommit.TARGET_COLUMN, (double) days);
        values.put(FirstCommit.BOUNDARY_COLUMN, 4.0);
        values.put("FC_marks", 2.0 + n % 4);
        values.put("FC_patches", (double) (n % 3 == 0 ? 1 : 0));
      } else {
        values.put(BusinessDays.BOUNDARY, 4.0);
        // never sent back to Open or Reopened: active, the whole interval's business days
        values.put(BusinessDays.ACTIVE, (double) days);
      }
      final Instant start = CREATED.plusSeconds(3600L * 7 * (n + 1));
      final Part inner = parts.inner().get(id);
      final Map<Population, String[]> verdicts = new EnumMap<>(Population.class);
      verdicts.put(population, new String[] {parts.outer().get(id).name(),
          inner == null ? "" : inner.name()});
      out.add(new RowCache.Entry(new IssueUnit(id, "K-" + id, issue.getValue(), "r",
          "e" + (k % 3), CREATED, after(start, days), "Fixed", false, start),
          new Row(id, values, Set.of()), verdicts));
      n++;
    }
    return out;
  }

  /** Writes {@code population}'s matrix to {@code path}, its columns sorted by name. */
  static Path write(final Path path, final Population population) throws IOException {
    return write(path, entries(population), population);
  }

  /**
   * The first-commit matrix at the temporal split's sides: the same rows, the issues of the four
   * projects started in turn, one project after the other, so every project is on both sides;
   * each resolution its target's business days after its start.
   */
  static Path temporal(final Path path) throws IOException {
    return temporal(path, 0);
  }

  /**
   * {@link #temporal(Path)} with the first {@code newIssues} inner validation issues, in the
   * order of their start, moved to a fifth project, {@code p4}, which then has issues on the
   * inner validation side alone: a new project there. The temporal rule reads no project, so the
   * sides are the same.
   */
  static Path temporal(final Path path, final int newIssues) throws IOException {
    final Population population = Population.FIRST_COMMIT;
    final List<RowCache.Entry> moved = new ArrayList<>();
    final List<SplitRule.Candidate> candidates = new ArrayList<>();
    int n = 0;
    for (final RowCache.Entry e : entries(population)) {
      final IssueUnit u = e.unit();
      final Instant start = CREATED.plusSeconds(3600L * 7 * ((n % 38) * 4 + n / 38 + 1));
      final int days = e.row().values().get(FirstCommit.TARGET_COLUMN).intValue();
      moved.add(new RowCache.Entry(new IssueUnit(u.issueId(), u.key(), u.project(), u.reporter(),
          u.executor(), u.createdAt(), after(start, days), u.reason(), u.moved(), start),
          e.row(), e.parts()));
      candidates.add(new SplitRule.Candidate(u.issueId(), u.project(), start));
      n++;
    }
    final ProjectSplit.Parts parts = SplitRule.TEMPORAL.draw(candidates);
    final List<String> renamed = moved.stream()
        .filter(e -> parts.inner().get(e.unit().issueId()) == Part.TEST)
        .sorted(java.util.Comparator.comparing((RowCache.Entry e) -> e.unit().startedAt()))
        .limit(newIssues).map(e -> e.unit().issueId()).toList();
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final RowCache.Entry e : moved) {
      final String id = e.unit().issueId();
      final Part inner = parts.inner().get(id);
      final Map<Population, String[]> verdicts = new EnumMap<>(Population.class);
      verdicts.put(population, new String[] {parts.outer().get(id).name(),
          inner == null ? "" : inner.name()});
      final IssueUnit u = e.unit();
      final IssueUnit unit = !renamed.contains(id) ? u : new IssueUnit(u.issueId(), u.key(),
          "p4", u.reporter(), u.executor(), u.createdAt(), u.resolvedAt(), u.reason(), u.moved(),
          u.startedAt());
      out.add(new RowCache.Entry(unit, e.row(), verdicts));
    }
    return write(path, out, population);
  }

  private static Path write(final Path path, final List<RowCache.Entry> entries,
      final Population population) throws IOException {
    final List<String> columns = new ArrayList<>(new LinkedHashSet<>(entries.stream()
        .flatMap(e -> e.row().values().keySet().stream()).sorted().toList()));
    RowCache.write(path, entries, columns, List.of(population));
    return path;
  }
}
