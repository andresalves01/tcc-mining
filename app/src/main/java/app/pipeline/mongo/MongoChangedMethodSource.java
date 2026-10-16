package app.pipeline.mongo;

import app.issues.port.ChangedMethodSource;
import app.labels.ChangedMethod;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads each commit's changed methods for the retrospective scenario through {@link
 * ChangedMethods#scan}, the rule and the loop the funnel's commit complexity shares, so the stages
 * read the same methods for the same commit. Read-only: every call is a {@code find}.
 */
public final class MongoChangedMethodSource implements ChangedMethodSource {

  private final MongoDatabase source;
  private final Progress progress;

  public MongoChangedMethodSource(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.progress = progress;
  }

  @Override
  public Map<String, Map<String, ChangedMethod>> changed(final Collection<String> commitIds) {
    final Map<String, Map<String, ChangedMethod>> out = new HashMap<>(commitIds.size() * 2);
    ChangedMethods.scan(source, commitIds, progress, (id, extraction) -> {
      if (!extraction.recovered().isEmpty()) {
        out.put(id, extraction.recovered());
      }
    });
    return out;
  }
}
