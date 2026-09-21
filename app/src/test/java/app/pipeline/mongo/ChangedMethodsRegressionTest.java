package app.pipeline.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.labels.ChangedMethod;
import app.labels.CommitMethods;
import app.labels.MethodDeduplication;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that the shared method reader still produces the methods the stored labels carry.
 *
 * <p>{@link ChangedMethods} was lifted out of the funnel so the task metrics and the developer's
 * accumulated complexity would share one implementation of "which methods did this commit change".
 * Lifting it changed no rule, but the labels in the database were written by the code as it stood
 * before, and the only way to know the extraction still lands in the same place is to re-derive
 * some of them and compare. Re-running the funnel would also answer it, at half an hour and a
 * rewritten collection.
 *
 * <p>Needs both databases and skips without them, like the other tests in this suite that do.
 */
class ChangedMethodsRegressionTest {

  private static final String MONGO_URI = "mongodb://127.0.0.1:27017";
  private static final int SAMPLE = 60;

  private static String db(final String key, final String fallback) {
    final String configured = System.getenv(key);
    return configured == null || configured.isBlank() ? fallback : configured;
  }

  @Test
  @DisplayName("re-deriving a stored label's methods reproduces exactly what it carries")
  void extractionStillMatchesTheStoredLabels() {
    try (MongoClient client = MongoClients.create(MONGO_URI)) {
      final MongoDatabase source = client.getDatabase(db("SOURCE_DB", "smartshark_2_2"));
      final MongoDatabase target = client.getDatabase(db("TARGET_DB", "smartshark_tcc"));

      final List<Document> labels = new ArrayList<>();
      try {
        target.getCollection("issue_label").aggregate(List.of(Aggregates.sample(SAMPLE)))
            .forEach(labels::add);
      } catch (final MongoException e) {
        assumeTrue(false, "no MongoDB at " + MONGO_URI + ": " + e.getMessage());
        return;
      }
      assumeTrue(!labels.isEmpty(), "issue_label is empty or absent; nothing to re-derive");

      // Which commits carried Java, taken from the ledger rather than recomputed: the funnel read
      // the same rows, so a disagreement here would be about linkage and not about extraction.
      final Set<ObjectId> javaCommits = new HashSet<>(1 << 17);
      for (final Document row : target.getCollection("developer_contribution")
          .find(Filters.gt("java_files_touched", 0)).projection(Projections.include("_id"))) {
        javaCommits.add(row.getObjectId("_id"));
      }
      assumeTrue(!javaCommits.isEmpty(), "developer_contribution is empty; run build-ledger");

      final Set<ObjectId> javaFiles = ChangedMethods.javaFileIds(source, "file");
      final ChangedMethods reader = new ChangedMethods(source);

      int compared = 0;
      for (final Document label : labels) {
        final ObjectId issueId = label.getObjectId("_id");
        final List<CommitMethods> perCommit = new ArrayList<>();
        for (final Document commit : source.getCollection("commit")
            .find(Filters.eq("linked_issue_ids", issueId))
            .projection(Projections.include("revision_hash", "author_date", "parents"))) {

          final List<?> parents = commit.getList("parents", Object.class);
          if (parents != null && parents.size() > 1) {
            continue; // merges are excluded from both volume and complexity
          }
          final ObjectId commitId = commit.getObjectId("_id");
          if (!javaCommits.contains(commitId)) {
            continue;
          }
          final Instant at = commit.getDate("author_date").toInstant();
          perCommit.add(new CommitMethods(commit.getString("revision_hash"), at,
              reader.of(commitId, javaFiles).recovered()));
        }

        final Map<String, Double> derived = new TreeMap<>();
        for (final ChangedMethod m : MethodDeduplication.byLatestChange(perCommit)) {
          derived.put(m.longName(), m.mccc());
        }
        final Map<String, Double> stored = new TreeMap<>();
        for (final Document m : label.getList("methods", Document.class, List.of())) {
          stored.put(m.getString("long_name"), m.getDouble("mccc"));
        }
        assertEquals(stored, derived,
            "label " + issueId.toHexString() + " no longer re-derives to what it stores -- "
                + "the extraction rule moved, or the linkage this test reconstructs is not the "
                + "one the funnel applied");
        compared++;
      }
      assumeTrue(compared > 0, "no label could be re-derived; nothing was checked");
    }
  }
}
