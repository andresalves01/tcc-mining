package app.pipeline;

import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.Clusterer;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.FixVersions;
import app.issues.Embedder;
import app.issues.Initial;
import app.issues.Pressure;
import app.issues.ProjectSplit;
import app.issues.RegistrationInProgress;
import app.issues.Releases;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
import app.pipeline.embedding.EmbeddingModel;
import app.pipeline.port.Progress;
import app.study.Row;
import java.util.ArrayList;
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
 * <p>The split is checked first: every row's verdicts must be those its rule draws over the
 * matrix's issues ({@link ProjectSplit}), and a difference refuses the run before the corpus is
 * read, as a matrix of the temporal cut does; so does a corpus missing any issue of the matrix,
 * since its description could not be read. Each description is the one {@link Initial#at}
 * recovers at the issue's first In Progress, as the language report reads it, cleaned by
 * {@link DescriptionCleaner}. Every row of the matrix stays, a missing value missing.
 *
 * <p>Each issue's day boundary ({@link DayBoundaries}) is read from the corpus's tracker activity
 * before its first In Progress, by the person who made that move, and written to the matrix as
 * {@link BusinessDays#BOUNDARY}. The issue's active business days at it, without the time its
 * changelog shows it back in Open or Reopened ({@link BusinessDays#active}), are written as {@link
 * BusinessDays#ACTIVE}: they are what the medians of the {@link Augmentation} read, and what the
 * later steps take as the target. Both are counted as the extraction counted them to draw the
 * split ({@link BusinessDays#of}).
 *
 * <p>The own start model's three attributes of ticket 19 ({@link Pressure}: a fix version set
 * before the start, the days left in the project's release cycle, the pace of its other Blocker
 * issues) are written beside them: the first two read from the corpus at each issue's first In
 * Progress, the Blocker pace from the training side of each variant of the split, as the
 * similarity medians are (ticket 21).
 */
public final class IssuesSimilarityTask {

  /**
   * @param refused why the matrix's split is not its rule's, or the matrix's issues the corpus
   *     does not hold; when not empty nothing else was done and the other fields are empty
   * @param sizes the four sides' rows, as the matrix's verdicts count them
   * @param entries the matrix's rows with the new columns, in its order
   * @param described rows with a description at their first In Progress
   * @param boundaries per issue id, its day boundary
   * @param bots the raw accounts recognised as bots and left out of every boundary's history
   * @param fixVersions how far the changelog explains the fix versions the start columns read
   *     ({@link FixVersions#fidelity}), on the outer training rows only; null when refused
   */
  public record Report(List<String> refused, RegistrationInProgress.Sizes sizes,
      List<RowCache.Entry> entries, Augmentation.Result result, int described,
      Map<String, DayBoundaries.Boundary> boundaries, Set<String> bots,
      Initial.Fidelity fixVersions) {

    /** A report without the fix versions' fidelity, as a refusal has. */
    public Report(final List<String> refused, final RegistrationInProgress.Sizes sizes,
        final List<RowCache.Entry> entries, final Augmentation.Result result, final int described,
        final Map<String, DayBoundaries.Boundary> boundaries, final Set<String> bots) {
      this(refused, sizes, entries, result, described, boundaries, bots, null);
    }
  }

  private static final Population P = Population.IN_PROGRESS;

  private final StudySource source;
  private final Progress progress;
  private final Function<List<RowCache.Entry>, List<String>> split;
  private final Embedder embedder;
  private final int dimension;
  private final Clusterer clusterer;

  /**
   * The split checked against its rule ({@link ProjectSplit#differences}), the descriptions
   * embedded by {@code embedder} into the model's size, the clusters fitted by {@code clusterer}.
   */
  public IssuesSimilarityTask(final StudySource source, final Progress progress,
      final Embedder embedder, final Clusterer clusterer) {
    this(source, progress, ProjectSplit::differences, embedder, EmbeddingModel.DIMENSION,
        clusterer);
  }

  /**
   * Against another check of the split and other vectors, for a matrix built in memory.
   *
   * @param split why the matrix's verdicts are not the split's, empty when they are
   */
  IssuesSimilarityTask(final StudySource source, final Progress progress,
      final Function<List<RowCache.Entry>, List<String>> split, final Embedder embedder,
      final int dimension, final Clusterer clusterer) {
    this.source = source;
    this.progress = progress;
    this.split = split;
    this.embedder = embedder;
    this.dimension = dimension;
    this.clusterer = clusterer;
  }

  public Report run(final List<RowCache.Entry> entries) {
    final RegistrationInProgress.Sizes found = ElasticNetSelection.sizes(entries);
    final List<String> refused = split.apply(entries);
    if (!refused.isEmpty()) {
      return new Report(refused, found, List.of(), null, 0, Map.of(), Set.of());
    }

    progress.phase(1, 4, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();
    final Universe universe = Universe.of(corpus);
    final List<String> unknown = entries.stream().map(e -> e.unit().issueId())
        .filter(id -> universe.issue(id) == null).toList();
    if (!unknown.isEmpty()) {
      return new Report(List.of(String.format("%,d issues of the matrix are not in the corpus, "
          + "the first %s", unknown.size(), unknown.get(0))), found, List.of(), null, 0,
          Map.of(), Set.of());
    }
    // the start columns are read at each issue's first In Progress: a row the corpus does not hold
    // as that In-Progress issue refuses, rather than leaving its fix version flag to be filled
    final List<String> unstarted = new ArrayList<>();
    for (final RowCache.Entry e : entries) {
      final Universe.Unit u = universe.unit(P, e.unit().issueId());
      if (u == null || u.start() != e.unit().startedAt().toEpochMilli()
          || u.resolved() != e.unit().resolvedAt().toEpochMilli()) {
        unstarted.add(e.unit().issueId());
      }
    }
    if (!unstarted.isEmpty()) {
      return new Report(List.of(String.format("%,d issues of the matrix are not In-Progress "
          + "issues of the corpus with the same first In Progress and first resolution, the "
          + "first %s", unstarted.size(), unstarted.get(0))), found, List.of(), null, 0,
          Map.of(), Set.of());
    }
    final Map<String, String> descriptions = descriptions(entries, universe);

    progress.phase(2, 4, "day boundaries from the tracker activity before each first In "
        + "Progress");
    // every row is an In-Progress issue of the corpus: the refusal above saw to it
    final List<String> ids = entries.stream().map(e -> e.unit().issueId()).toList();
    final BusinessDays.Counted counted = BusinessDays.of(corpus, universe, ids);
    final Map<String, DayBoundaries.Boundary> boundaries = counted.boundaries();
    final Map<String, Integer> active = counted.active();

    progress.phase(3, 4, "Jaccard and embedding neighbours, K-means clusters and reporter medians "
        + "of %,d issues, %,d with a description", entries.size(), descriptions.size());
    final Augmentation.Result result =
        Augmentation.run(issues(entries, descriptions, active), embedder, dimension, clusterer);

    progress.phase(4, 4, "adding %d columns", Augmentation.columns().size()
        + BusinessDays.COLUMNS.size() + Pressure.COLUMNS.size());
    final Map<String, String[]> parts = new HashMap<>(entries.size() * 2);
    entries.forEach(e -> parts.put(e.unit().issueId(), new String[] {e.outer(P), e.inner(P)}));
    final Pressure pressure = Pressure.of(universe, Releases.of(corpus), parts);
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      final Map<String, Double> added = new HashMap<>(result.columns().get(e.unit().issueId()));
      added.putAll(pressure.row(universe.unit(P, e.unit().issueId())));
      values.put(BusinessDays.BOUNDARY, (double) boundaries.get(e.unit().issueId()).hour());
      values.put(BusinessDays.ACTIVE, (double) active.get(e.unit().issueId()));
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
    // the outer test is left out of the gate, as of every report this stage prints
    final List<String> training = entries.stream().filter(e -> "TRAIN".equals(e.outer(P)))
        .map(e -> e.unit().issueId()).toList();
    return new Report(refused, found, out, result, descriptions.size(), boundaries,
        counted.bots(), FixVersions.fidelity(universe, training));
  }

  /**
   * The columns this stage adds besides the day boundary and the active business days, a missing
   * one recorded as absent: the {@link Augmentation} columns, then the {@link Pressure} ones.
   */
  public static List<String> columns() {
    final List<String> out = new ArrayList<>(Augmentation.columns());
    out.addAll(Pressure.COLUMNS);
    return List.copyOf(out);
  }

  /** The rows as the augmentation reads them, each with its active business days. */
  private static List<Augmentation.Issue> issues(final List<RowCache.Entry> entries,
      final Map<String, String> descriptions, final Map<String, Integer> active) {
    final List<Augmentation.Issue> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final long start = e.unit().startedAt().toEpochMilli();
      final long resolved = e.unit().resolvedAt().toEpochMilli();
      out.add(new Augmentation.Issue(id, e.unit().project(), e.unit().reporter(), start,
          resolved, active.get(id), e.outer(P), e.inner(P), descriptions.get(id)));
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
