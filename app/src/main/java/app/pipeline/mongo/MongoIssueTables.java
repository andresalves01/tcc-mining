package app.pipeline.mongo;

import app.paper6.Tables;
import app.paper6.port.IssueTableSource;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Loads every table the issue constructs read, once, from the corpus. Only {@code find} and one
 * read-only {@code aggregate}; nothing is written.
 *
 * <p>Measured volumes, so the memory cost is not a surprise: 207.400 issues, 901.872 comments,
 * 1.589.368 events. {@code file_action} is not read at all -- without a list of files at creation
 * there is no area to measure the heat of.
 */
public final class MongoIssueTables implements IssueTableSource {

  static final Set<String> RESOLVED = Set.of("Resolved", "Closed");

  /** The fields the replay undoes. Everything else in the changelog is left where it is. */
  static final List<String> FIELDS = List.of("title", "desc", "priority", "issue_type",
      "assignee_id", "labels", "components", "affects_versions", "fix_versions", "issue_links");

  private static final int BATCH = 2_000;

  private final MongoDatabase source;
  private final String name;
  private final Progress progress;

  public MongoIssueTables(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.name = sourceDb;
    this.progress = progress;
  }

  @Override
  public Tables load(final Collection<String> populationIds) {
    final Tables.Builder builder = new Tables.Builder();
    final Map<ObjectId, String> projectOfSystem = projectOfIssueSystem();

    // -- todas as issues: projeto, relator, criacao --------------------------------------------
    progress.detail("lendo %s", "issue");
    final Map<ObjectId, String> reporterOf = new HashMap<>(1 << 18);
    final Map<String, Long> firstIssue = new HashMap<>();
    int issues = 0;
    for (final Document d : source.getCollection("issue").find().projection(
        Projections.include("issue_system_id", "created_at", "reporter_id", "creator_id"))) {
      final String project = projectOfSystem.get(d.getObjectId("issue_system_id"));
      final Date created = d.getDate("created_at");
      if (project == null || created == null) {
        continue;
      }
      final ObjectId who = d.getObjectId("reporter_id") != null ? d.getObjectId("reporter_id")
          : d.getObjectId("creator_id");
      final String reporter = who == null ? null : who.toHexString();
      if (reporter != null) {
        reporterOf.put(d.getObjectId("_id"), reporter);
      }
      builder.issue(d.getObjectId("_id").toHexString(), project, reporter, created.getTime());
      firstIssue.merge(project, created.getTime(), Math::min);
      issues++;
    }
    firstIssue.forEach((project, at) -> builder.origin(project, at, "issue"));
    progress.detail("%,d issues", issues);

    // -- primeiro commit de cada projeto -------------------------------------------------------
    final Map<ObjectId, String> projectOfVcs = projectOfVcsSystem();
    for (final Document d : source.getCollection("commit").aggregate(List.of(
        Aggregates.group("$vcs_system_id", Accumulators.min("first", "$committer_date"))))
        .allowDiskUse(true)) {
      final String project = projectOfVcs.get(d.getObjectId("_id"));
      final Date first = d.getDate("first");
      if (project != null && first != null) {
        builder.origin(project, first.getTime(), "commit");
      }
    }

    // -- resolucoes: a primeira mudanca de status para Resolved ou Closed ----------------------
    progress.detail("lendo %s", "event (status)");
    int resolutions = 0;
    for (final Document d : source.getCollection("event").find(Filters.eq("status", "status"))
        .projection(Projections.include("issue_id", "created_at", "new_value", "author_id"))) {
      final Object value = d.get("new_value");
      final Date at = d.getDate("created_at");
      final ObjectId issue = d.getObjectId("issue_id");
      if (at == null || issue == null || value == null || !RESOLVED.contains(value.toString())) {
        continue;
      }
      final ObjectId author = d.getObjectId("author_id");
      builder.resolution(issue.toHexString(), at.getTime(),
          author == null ? null : author.toHexString());
      resolutions++;
    }
    progress.detail("%,d eventos de resolucao", resolutions);

    // -- comentarios ----------------------------------------------------------------------------
    progress.detail("lendo %s", "issue_comment");
    int comments = 0;
    for (final Document d : source.getCollection("issue_comment").find()
        .projection(Projections.include("issue_id", "author_id", "created_at"))) {
      final ObjectId issue = d.getObjectId("issue_id");
      final ObjectId author = d.getObjectId("author_id");
      final Date at = d.getDate("created_at");
      if (issue == null || author == null || at == null) {
        continue;
      }
      builder.comment(issue.toHexString(), reporterOf.get(issue), author.toHexString(),
          at.getTime());
      comments++;
    }
    progress.detail("%,d comentarios", comments);

    // -- a populacao: o estado final e as mudancas registradas ---------------------------------
    final List<ObjectId> ids = populationIds.stream().map(ObjectId::new).toList();
    progress.detail("lendo o estado final e o changelog de %,d issues", ids.size());
    for (int from = 0; from < ids.size(); from += BATCH) {
      final List<ObjectId> batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
      for (final Document d : source.getCollection("issue").find(Filters.in("_id", batch))
          .projection(Projections.include("title", "desc", "priority", "issue_type",
              "assignee_id", "labels", "components", "affects_versions", "fix_versions",
              "issue_links"))) {
        builder.snapshot(d.getObjectId("_id").toHexString(), snapshot(d));
      }
      for (final Document d : source.getCollection("event").find(Filters.and(
          Filters.in("issue_id", batch), Filters.in("status", FIELDS)))
          .projection(Projections.include("issue_id", "created_at", "status", "old_value",
              "new_value"))) {
        final Date at = d.getDate("created_at");
        if (at == null) {
          continue;
        }
        builder.change(d.getObjectId("issue_id").toHexString(), new Tables.Change(at.getTime(),
            d.getString("status"), text(d.get("old_value")), text(d.get("new_value"))));
      }
    }
    return builder.build();
  }

  static Tables.Snapshot snapshot(final Document d) {
    final ObjectId assignee = d.getObjectId("assignee_id");
    return new Tables.Snapshot(
        d.getString("title"),
        d.getString("desc"),
        d.getString("priority"),
        d.getString("issue_type"),
        assignee == null ? null : assignee.toHexString(),
        strings(d.get("labels")),
        size(d.get("components")),
        size(d.get("affects_versions")),
        size(d.get("fix_versions")),
        d.containsKey("issue_links") ? size(d.get("issue_links")) : -1);
  }

  /** The changelog's values as text: an id as its hex, a link as the id it points to. */
  static String text(final Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof ObjectId id) {
      return id.toHexString();
    }
    if (value instanceof Document doc) {
      final Object target = doc.get("issue_id");
      return target instanceof ObjectId id ? id.toHexString() : doc.toJson();
    }
    return value.toString();
  }

  private static List<String> strings(final Object value) {
    final List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (final Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          out.add(item.toString());
        }
      }
    }
    return out;
  }

  private static int size(final Object value) {
    return value instanceof List<?> list ? list.size() : 0;
  }

  private Map<ObjectId, String> projectNames() {
    final Map<ObjectId, String> names = new HashMap<>();
    for (final Document p : source.getCollection("project").find()
        .projection(Projections.include("name"))) {
      names.put(p.getObjectId("_id"), p.getString("name"));
    }
    return names;
  }

  /** The same resolution as the label pipeline: issue system, then project, then its name. */
  private Map<ObjectId, String> projectOfIssueSystem() {
    final Map<ObjectId, String> names = projectNames();
    final Map<ObjectId, String> out = new HashMap<>();
    for (final Document s : source.getCollection("issue_system").find()
        .projection(Projections.include("project_id"))) {
      out.put(s.getObjectId("_id"), names.get(s.getObjectId("project_id")));
    }
    return out;
  }

  private Map<ObjectId, String> projectOfVcsSystem() {
    final Map<ObjectId, String> names = projectNames();
    final Map<ObjectId, String> out = new HashMap<>();
    for (final Document s : source.getCollection("vcs_system").find()
        .projection(Projections.include("project_id"))) {
      out.put(s.getObjectId("_id"), names.get(s.getObjectId("project_id")));
    }
    return out;
  }

  @Override
  public Set<String> reads() {
    return Set.of("issue", "issue_comment", "event", "commit", "issue_system", "vcs_system",
        "project");
  }

  @Override
  public String describe() {
    return name + " (somente leitura)";
  }
}
