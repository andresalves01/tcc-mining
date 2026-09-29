package app.pipeline.mongo;

import app.paper5.Artigo4Correction;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Loads what {@link Artigo4Correction} computes from, read-only: every pull request with its
 * events, commits, first review, first outside comment and files; the contribution ledger with the
 * instant each commit reached the repository; and the previous paper's units. Only {@code find}.
 */
public final class MongoArtigo4Tables implements app.paper5.port.Artigo4TableSource {

  private final MongoDatabase source;
  private final MongoDatabase ledger;
  private final MongoDatabase units;
  private final Progress progress;

  public MongoArtigo4Tables(final MongoClient client, final String sourceDb,
      final String ledgerDb, final String unitsDb, final Progress progress) {
    this.source = client.getDatabase(sourceDb);
    this.ledger = client.getDatabase(ledgerDb);
    this.units = client.getDatabase(unitsDb);
    this.progress = progress;
  }

  private static long at(final Date d) {
    return d == null ? Artigo4Correction.NONE_VALUE : d.getTime();
  }

  @Override
  public String describe() {
    return "SOURCE_DB (pull requests, commits), LEDGER_DB (developer_contribution, "
        + "person_canonical), UNITS_DB (issue_label) -- somente leitura";
  }

  @Override
  public Artigo4Correction.Inputs load() {
    final long none = Artigo4Correction.NONE_VALUE;
    final Map<String, String> names = new HashMap<>();
    source.getCollection("project").find().projection(Projections.include("name"))
        .forEach(p -> names.put(p.getObjectId("_id").toHexString(), p.getString("name")));
    final Map<String, String> projectBySystem = new HashMap<>();
    source.getCollection("pull_request_system").find().projection(
        Projections.include("project_id")).forEach(s -> {
          final ObjectId pid = s.getObjectId("project_id");
          final String name = pid == null ? null : names.get(pid.toHexString());
          if (name != null) {
            projectBySystem.put(s.getObjectId("_id").toHexString(), name);
          }
        });
    final Map<String, String> canonical = new HashMap<>(1 << 14);
    ledger.getCollection("person_canonical").find().projection(
        Projections.include("canonical_id")).forEach(c -> {
          final ObjectId to = c.getObjectId("canonical_id");
          if (to != null) {
            canonical.put(c.getObjectId("_id").toHexString(), to.toHexString());
          }
        });

    progress.detail("%s", "eventos de merge e fechamento");
    final Map<String, Long> merged = new HashMap<>(1 << 16);
    final Map<String, Long> closed = new HashMap<>(1 << 16);
    for (final Document e : source.getCollection("pull_request_event")
        .find(Filters.in("event_type", "merged", "closed"))
        .projection(Projections.include("pull_request_id", "created_at", "event_type"))) {
      final ObjectId pr = e.getObjectId("pull_request_id");
      final Date d = e.getDate("created_at");
      if (pr == null || d == null) {
        continue;
      }
      final String id = pr.toHexString();
      closed.merge(id, d.getTime(), Math::min);
      if ("merged".equals(e.getString("event_type"))) {
        merged.merge(id, d.getTime(), Math::min);
      }
    }

    progress.detail("%s", "commits dos pull requests");
    final Map<String, Set<String>> authorsByPr = new HashMap<>(1 << 16);
    final Map<String, Long> landing = new HashMap<>(1 << 18);
    for (final Document c : source.getCollection("pull_request_commit").find()
        .projection(Projections.include("pull_request_id", "author_id", "commit_sha"))) {
      final ObjectId pr = c.getObjectId("pull_request_id");
      if (pr == null) {
        continue;
      }
      final String id = pr.toHexString();
      final ObjectId author = c.getObjectId("author_id");
      if (author != null) {
        authorsByPr.computeIfAbsent(id, k -> new HashSet<>(4)).add(author.toHexString());
      }
      final String sha = c.getString("commit_sha");
      final Long m = merged.get(id);
      if (sha != null && m != null) {
        landing.merge(sha, m, Math::min);
      }
    }

    progress.detail("%s", "revisoes e comentarios");
    final Map<String, Long> firstReview = new HashMap<>(1 << 16);
    for (final Document r : source.getCollection("pull_request_review").find()
        .projection(Projections.include("pull_request_id", "submitted_at"))) {
      final Date d = r.getDate("submitted_at");
      if (d != null && r.getObjectId("pull_request_id") != null) {
        firstReview.merge(r.getObjectId("pull_request_id").toHexString(), d.getTime(), Math::min);
      }
    }
    final Map<String, Long> firstComment = new HashMap<>(1 << 16);
    for (final Document c : source.getCollection("pull_request_comment").find()
        .projection(Projections.include("pull_request_id", "created_at", "author_id"))) {
      final Date d = c.getDate("created_at");
      final ObjectId pr = c.getObjectId("pull_request_id");
      if (d == null || pr == null) {
        continue;
      }
      final String id = pr.toHexString();
      final ObjectId who = c.getObjectId("author_id");
      final Set<String> authors = authorsByPr.get(id);
      if (who != null && authors != null && authors.contains(who.toHexString())) {
        continue;
      }
      firstComment.merge(id, d.getTime(), Math::min);
    }

    progress.detail("%s", "arquivos");
    final Map<String, List<String>> paths = new HashMap<>(1 << 16);
    for (final Document f : source.getCollection("pull_request_file").find()
        .projection(Projections.include("pull_request_id", "path"))) {
      final String path = f.getString("path");
      if (path != null && f.getObjectId("pull_request_id") != null) {
        paths.computeIfAbsent(f.getObjectId("pull_request_id").toHexString(),
            k -> new ArrayList<>(8)).add(path);
      }
    }

    progress.detail("%s", "pull requests");
    final List<Artigo4Correction.Pr> prs = new ArrayList<>(1 << 16);
    for (final Document pr : source.getCollection("pull_request").find().projection(
        Projections.include("pull_request_system_id", "created_at", "creator_id"))) {
      final ObjectId system = pr.getObjectId("pull_request_system_id");
      final String project = system == null ? null : projectBySystem.get(system.toHexString());
      final Date opened = pr.getDate("created_at");
      if (project == null || opened == null) {
        continue;
      }
      final String id = pr.getObjectId("_id").toHexString();
      final ObjectId creator = pr.getObjectId("creator_id");
      final String creatorId = creator == null ? null : creator.toHexString();
      final Set<String> authors = authorsByPr.get(id);
      prs.add(new Artigo4Correction.Pr(id, project, creatorId,
          creatorId == null ? null : canonical.get(creatorId), opened.getTime(),
          merged.getOrDefault(id, none), closed.getOrDefault(id, none),
          authors != null && authors.size() == 1 ? authors.iterator().next() : null,
          firstReview.getOrDefault(id, none), firstComment.getOrDefault(id, none),
          paths.getOrDefault(id, List.of())));
    }

    progress.detail("%s", "livro de contribuicoes");
    final List<Document> rows = new ArrayList<>(1 << 19);
    final Set<String> shas = new HashSet<>(1 << 19);
    for (final Document d : ledger.getCollection("developer_contribution").find().projection(
        Projections.include("revision_hash", "canonical_id", "project", "authored_at",
            "java_lines_added", "java_lines_deleted", "is_bot"))) {
      rows.add(d);
      if (d.getString("revision_hash") != null) {
        shas.add(d.getString("revision_hash"));
      }
    }
    progress.detail("%s", "data de cada commit no repositorio (commit.committer_date)");
    final Map<String, Long> committed = new HashMap<>(1 << 19);
    for (final Document c : source.getCollection("commit").find()
        .projection(Projections.include("revision_hash", "committer_date"))) {
      final String sha = c.getString("revision_hash");
      final Date d = c.getDate("committer_date");
      if (sha != null && d != null && shas.contains(sha)) {
        committed.merge(sha, d.getTime(), Math::min);
      }
    }
    final List<Artigo4Correction.Commit> commits = new ArrayList<>(rows.size());
    int inPr = 0;
    int byCommitter = 0;
    int byAuthoring = 0;
    for (final Document d : rows) {
      final Date authoredAt = d.getDate("authored_at");
      final ObjectId who = d.getObjectId("canonical_id");
      if (authoredAt == null || who == null) {
        continue;
      }
      final long authored = authoredAt.getTime();
      final String sha = d.getString("revision_hash");
      final long visible;
      if (sha != null && landing.containsKey(sha)) {
        visible = Math.max(authored, landing.get(sha));
        inPr++;
      } else if (sha != null && committed.containsKey(sha)) {
        visible = Math.max(authored, committed.get(sha));
        byCommitter++;
      } else {
        visible = authored;
        byAuthoring++;
      }
      commits.add(new Artigo4Correction.Commit(who.toHexString(), d.getString("project"),
          authored, visible, number(d, "java_lines_added") + number(d, "java_lines_deleted"),
          Boolean.TRUE.equals(d.getBoolean("is_bot"))));
    }

    final List<Artigo4Correction.Unit> list = new ArrayList<>(10_000);
    final Map<String, long[]> interval = new HashMap<>(20_000);
    for (final Document u : units.getCollection("issue_label").find().projection(
        Projections.include("project", "canonical_id", "created_at", "resolved_at"))) {
      final String id = u.getObjectId("_id").toHexString();
      list.add(new Artigo4Correction.Unit(id, u.getString("project"),
          u.getObjectId("canonical_id").toHexString(), u.getDate("created_at").getTime()));
      interval.put(id, new long[] {u.getDate("created_at").getTime(),
          u.getDate("resolved_at").getTime()});
    }
    return new Artigo4Correction.Inputs(prs, commits, list, interval, inPr, byCommitter,
        byAuthoring);
  }

  private static long number(final Document d, final String field) {
    final Object v = d.get(field);
    return v instanceof Number n ? n.longValue() : 0L;
  }
}
