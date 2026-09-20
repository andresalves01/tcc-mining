package app.pipeline.mongo;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * MongoDB adapter for {@link ContributionLedger}.
 *
 * <p>Written into the same separate database as the reconstructed identities, never into the
 * published corpus.
 *
 * <p>The commit's own {@code ObjectId} is reused as {@code _id}. That is what makes a re-run
 * idempotent — a second pass rewrites the same rows instead of doubling the ledger — and it keeps
 * every row joinable straight back to {@code smartshark_2_2.commit} without a lookup table.
 *
 * <p>Two indexes, one per question actually asked of this collection: accumulate one developer's
 * history up to an instant, and enumerate a project's activity in a window.
 */
public final class MongoContributionLedger implements ContributionLedger {

  public static final String DEVELOPER_CONTRIBUTION = "developer_contribution";

  private static final int BATCH_SIZE = 5_000;

  private final MongoDatabase target;
  private final String targetName;

  public MongoContributionLedger(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public long save(final Collection<Contribution> contributions) {
    if (contributions.isEmpty()) {
      return 0;
    }
    final MongoCollection<Document> collection = target.getCollection(DEVELOPER_CONTRIBUTION);
    final ReplaceOptions upsert = new ReplaceOptions().upsert(true);
    final List<WriteModel<Document>> batch = new ArrayList<>(BATCH_SIZE);
    long total = 0;
    for (final Contribution c : contributions) {
      final ObjectId id = new ObjectId(c.commitId());
      batch.add(new ReplaceOneModel<>(new Document("_id", id), toDocument(c, id), upsert));
      if (batch.size() == BATCH_SIZE) {
        total += write(collection, batch);
        batch.clear();
      }
    }
    total += write(collection, batch);

    collection.createIndex(Indexes.ascending("canonical_id", "authored_at"),
        new IndexOptions().background(true));
    collection.createIndex(Indexes.ascending("project_id", "authored_at"),
        new IndexOptions().background(true));
    return total;
  }

  @Override
  public List<Contribution> readAll() {
    final List<Contribution> out = new ArrayList<>();
    for (final Document doc : target.getCollection(DEVELOPER_CONTRIBUTION).find()) {
      out.add(new Contribution(
          doc.getObjectId("_id").toHexString(),
          doc.getString("revision_hash"),
          hex(doc.getObjectId("project_id")),
          doc.getString("project"),
          hex(doc.getObjectId("vcs_system_id")),
          hex(doc.getObjectId("author_id")),
          hex(doc.getObjectId("canonical_id")),
          Boolean.TRUE.equals(doc.getBoolean("is_bot")),
          doc.getDate("authored_at").toInstant(),
          doc.getInteger("lines_added", 0),
          doc.getInteger("lines_deleted", 0),
          doc.getInteger("java_lines_added", 0),
          doc.getInteger("java_lines_deleted", 0),
          doc.getInteger("files_touched", 0),
          doc.getInteger("java_files_touched", 0)));
    }
    return out;
  }

  private static String hex(final ObjectId id) {
    return id == null ? null : id.toHexString();
  }

  @Override
  public long count() {
    return target.getCollection(DEVELOPER_CONTRIBUTION).countDocuments();
  }

  @Override
  public String describe() {
    return targetName + "." + DEVELOPER_CONTRIBUTION;
  }

  private static Document toDocument(final Contribution c, final ObjectId id) {
    return new Document("_id", id)
        .append("revision_hash", c.revisionHash())
        .append("project_id", new ObjectId(c.projectId()))
        .append("project", c.projectName())
        .append("vcs_system_id", new ObjectId(c.vcsSystemId()))
        .append("author_id", new ObjectId(c.authorId()))
        .append("canonical_id", new ObjectId(c.canonicalId()))
        .append("is_bot", c.bot())
        .append("authored_at", Date.from(c.authoredAt()))
        .append("lines_added", c.linesAdded())
        .append("lines_deleted", c.linesDeleted())
        .append("java_lines_added", c.javaLinesAdded())
        .append("java_lines_deleted", c.javaLinesDeleted())
        .append("files_touched", c.filesTouched())
        .append("java_files_touched", c.javaFilesTouched());
  }

  private static long write(
      final MongoCollection<Document> collection, final List<WriteModel<Document>> batch) {
    if (batch.isEmpty()) {
      return 0;
    }
    final BulkWriteResult result = collection.bulkWrite(batch);
    return result.getInsertedCount() + result.getModifiedCount() + result.getUpserts().size();
  }
}
