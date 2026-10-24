package app.pipeline.mongo;

import app.features.CommitComplexity;
import app.features.port.CommitComplexityStore;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Writes {@code commit_complexity}, one document per commit read.
 *
 * <p>Upserted on the commit's own id, never dropped, for the reason the contribution ledger is: the
 * row describes the commit and the corpus, not the current label set, so a re-run repairs instead
 * of replacing. It is also what makes a two-hour build resumable — the task asks which ids are
 * already here and reads only the rest.
 */
public final class MongoCommitComplexityStore implements CommitComplexityStore {

  public static final String COMMIT_COMPLEXITY = "commit_complexity";

  private static final int BATCH_SIZE = 5_000;

  private final MongoDatabase target;
  private final String targetName;

  public MongoCommitComplexityStore(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public long save(final Collection<CommitComplexity> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    final MongoCollection<Document> collection = target.getCollection(COMMIT_COMPLEXITY);
    final ReplaceOptions upsert = new ReplaceOptions().upsert(true);
    final List<WriteModel<Document>> batch = new ArrayList<>(BATCH_SIZE);
    long written = 0;
    for (final CommitComplexity row : rows) {
      final ObjectId id = new ObjectId(row.commitId());
      batch.add(new ReplaceOneModel<>(new Document("_id", id), new Document("_id", id)
          .append("mccc", row.mccc())
          .append("nl", row.nl())
          .append("methods", row.methods())
          .append("unrecoverable", row.unrecoverable()), upsert));
      if (batch.size() == BATCH_SIZE) {
        written += write(collection, batch);
      }
    }
    return written + write(collection, batch);
  }

  @Override
  public Set<String> storedIds() {
    final Set<String> ids = new HashSet<>(1 << 18);
    for (final Document doc : target.getCollection(COMMIT_COMPLEXITY)
        .find().projection(Projections.include("_id"))) {
      ids.add(doc.getObjectId("_id").toHexString());
    }
    return ids;
  }

  private static long write(final MongoCollection<Document> collection,
      final List<WriteModel<Document>> batch) {

    if (batch.isEmpty()) {
      return 0;
    }
    final BulkWriteResult result = collection.bulkWrite(batch);
    batch.clear();
    return result.getInsertedCount() + result.getUpserts().size() + result.getModifiedCount();
  }

  @Override
  public String describe() {
    return targetName + "." + COMMIT_COMPLEXITY;
  }
}
