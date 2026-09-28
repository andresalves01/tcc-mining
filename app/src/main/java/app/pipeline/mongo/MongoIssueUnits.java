package app.pipeline.mongo;

import app.labels.IssueLabel;
import app.paper6.Extractor;
import app.paper6.port.IssueUnitSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
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
 * Reads the issue labels, their reporters and the author's stored columns. Writes nothing.
 *
 * <p>{@code issue_label} and {@code issue_features} come from the thesis database, which this study
 * treats as frozen; {@code issue} comes from the corpus. Every call here is a {@code find}.
 */
public final class MongoIssueUnits implements IssueUnitSource {

  private final MongoDatabase units;
  private final MongoDatabase source;
  private final String describe;

  public MongoIssueUnits(final MongoClient client, final String unitsDb, final String sourceDb) {
    this.units = client.getDatabase(unitsDb);
    this.source = client.getDatabase(sourceDb);
    this.describe = unitsDb + ".issue_label + " + unitsDb + ".issue_features + " + sourceDb
        + ".issue (somente leitura)";
  }

  @Override
  public List<Label> readAll() {
    final List<Document> docs = new ArrayList<>(60_000);
    final Set<ObjectId> ids = new HashSet<>();
    for (final Document d : units.getCollection("issue_label").find().projection(
        Projections.include("project", "created_at", "resolved_at", "canonical_id",
            "first_commit_at", "commits", "java_lines_added", "java_lines_deleted", "split"))) {
      docs.add(d);
      ids.add(d.getObjectId("_id"));
    }
    final Map<ObjectId, String[]> people = new HashMap<>(ids.size() * 2);
    for (final Document issue : source.getCollection("issue").find().projection(
        Projections.include("reporter_id", "creator_id", "external_id"))) {
      final ObjectId id = issue.getObjectId("_id");
      if (!ids.contains(id)) {
        continue;
      }
      final ObjectId reporter = issue.getObjectId("reporter_id");
      final ObjectId creator = issue.getObjectId("creator_id");
      people.put(id, new String[] {
          reporter != null ? reporter.toHexString() : creator != null ? creator.toHexString() : "",
          reporter == null && creator != null ? "c" : "",
          issue.getString("external_id") == null ? "" : issue.getString("external_id")});
    }
    final List<Label> labels = new ArrayList<>(docs.size());
    for (final Document d : docs) {
      final ObjectId id = d.getObjectId("_id");
      final String[] who = people.getOrDefault(id, new String[] {"", "", ""});
      labels.add(new Label(new IssueLabel(id.toHexString(), d.getString("project"),
          d.getDate("created_at").toInstant(), d.getDate("resolved_at").toInstant(),
          d.getObjectId("canonical_id").toHexString(), d.getDate("first_commit_at").toInstant(),
          number(d, "commits").intValue(), number(d, "java_lines_added").longValue(),
          number(d, "java_lines_deleted").longValue(), List.of()),
          who[0], who[2], d.getString("split"), "c".equals(who[1])));
    }
    return labels;
  }

  @Override
  public Map<String, Map<String, Double>> ownColumns(final Collection<String> issueIds) {
    final Set<String> wanted = new HashSet<>(issueIds);
    final List<String> fields = new ArrayList<>(Extractor.OWN_STORED);
    fields.add("extra_desc_length");
    fields.add("extra_linked_issues");
    fields.add("extra_issue_type");
    final Map<String, Map<String, Double>> out = new HashMap<>(wanted.size() * 2);
    for (final Document d : units.getCollection("issue_features").find()
        .projection(Projections.include(fields))) {
      final String id = d.getObjectId("_id").toHexString();
      if (!wanted.contains(id)) {
        continue;
      }
      final Map<String, Double> values = new HashMap<>();
      for (final String field : fields) {
        final Object value = d.get(field);
        if (field.equals("extra_issue_type")) {
          if (value != null) {
            values.put(field, Extractor.typeCode(value.toString()));
          }
        } else if (value instanceof Number n) {
          values.put(field, n.doubleValue());
        }
      }
      out.put(id, values);
    }
    return out;
  }

  private static Number number(final Document d, final String field) {
    final Object value = d.get(field);
    return value instanceof Number n ? n : 0;
  }

  @Override
  public String describe() {
    return describe;
  }
}
