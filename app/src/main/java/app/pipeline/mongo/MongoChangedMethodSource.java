package app.pipeline.mongo;

import app.issues.port.ChangedMethodSource;
import app.labels.ChangedMethod;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;

/**
 * Reads each commit's changed methods for the retrospective scenario through {@link
 * ChangedMethods}, the rule the funnel and the commit complexity share, so the three read the same
 * methods for the same commit. Read-only: every call is a {@code find}.
 */
public final class MongoChangedMethodSource implements ChangedMethodSource {

  private static final String FILE = "file";

  /** Declared so {@code check} can refuse before the stage spends an hour discovering the gap. */
  public static final Set<String> READS = Stream.concat(
      Stream.of(FILE), ChangedMethods.READS.stream()).collect(Collectors.toUnmodifiableSet());

  private static final int PROGRESS_EVERY = 2_000;

  private final MongoDatabase source;
  private final Progress progress;

  public MongoChangedMethodSource(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.progress = progress;
  }

  @Override
  public Set<String> reads() {
    return READS;
  }

  @Override
  public Map<String, Map<String, ChangedMethod>> changed(final Collection<String> commitIds) {
    progress.detail("indexing .java files");
    final Set<ObjectId> javaFiles = ChangedMethods.javaFileIds(source, FILE);
    progress.detail("%,d .java files", javaFiles.size());
    final ChangedMethods reader = new ChangedMethods(source);
    final Map<String, Map<String, ChangedMethod>> out = new HashMap<>(commitIds.size() * 2);
    int scanned = 0;
    for (final String id : commitIds) {
      final Map<String, ChangedMethod> recovered =
          reader.of(new ObjectId(id), javaFiles).recovered();
      if (!recovered.isEmpty()) {
        out.put(id, recovered);
      }
      if (++scanned % PROGRESS_EVERY == 0) {
        progress.count(scanned, commitIds.size(), "commits read");
      }
    }
    return out;
  }
}
