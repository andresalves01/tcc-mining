package app.pipeline;

import app.issues.Augmentation;
import app.issues.Corpus;
import app.issues.DescriptionCleaner;
import app.issues.Initial;
import app.issues.RegistrationInProgress;
import app.issues.RowCache;
import app.issues.Universe;
import app.issues.Universe.Population;
import app.issues.port.StudySource;
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
 */
public final class IssuesSimilarityTask {

  /**
   * @param refused every split size that differs from the expected, or the matrix's issues the
   *     corpus does not hold; when not empty nothing else was done and the other fields are empty
   * @param entries the matrix's rows with the new columns, in its order
   * @param described rows with a description at their first In Progress
   */
  public record Report(List<String> refused, RegistrationInProgress.Sizes sizes,
      List<RowCache.Entry> entries, Augmentation.Result result, int described) {}

  private static final Population P = Population.IN_PROGRESS;

  private final StudySource source;
  private final Progress progress;
  private final RegistrationInProgress.Sizes expected;

  public IssuesSimilarityTask(final StudySource source, final Progress progress) {
    this(source, progress, RegistrationInProgress.SIZES);
  }

  /** Against other sizes, for a matrix built in memory. */
  IssuesSimilarityTask(final StudySource source, final Progress progress,
      final RegistrationInProgress.Sizes expected) {
    this.source = source;
    this.progress = progress;
    this.expected = expected;
  }

  public Report run(final List<RowCache.Entry> entries) {
    final RegistrationInProgress.Sizes found = Augmentation.sizes(issues(entries, Map.of()));
    final List<String> refused = differences(found, expected);
    if (!refused.isEmpty()) {
      return new Report(refused, found, List.of(), null, 0);
    }

    progress.phase(1, 3, "reading the corpus: %s", source.describe());
    final Corpus corpus = source.load();
    final Universe universe = Universe.of(corpus);
    final List<String> unknown = entries.stream().map(e -> e.unit().issueId())
        .filter(id -> universe.issue(id) == null).toList();
    if (!unknown.isEmpty()) {
      return new Report(List.of(String.format("%,d issues of the matrix are not in the corpus, "
          + "the first %s", unknown.size(), unknown.get(0))), found, List.of(), null, 0);
    }
    final Map<String, String> descriptions = descriptions(entries, universe);

    progress.phase(2, 3, "Jaccard neighbours of %,d issues, %,d with a description",
        entries.size(), descriptions.size());
    final Augmentation.Result result = Augmentation.run(issues(entries, descriptions));

    progress.phase(3, 3, "adding %d columns", Augmentation.columns().size());
    final List<RowCache.Entry> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final Map<String, Double> values = new HashMap<>(e.row().values());
      final Set<String> absent = new HashSet<>(e.row().absent());
      final Map<String, Double> added = result.columns().get(e.unit().issueId());
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
    return new Report(refused, found, out, result, descriptions.size());
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

  private static List<Augmentation.Issue> issues(final List<RowCache.Entry> entries,
      final Map<String, String> descriptions) {
    final List<Augmentation.Issue> out = new ArrayList<>(entries.size());
    for (final RowCache.Entry e : entries) {
      final String id = e.unit().issueId();
      out.add(new Augmentation.Issue(id, e.unit().project(), e.unit().startedAt().toEpochMilli(),
          e.unit().resolvedAt().toEpochMilli(), e.outer(P), e.inner(P), descriptions.get(id)));
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
