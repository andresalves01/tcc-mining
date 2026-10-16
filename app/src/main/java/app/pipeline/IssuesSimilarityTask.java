package app.pipeline;

import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.Clusterer;
import app.issues.Corpus;
import app.issues.DayBoundaries;
import app.issues.DescriptionCleaner;
import app.issues.ElasticNetSelection;
import app.issues.Embedder;
import app.issues.Initial;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.World;
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

/**
 * The similarity stage after the in-progress extraction: the extracted matrix in, the same matrix
 * with the {@link Augmentation} columns added out.
 *
 * <p>The four split sizes are checked first, against the registered study's, and a difference
 * refuses the run before the corpus is read; so does a corpus missing any issue of the matrix,
 * since its description could not be read. Each description is the one {@link Initial#at}
 * recovers at the issue's first In Progress, as the language report reads it, cleaned by
 * {@link DescriptionCleaner}. Every row of the matrix stays, a missing value missing.
 *
 * <p>Each issue's day boundary ({@link DayBoundaries}) is read from the corpus's tracker activity
 * before its first In Progress, by the person who made that move, and written to the matrix as
 * {@link BusinessDays#BOUNDARY}; the issue's business days at it are what the medians of the
 * {@link Augmentation} read, and what the later steps take as the target.
 */
public final class IssuesSimilarityTask {

  /**
   * @param refused every split size that differs from the expected, or the matrix's issues the
   *     corpus does not hold; when not empty nothing else was done and the other fields are empty
   * @param entries the matrix's rows with the new columns, in its order
   * @param described rows with a description at their first In Progress
   * @param boundaries per issue id, its day boundary
   * @param bots the raw accounts recognised as bots and left out of every boundary's history
   */
  public record Report(List<String> refused, RegistrationInProgress.Sizes sizes,
      List<RowCache.Entry> entries, Augmentation.Result result, int described,
      Map<String, DayBoundaries.Boundary> boundaries, Set<String> bots) {}

  private static final Population P = Population.IN_PROGRESS;

  private final StudySource source;
  private final Progress progress;
  private final RegistrationInProgress.Sizes expected;
  private final Embedder embedder;
  private final int dimension;
  private final Clusterer clusterer;

  /**
   * The registered sizes, the descriptions embedded by {@code embedder} into the model's size,
   * the clusters fitted by {@code clusterer}.
   */
  public IssuesSimilarityTask(final StudySource source, final Progress progress,
      final Embedder embedder, final Clusterer clusterer) {
    this(source, progress, RegistrationInProgress.SIZES, embedder, EmbeddingModel.DIMENSION,
        clusterer);
  }

  /** Against other sizes and vectors, for a matrix built in memory. */
  IssuesSimilarityTask(final StudySource source, final Progress progress,
      final RegistrationInProgress.Sizes expected, final Embedder embedder,
      final int dimension, final Clusterer clusterer) {
    this.source = source;
    this.progress = progress;
    this.expected = expected;
    this.embedder = embedder;
    this.dimension = dimension;
    this.clusterer = clusterer;
  }

  public Report run(final List<RowCache.Entry> entries) {
    final RegistrationInProgress.Sizes found = ElasticNetSelection.sizes(entries);
    final List<String> refused = differences(found, expected);
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
    final Map<String, String> descriptions = descriptions(entries, universe);

    progress.phase(2, 4, "day boundaries from the tracker activity before each first In "
        + "Progress");
    final DayBoundaries days = DayBoundaries.of(corpus, universe);
    final Map<String, DayBoundaries.Boundary> boundaries = new HashMap<>(entries.size() * 2);
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final Universe.Unit started = universe.unit(P, id);
      final String clicker = started == null ? null
          : World.person(started.startedBy(), corpus.people());
      boundaries.put(id, days.at(clicker, e.unit().project(),
          e.unit().startedAt().toEpochMilli()));
    }

    progress.phase(3, 4, "Jaccard and embedding neighbours, K-means clusters and reporter medians "
        + "of %,d issues, %,d with a description", entries.size(), descriptions.size());
    final Augmentation.Result result =
        Augmentation.run(issues(entries, descriptions, boundaries), embedder, dimension,
            clusterer);

    progress.phase(4, 4, "adding %d columns", Augmentation.columns().size() + 1);
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      final Map<String, Double> added = result.columns().get(e.unit().issueId());
      values.put(BusinessDays.BOUNDARY, (double) boundaries.get(e.unit().issueId()).hour());
      for (final String column : Augmentation.columns()) {
        final Double v = added.get(column);
        if (v == null) {
          absent.add(column);
        } else {
          values.put(column, v);
        }
      }
      out.add(new RowCache.Entry(e.unit(), new Row(e.row().unitId(), values, absent), e.parts()));
    }
    return new Report(refused, found, out, result, descriptions.size(), boundaries,
        days.bots());
  }

  static List<String> differences(final RegistrationInProgress.Sizes found,
      final RegistrationInProgress.Sizes expected) {
    final List<String> out = new ArrayList<>();
    check(out, "outer training", found.train(), expected.train());
    check(out, "outer test", found.test(), expected.test());
    check(out, "inner fitting", found.innerFit(), expected.innerFit());
    check(out, "inner validation", found.innerSelection(), expected.innerSelection());
    return out;
  }

  private static void check(final List<String> out, final String side, final int found,
      final int expected) {
    if (found != expected) {
      out.add(String.format("%s: %d rows, expected %d", side, found, expected));
    }
  }

  /** The rows as the augmentation reads them, each with its business days at its boundary. */
  private static List<Augmentation.Issue> issues(final List<RowCache.Entry> entries,
      final Map<String, String> descriptions,
      final Map<String, DayBoundaries.Boundary> boundaries) {
    final List<Augmentation.Issue> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      final long start = e.unit().startedAt().toEpochMilli();
      final long resolved = e.unit().resolvedAt().toEpochMilli();
      out.add(new Augmentation.Issue(id, e.unit().project(), e.unit().reporter(), start,
          resolved, BusinessDays.count(start, resolved, boundaries.get(id).hour()), e.outer(P),
          e.inner(P), descriptions.get(id)));
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
