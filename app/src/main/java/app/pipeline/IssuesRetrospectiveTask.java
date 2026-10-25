package app.pipeline;

import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.ProjectSplit;
import app.issues.Releases;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.World;
import app.issues.port.ChangedMethodSource;
import app.issues.port.StudySource;
import app.labels.ChangedMethod;
import app.pipeline.port.Progress;
import app.study.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The retrospective stage after the similarity stage (ticket 15): the similarity stage's matrix in,
 * the same matrix with the nine {@link Retrospective} columns added out. The earlier columns are
 * copied as they are, so every model before the retrospective one reads the same values. Urgency
 * reads the projects' release dates from the tags of the corpus (ticket 19). The wait on blocking
 * issues is no longer written: no model reads it.
 *
 * <p>The split is checked first against its rule ({@link ProjectSplit#differences}), and a
 * difference refuses the run before the corpus is read; so does an issue of the matrix the corpus
 * does not hold as an In-Progress issue, or one whose first In Progress or first resolution there
 * is not the matrix's. The bots are the accounts {@link DayBoundaries#bots} finds over the
 * comments of the corpus's home, unique issues, as the day boundaries leave out. The methods are
 * read only for the implementation commits of the matrix's issues.
 */
public final class IssuesRetrospectiveTask {

  private final Population population;

  /**
   * One inner side's coverage, on its issues of at least one business day.
   *
   * @param observed per column, in {@link Retrospective#COLUMNS}' order, the rows with a value
   */
  public record Coverage(String side, int rows, Map<String, Integer> observed) {}

  /**
   * @param refused why the matrix's split is not its rule's, or the matrix's issues the
   *     corpus does not hold as they are; when not empty nothing else was done and the other
   *     fields are empty
   * @param entries the matrix's rows with the nine columns, in its order
   * @param commits the implementation commits whose methods were read
   * @param withMethods of those, the commits that changed a recovered method
   * @param bots the raw accounts left out of the discussion
   * @param coverage the inner fitting's and the inner validation's; the outer test is not counted
   */
  public record Report(List<String> refused, List<RowCache.Entry> entries, int commits,
      int withMethods, Set<String> bots, List<Coverage> coverage) {}

  private final StudySource source;
  private final ChangedMethodSource methods;
  private final Progress progress;
  private final Function<List<RowCache.Entry>, List<String>> split;

  public IssuesRetrospectiveTask(final StudySource source, final ChangedMethodSource methods,
      final Progress progress) {
    this(source, methods, progress, Population.IN_PROGRESS);
  }

  /**
   * The same for the matrix of {@code population}. For {@link Population#FIRST_COMMIT} the start
   * t0 is the matrix's, the first commit that counts, and the target is the extraction's ({@link
   * BusinessDays#columns}).
   */
  public IssuesRetrospectiveTask(final StudySource source, final ChangedMethodSource methods,
      final Progress progress, final Population population) {
    this(source, methods, progress, e -> ProjectSplit.differences(e, population), population);
  }

  /**
   * Against another check of the split, for a matrix built in memory.
   *
   * @param split why the matrix's verdicts are not the split's, empty when they are
   */
  IssuesRetrospectiveTask(final StudySource source, final ChangedMethodSource methods,
      final Progress progress, final Function<List<RowCache.Entry>, List<String>> split) {
    this(source, methods, progress, split, Population.IN_PROGRESS);
  }

  /** The same for the matrix of {@code population}. */
  IssuesRetrospectiveTask(final StudySource source, final ChangedMethodSource methods,
      final Progress progress, final Function<List<RowCache.Entry>, List<String>> split,
      final Population population) {
    this.source = source;
    this.methods = methods;
    this.progress = progress;
    this.split = split;
    this.population = population;
  }

  public Report run(final List<RowCache.Entry> entries) {
    final List<String> refused = split.apply(entries);
    if (!refused.isEmpty()) {
      return refused(refused);
    }
    // the coverage reads each row's business days: a row without its boundary or its active
    // count refuses now, before the long read of the methods rather than after it
    final List<String> target = BusinessDays.columns(population);
    final List<String> unbounded = entries.stream()
        .filter(e -> !e.row().values().keySet().containsAll(target))
        .map(e -> e.unit().issueId()).toList();
    if (!unbounded.isEmpty()) {
      final RowCache.Entry first = entries.stream()
          .filter(e -> e.unit().issueId().equals(unbounded.get(0))).findFirst().orElseThrow();
      final List<String> missing = target.stream()
          .filter(c -> !first.row().values().containsKey(c)).toList();
      return refused(List.of(String.format("%,d rows lack %s, the first %s; the matrix was "
          + "written before them", unbounded.size(), missing, unbounded.get(0))));
    }

    progress.phase(1, 4, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();
    final Universe universe = Universe.of(corpus);
    final List<String> unknown = new ArrayList<>();
    final Map<String, Universe.Unit> units = new HashMap<>(entries.size() * 2);
    for (final RowCache.Entry e : entries) {
      final Universe.Unit u = unit(universe, e);
      if (u == null) {
        unknown.add(e.unit().issueId());
      } else {
        units.put(u.id(), u);
      }
    }
    if (!unknown.isEmpty()) {
      return refused(List.of(population == Population.IN_PROGRESS
          ? String.format("%,d issues of the matrix are not In-Progress issues of the corpus "
              + "with the same first In Progress and first resolution, the first %s",
              unknown.size(), unknown.get(0))
          : String.format("%,d issues of the matrix are not issues of the corpus with the same "
              + "creation and first resolution and a start between them, the first %s",
              unknown.size(), unknown.get(0))));
    }

    progress.phase(2, 4, "implementation commits of %,d issues", entries.size());
    final World world = new World(universe, corpus);
    final Set<String> bots = Set.copyOf(DayBoundaries.bots(corpus.comments(),
        new HashSet<>(universe.universe())));
    final Retrospective retrospective = new Retrospective(world, bots, Releases.of(corpus));
    final Set<String> commits = new TreeSet<>();
    for (final Universe.Unit u : units.values()) {
      world.implementation(u.id(), u.resolved()).forEach(c -> commits.add(c.id()));
    }

    progress.phase(3, 4, "changed methods of %,d implementation commits", commits.size());
    final Map<String, Map<String, ChangedMethod>> changed = methods.changed(commits);

    progress.phase(4, 4, "adding %d columns", Retrospective.COLUMNS.size());
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> added = retrospective.row(units.get(e.unit().issueId()), changed);
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      for (final String column : Retrospective.COLUMNS) {
        final Double v = added.get(column);
        if (v == null) {
          absent.add(column);
        } else {
          values.put(column, v);
        }
      }
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent), e.parts()));
    }
    final int withMethods = (int) commits.stream()
        .filter(id -> !changed.getOrDefault(id, Map.of()).isEmpty()).count();
    return new Report(List.of(), List.copyOf(out), commits.size(), withMethods, bots,
        coverage(out, population));
  }

  /**
   * The unit the nine columns are read for, its start the matrix's; null when the corpus does not
   * hold the issue as the matrix has it. For {@link Population#IN_PROGRESS}, the In-Progress unit
   * with the same first In Progress and first resolution. For {@link Population#FIRST_COMMIT}, the
   * issue of the universe with the same creation and first resolution, started at the matrix's
   * first commit, which must lie strictly between them ({@link IssuesSimilarityTask#heldAsIs}).
   */
  private Universe.Unit unit(final Universe universe, final RowCache.Entry e) {
    if (population == Population.IN_PROGRESS) {
      final Universe.Unit u = universe.unit(population, e.unit().issueId());
      return u == null || u.start() != e.unit().startedAt().toEpochMilli()
          || u.resolved() != e.unit().resolvedAt().toEpochMilli() ? null : u;
    }
    if (!IssuesSimilarityTask.heldAsIs(universe, e)) {
      return null;
    }
    final Universe.Unit u = universe.unit(e.unit().issueId());
    return new Universe.Unit(u.id(), u.key(), u.project(), u.reporter(), u.created(),
        u.resolved(), u.reason(), u.moved(), e.unit().startedAt().toEpochMilli(), null);
  }

  /**
   * Per inner side, fitting then validation, on its issues of at least one business day, the rows
   * with each of the nine observed. Only rows of the outer training set are read.
   */
  static List<Coverage> coverage(final List<RowCache.Entry> entries) {
    return coverage(entries, Population.IN_PROGRESS);
  }

  /** The same under the split of {@code split}. */
  static List<Coverage> coverage(final List<RowCache.Entry> entries, final Population split) {
    final BusinessDays.Population population = BusinessDays.population(entries.stream()
        .filter(e -> "TRAIN".equals(e.outer(split))).toList(), Augmentation.Variant.INNER);
    final List<Coverage> out = new ArrayList<>(2);
    for (final String[] side : new String[][] {{"TRAIN", "inner fitting"},
        {"TEST", "inner validation"}}) {
      final List<RowCache.Entry> rows = population.entries().stream()
          .filter(e -> side[0].equals(e.inner(split))).toList();
      final Map<String, Integer> observed = new LinkedHashMap<>();
      for (final String column : Retrospective.COLUMNS) {
        observed.put(column,
            (int) rows.stream().filter(e -> e.row().values().containsKey(column)).count());
      }
      out.add(new Coverage(side[1], rows.size(), observed));
    }
    return List.copyOf(out);
  }

  private static Report refused(final List<String> why) {
    return new Report(why, List.of(), 0, 0, Set.of(), List.of());
  }
}
