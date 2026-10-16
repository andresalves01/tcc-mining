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

/**
 * Reads each commit's changed methods from the corpus and sums their complexity.
 *
 * <p>Shares {@link ChangedMethods} with the funnel, so there is one rule for which methods a commit
 * changed, and its {@link ChangedMethods#scan} with the retrospective scenario, so there is one
 * loop over many commits. What differs is the arithmetic on top: here every commit's methods are
 * summed and a method touched again later is counted again, because this quantity measures
 * repeated contact with complex code rather than the description of one task.
 *
 * <p>Commits that changed no recoverable method are emitted as zeros rather than skipped. A row
 * that says "read, found nothing" is what lets a resumed run tell itself apart from one that never
 * reached the commit at all.
 */
public final class MongoCommitComplexity implements CommitComplexitySource {

  /** Declared so {@code check} can refuse before the stage spends an hour discovering the gap. */
  public static final Set<String> READS = ChangedMethods.SCAN_READS;

  private final MongoDatabase source;
  private final Progress progress;

  public MongoCommitComplexity(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.progress = progress;
  }

  @Override
  public Report scan(final Collection<String> commitIds, final Consumer<CommitComplexity> sink) {
    final int[] scanned = {0};
    final int[] withMethods = {0};
    final long[] methods = {0};
    final long[] unrecoverable = {0};
    ChangedMethods.scan(source, commitIds, progress, (id, extraction) -> {
      double mccc = 0;
      double nl = 0;
      for (final Map.Entry<String, ChangedMethod> entry : extraction.recovered().entrySet()) {
        mccc += entry.getValue().mccc();
        nl += entry.getValue().nl();
      }
      final int recovered = extraction.recovered().size();
      sink.accept(new CommitComplexity(id, mccc, nl, recovered, extraction.unrecoverable()));
      scanned[0]++;
      if (recovered > 0) {
        withMethods[0]++;
      }
      methods[0] += recovered;
      unrecoverable[0] += extraction.unrecoverable();
    });
    return new Report(scanned[0], withMethods[0], methods[0], unrecoverable[0]);
  }
}
