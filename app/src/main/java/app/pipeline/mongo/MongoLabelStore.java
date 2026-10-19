package app.pipeline.mongo;

import app.labels.ChangedMethod;
import app.labels.ExamOrigin;
import app.labels.IssueLabel;
import app.labels.port.LabelStore;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
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
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Writes {@code issue_label}, one document per surviving label.
 *
 * <p>The collection is dropped before writing rather than upserted into. A funnel run is a
 * statement about which issues survive a set of rules, and rules that tighten produce fewer rows —
 * upserting would leave the rows of a looser earlier run behind, indistinguishable from the current
 * ones. The contribution ledger can be upserted safely because its key space never shrinks; this
 * one's does.
 */
public final class MongoLabelStore implements LabelStore {

  public static final String ISSUE_LABEL = "issue_label";

  private static final int BATCH_SIZE = 2_000;

  private final MongoDatabase target;
  private final String targetName;

  public MongoLabelStore(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public long save(final Collection<IssueLabel> labels) {
    target.getCollection(ISSUE_LABEL).drop();
    final List<WriteModel<Document>> batch = new ArrayList<>(BATCH_SIZE);
    long written = 0;
    for (final IssueLabel label : labels) {
      batch.add(new ReplaceOneModel<>(new Document("_id", new ObjectId(label.issueId())),
          document(label), new ReplaceOptions().upsert(true)));
      if (batch.size() == BATCH_SIZE) {
        written += flush(batch);
      }
    }
    written += flush(batch);
    target.getCollection(ISSUE_LABEL)
        .createIndex(Indexes.ascending("project", "resolved_at"), new IndexOptions().background(true));
    target.getCollection(ISSUE_LABEL)
        .createIndex(Indexes.ascending("canonical_id", "resolved_at"), new IndexOptions().background(true));
    return written;
  }

  private static Document document(final IssueLabel label) {
    final List<Document> methods = label.methods().stream()
        .map(m -> new Document("long_name", m.longName())
            .append("mccc", m.mccc()).append("nl", m.nl()))
        .toList();
    final Document doc = new Document("_id", new ObjectId(label.issueId()))
        .append("project", label.projectName())
        .append("created_at", Date.from(label.createdAt()))
        .append("resolved_at", Date.from(label.resolvedAt()))
        .append("ttc_days", label.ttcDays())
        .append("canonical_id", new ObjectId(label.canonicalAuthorId()))
        .append("first_commit_at", Date.from(label.firstCommitAt()))
        .append("commits", label.commits())
        .append("java_lines_added", label.javaLinesAdded())
        .append("java_lines_deleted", label.javaLinesDeleted())
        .append("n_methods", methods.size())
        .append("mean_mccc", label.meanMccc().orElseThrow())
        .append("mean_nl", label.meanNl().orElseThrow())
        .append("methods", methods);
    // Absent is absent: a label with no external examination gets no field here rather than a
    // zero.
    label.examinedAt().ifPresent(at -> doc.append("examined_at", Date.from(at)));
    label.examOrigin().ifPresent(origin -> doc.append("exam_origin", origin.name()));
    label.waitDays().ifPresent(days -> doc.append("ttc_espera", days));
    label.authorDays().ifPresent(days -> doc.append("ttc_autor", days));
    doc.append("ttc_util", label.workingDays());
    label.workingWaitDays().ifPresent(days -> doc.append("ttc_espera_util", days));
    label.workingAuthorDays().ifPresent(days -> doc.append("ttc_autor_util", days));
    return doc;
  }

  private long flush(final List<WriteModel<Document>> batch) {
    if (batch.isEmpty()) {
      return 0;
    }
    final BulkWriteResult result = target.getCollection(ISSUE_LABEL).bulkWrite(batch);
    batch.clear();
    return result.getInsertedCount() + result.getUpserts().size() + result.getModifiedCount();
  }

  @Override
  public List<IssueLabel> readAll() {
    final List<IssueLabel> labels = new ArrayList<>(1 << 16);
    for (final Document doc : target.getCollection(ISSUE_LABEL).find()) {
      final List<Document> methods = doc.getList("methods", Document.class, List.of());
      labels.add(new IssueLabel(
          doc.getObjectId("_id").toHexString(),
          doc.getString("project"),
          doc.getDate("created_at").toInstant(),
          doc.getDate("resolved_at").toInstant(),
          doc.getObjectId("canonical_id").toHexString(),
          doc.getDate("first_commit_at").toInstant(),
          doc.getInteger("commits", 0),
          doc.getLong("java_lines_added") == null
              ? doc.getInteger("java_lines_added", 0) : doc.getLong("java_lines_added"),
          doc.getLong("java_lines_deleted") == null
              ? doc.getInteger("java_lines_deleted", 0) : doc.getLong("java_lines_deleted"),
          methods.stream().map(m -> new ChangedMethod(m.getString("long_name"),
              m.getDouble("mccc"), m.getDouble("nl"))).toList(),
          Optional.ofNullable(doc.getDate("examined_at")).map(Date::toInstant),
          Optional.ofNullable(doc.getString("exam_origin")).map(ExamOrigin::valueOf)));
    }
    return labels;
  }

  @Override
  public String describe() {
    return targetName + "." + ISSUE_LABEL;
  }
}
