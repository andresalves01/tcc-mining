package app.pipeline.mongo;

import app.features.CommitComplexity;
import app.features.port.CommitComplexitySource;
import app.labels.ChangedMethod;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;

/**
 * Reads each commit's changed methods from the corpus and sums their complexity.
 *
 * <p>Shares {@link ChangedMethods} with the funnel, so there is one rule for which methods a commit
 * changed. What differs is the arithmetic on top: here every commit's methods are summed and a
 * method touched again later is counted again, because this quantity measures repeated contact with
 * complex code rather than the description of one task.
 *
 * <p>Commits that changed no recoverable method are emitted as zeros rather than skipped. A row
 * that says "read, found nothing" is what lets a resumed run tell itself apart from one that never
 * reached the commit at all.
 */
public final class MongoCommitComplexity implements CommitComplexitySource {

  private static final String FILE = "file";

  /** Declared so {@code check} can refuse before the stage spends an hour discovering the gap. */
  public static final Set<String> READS = Stream.concat(
      Stream.of(FILE), ChangedMethods.READS.stream()).collect(Collectors.toUnmodifiableSet());

  private static final int PROGRESS_EVERY = 2_000;

  private final MongoDatabase source;
  private final Progress progress;

  public MongoCommitComplexity(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.progress = progress;
  }

  @Override
  public Report scan(final Collection<String> commitIds, final Consumer<CommitComplexity> sink) {
    progress.detail("indexing .java files");
    final Set<ObjectId> javaFiles = ChangedMethods.javaFileIds(source, FILE);
    progress.detail("%,d .java files", javaFiles.size());

    final ChangedMethods reader = new ChangedMethods(source);
    int scanned = 0;
    int withMethods = 0;
    long methods = 0;
    long unrecoverable = 0;
    for (final String id : commitIds) {
      final ChangedMethods.Extraction extraction =
          reader.of(new ObjectId(id), javaFiles);
      double mccc = 0;
      double nl = 0;
      for (final Map.Entry<String, ChangedMethod> entry : extraction.recovered().entrySet()) {
        mccc += entry.getValue().mccc();
        nl += entry.getValue().nl();
      }
      final int recovered = extraction.recovered().size();
      sink.accept(new CommitComplexity(id, mccc, nl, recovered, extraction.unrecoverable()));

      scanned++;
      if (recovered > 0) {
        withMethods++;
      }
      methods += recovered;
      unrecoverable += extraction.unrecoverable();
      if (scanned % PROGRESS_EVERY == 0) {
        progress.count(scanned, commitIds.size(), "commits read");
      }
    }
    return new Report(scanned, withMethods, methods, unrecoverable);
  }
}
