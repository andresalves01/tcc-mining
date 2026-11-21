package app.pipeline;

import app.issues.Augmentation;
import app.issues.Augmentation.Variant;
import app.issues.BusinessDays;
import app.issues.Clusterer;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.FirstCommit;
import app.issues.FixVersions;
import app.issues.Embedder;
import app.issues.Initial;
import app.issues.ProjectSplit;
import app.issues.RowCache;
import app.issues.SplitRule;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.Verdict;
import app.issues.port.StudySource;
import app.pipeline.embedding.EmbeddingModel;
import app.pipeline.port.Progress;
import app.study.Row;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The similarity stage after the in-progress extraction: the extracted matrix in, the same matrix
 * with the {@link Augmentation} columns added out.
 *
 * <p>The split is checked first: every row's verdicts must be those the run's rule draws over the
 * matrix's issues ({@link SplitRule}), and a difference refuses the run before the corpus is
 * read, as a matrix drawn by another rule does; so does a corpus missing any issue of the matrix,
 * since its description could not be read. Each description is the one {@link Initial#at}
 * recovers at the issue's first commit, cleaned by
 * {@link DescriptionCleaner}. Every row of the matrix stays, a missing value missing.
 *
 * <p>Every duration median reads the target the extraction wrote ({@link
 * FirstCommit#TARGET_COLUMN}), point in time.
 *
 * <p>The former start-pressure attributes are no longer written: none of the six current
 * attribute sets reads them. The fix-version changelog fidelity remains a diagnostic of the
 * source corpus, not a model input.
 */
public final class IssuesSimilarityTask {

  /**
   * @param refused why the matrix's split is not its rule's, or the matrix's issues the corpus
   *     does not hold; when not empty nothing else was done and the other fields are empty
   * @param sizes the four sides' rows, as the matrix's verdicts count them
   * @param entries the matrix's rows with the new columns, in its order
   * @param described rows with a description at their first In Progress
   * @param bots the raw accounts recognised as bots and left out of every boundary's history
   * @param fixVersions how far the changelog explains the fix versions in the corpus
   *     ({@link FixVersions#fidelity}), on the outer training rows only; null when refused
   */
  public record Report(List<String> refused, ProjectSplit.Sizes sizes,
      List<RowCache.Entry> entries, Augmentation.Result result, int described, Set<String> bots,
      Initial.Fidelity fixVersions) {

    /** A report without the fix versions' fidelity, as a refusal has. */
    public Report(final List<String> refused, final ProjectSplit.Sizes sizes,
        final List<RowCache.Entry> entries, final Augmentation.Result result, final int described,
        final Set<String> bots) {
      this(refused, sizes, entries, result, described, bots, null);
    }
  }

  private final Population population;

  private final StudySource source;
  private final Progress progress;
  private final Function<List<RowCache.Entry>, List<String>> split;
  private final Embedder embedder;
  private final int dimension;
  private final Clusterer clusterer;
  /** The rule the run declares: whether its histories are point in time. */

  /**
   * The descriptions embedded by {@code embedder} into the model's size, the clusters fitted by
   * {@code clusterer}, the split checked against {@code rule}; every history is point in time.
   */
  public IssuesSimilarityTask(final StudySource source, final Progress progress,
      final Embedder embedder, final Clusterer clusterer, final Population population,
      final SplitRule rule) {
    this(source, progress, e -> rule.differences(e, population), embedder,
        EmbeddingModel.DIMENSION, clusterer, population, rule);
  }

  /**
   * Against another check of the split and other vectors, for a matrix built in memory.
   *
   * @param split why the matrix's verdicts are not the split's, empty when they are
   */
  IssuesSimilarityTask(final StudySource source, final Progress progress,
      final Function<List<RowCache.Entry>, List<String>> split, final Embedder embedder,
      final int dimension, final Clusterer clusterer, final Population population,
      final SplitRule rule) {
    this.source = source;
    this.progress = progress;
    this.split = split;
    this.embedder = embedder;
    this.dimension = dimension;
    this.clusterer = clusterer;
    this.population = population;
  }

  public Report run(final List<RowCache.Entry> entries) {
    return firstCommit(entries);
  }

  /**
   * The first-commit matrix ({@link Population#FIRST_COMMIT}): the same columns read at the first
   * commit that counts, the matrix's start. Each description is the one {@link Initial#at}
   * recovers at that instant; every history and median reads the target the extraction wrote
   * ({@link FirstCommit#TARGET_COLUMN}), the training side's only and never the row's own, as
   * {@link Augmentation} reads any target; under the revised temporal design every history is a
   * point-in-time history, and the outer test answers at the inner level too ({@link
   * Augmentation.Histories#POINT_IN_TIME}). The status is not read: no day boundary of the
   * clicker and no active business days are written, the target's own boundary already in the
   * matrix. Refuses, before the corpus is read, a split that is not its rule's and a row without
   * its target of at least one business day or without the attributes of decision 16a; then an
   * issue the corpus does not hold with the matrix's creation and first resolution, or whose
   * start is not between them.
   */
  private Report firstCommit(final List<RowCache.Entry> entries) {
    final ProjectSplit.Sizes found = ElasticNetSelection.sizes(entries);
    final List<String> refused = new ArrayList<>(split.apply(entries));
    if (refused.isEmpty()) {
      final List<String> missing = new ArrayList<>();
      for (final RowCache.Entry e : entries) {
        final Double target = e.row().values().get(FirstCommit.TARGET_COLUMN);
        if (target == null || target != Math.rint(target) || target < 1
            || !e.row().values().keySet().containsAll(FirstCommit.ATTRIBUTE_COLUMNS)) {
          missing.add(e.unit().issueId());
        }
      }
      if (!missing.isEmpty()) {
        refused.add(String.format("%,d rows have no target of at least one business day in %s "
            + "or lack %s, the first %s; give the cache the first-commit extraction wrote",
            missing.size(), FirstCommit.TARGET_COLUMN, FirstCommit.ATTRIBUTE_COLUMNS,
            missing.get(0)));
      }
    }
    if (!refused.isEmpty()) {
      return new Report(refused, found, List.of(), null, 0, Set.of());
    }

    progress.phase(1, 3, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();
    final Universe universe = Universe.of(corpus);
    final List<String> unknown = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      if (!heldAsIs(universe, e)) {
        unknown.add(e.unit().issueId());
      }
    }
    if (!unknown.isEmpty()) {
      return new Report(List.of(String.format("%,d issues of the matrix are not issues of the "
          + "corpus with the same creation and first resolution and a start between them, the "
          + "first %s", unknown.size(), unknown.get(0))), found, List.of(), null, 0, Set.of());
    }
    final Map<String, String> descriptions = descriptions(entries, universe);
    final Map<String, Integer> days = new HashMap<>(entries.size() * 2);
    entries.forEach(e -> days.put(e.unit().issueId(),
        e.row().values().get(FirstCommit.TARGET_COLUMN).intValue()));
    final Map<Variant, Map<String, Integer>> target = new EnumMap<>(Variant.class);
    for (final Variant v : Variant.values()) {
      target.put(v, days);
    }

    progress.phase(2, 3, "Jaccard and embedding neighbours, K-means clusters and reporter medians "
        + "of %,d issues, %,d with a description", entries.size(), descriptions.size());
    final Augmentation.Result result =
        Augmentation.run(issues(entries, descriptions, target), embedder, dimension, clusterer,
            histories());

    progress.phase(3, 3, "adding %d columns", Augmentation.columns().size());
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      final Map<String, Double> added = result.columns().get(e.unit().issueId());
      for (final String column : columns()) {
        final Double v = added.get(column);
        if (v == null) {
          absent.add(column);
        } else {
          values.put(column, v);
        }
      }
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent), e.parts()));
    }
    final List<String> training = entries.stream()
        .filter(e -> Verdict.outerTraining(e.outer(population)))
        .map(e -> e.unit().issueId()).toList();
    return new Report(List.of(), found, out, result, descriptions.size(), Set.of(),
        FixVersions.fidelity(universe, training));
  }

  /**
   * Whether the corpus holds the issue of a first-commit row as the matrix has it: an issue of
   * the universe with the same creation and first resolution, the start strictly after the
   * creation, as the population's rule requires, and before the first resolution.
   */
  static boolean heldAsIs(final Universe universe, final RowCache.Entry e) {
    final Universe.Unit u = universe.unit(e.unit().issueId());
    final long start = e.unit().startedAt().toEpochMilli();
    return u != null && u.created() == e.unit().createdAt().toEpochMilli()
        && u.resolved() == e.unit().resolvedAt().toEpochMilli() && u.created() < start
        && start < u.resolved();
  }

  /** Which members every history admits: point in time, the revised design's. */
  Augmentation.Histories histories() {
    return Augmentation.Histories.POINT_IN_TIME;
  }

  /**
   * The columns this stage adds besides the day boundary and the active business days, a missing
   * one recorded as absent: the {@link Augmentation} columns.
   */
  public static List<String> columns() {
    return Augmentation.columns();
  }

  /** The rows as the augmentation reads them, each with its target in each variant. */
  private List<Augmentation.Issue> issues(final List<RowCache.Entry> entries,
      final Map<String, String> descriptions, final Map<Variant, Map<String, Integer>> target) {
    final List<Augmentation.Issue> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final long start = e.unit().startedAt().toEpochMilli();
      final long resolved = e.unit().resolvedAt().toEpochMilli();
      out.add(new Augmentation.Issue(id, e.unit().project(), e.unit().reporter(), start,
          resolved, target.get(Variant.INNER).get(id), target.get(Variant.OUTER).get(id),
          e.outer(population), e.inner(population), descriptions.get(id)));
    }
    return out;
  }

  /** Each issue's cleaned description at its first In Progress; absent ones left out. */
  private Map<String, String> descriptions(final List<RowCache.Entry> entries,
      final Universe universe) {
    final Set<String> ids = new TreeSet<>();
    entries.forEach(e -> ids.add(e.unit().issueId()));
    final Map<String, Corpus.Text> texts = source.texts(ids);
    final Map<String, String> out = new HashMap<>(ids.size() * 2);
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final String cleaned = DescriptionCleaner.clean(Initial.at(universe.issue(id),
          universe.events(id),
          texts.get(id), e.unit().startedAt().toEpochMilli()).description());
      if (cleaned != null) {
        out.put(id, cleaned);
      }
    }
    return out;
  }
}
