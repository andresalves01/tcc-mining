package app.pipeline.mongo;

import app.labels.ChangedMethod;
import app.labels.ExamOrigin;
import app.labels.IssueLabel;
import app.pullrequests.port.UnitSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Reads the frozen merged-pull-request units, and joins each one's creator from the source corpus.
 *
 * <p>Two databases, both read-only: {@code UNITS_DB} holds {@code issue_label} as the earlier pull-request study
 * pipeline left it, and {@code SOURCE_DB} holds the raw corpus. Nothing in this class writes, and
 * the port it implements offers no way to.
 *
 * <p>The label mapping repeats {@link MongoLabelStore#readAll()} rather than calling it, because
 * calling it would mean holding a {@code LabelStore} -- the type whose {@code save} drops the
 * collection. Duplicating a dozen lines of field reads is the cheaper risk.
 */
public final class MongoUnitSource implements UnitSource {

  public static final Set<String> READS = Set.of("pull_request");

  private static final String ISSUE_LABEL = "issue_label";
  private static final String PULL_REQUEST = "pull_request";
  private static final int BATCH = 2_000;

  private final MongoDatabase units;
  private final MongoDatabase source;
  private final String unitsName;

  public MongoUnitSource(final MongoClient client, final String unitsDb, final String sourceDb) {
    this.units = client.getDatabase(unitsDb);
    this.source = client.getDatabase(sourceDb);
    this.unitsName = unitsDb;
  }

  @Override
  public List<IssueLabel> readAll() {
    final List<IssueLabel> labels = new ArrayList<>(1 << 14);
    for (final Document doc : units.getCollection(ISSUE_LABEL).find()) {
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
          List.of(new ChangedMethod("(nao lido por este estudo)", 0.0, 0.0)),
          Optional.ofNullable(doc.getDate("examined_at")).map(Date::toInstant),
          Optional.ofNullable(doc.getString("exam_origin")).map(ExamOrigin::valueOf)));
    }
    return labels;
  }

  @Override
  public Map<String, String> creatorByUnit(final Collection<String> unitIds) {
    final Map<String, String> creators = new HashMap<>(unitIds.size() * 2);
    final List<ObjectId> all = unitIds.stream().map(ObjectId::new).toList();
    for (int from = 0; from < all.size(); from += BATCH) {
      final List<ObjectId> batch = all.subList(from, Math.min(from + BATCH, all.size()));
      source.getCollection(PULL_REQUEST)
          .find(Filters.in("_id", batch))
          .projection(Projections.include("creator_id"))
          .forEach(doc -> {
            final ObjectId creator = doc.getObjectId("creator_id");
            if (creator != null) {
              creators.put(doc.getObjectId("_id").toHexString(), creator.toHexString());
            }
          });
    }
    return creators;
  }

  @Override
  public String describe() {
    return unitsName + "." + ISSUE_LABEL + " (somente leitura)";
  }
}
