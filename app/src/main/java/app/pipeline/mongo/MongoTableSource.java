package app.pipeline.mongo;

import app.pullrequests.Tables;
import app.pullrequests.port.TableSource;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Loads the corpus into the event tables the replication computes from.
 *
 * <p>One pass per collection, everything into memory: 52.478 pull requests, 18.786 merge events,
 * 188.500 comments and 127.618 reviews are small next to the corpus they come from, and holding
 * them means every temporal rule is applied once, in {@code app.pullrequests}, where a test can reach it.
 *
 * <p><b>Nothing here filters by {@code t0}.</b> That is deliberate and is what makes the
 * time-travel test possible at all.
 */
public final class MongoTableSource implements TableSource {

  private static final Set<String> READS = Set.of(
      "pull_request", "pull_request_system", "project", "pull_request_event",
      "pull_request_comment", "pull_request_review", "commit", "vcs_system", "issue",
      "issue_system", "pull_request_file", "pull_request_commit", "file", "file_action");

  /** Yu's convention, which these projects barely use: 6,7% of the population. */
  private static final Pattern HASH_REFERENCE = Pattern.compile("#\\d+");

  /** The convention they do use -- a Jira key -- present in 72,6%. Decision of section 1.5. */
  private static final Pattern JIRA_REFERENCE = Pattern.compile("\\b[A-Z][A-Z0-9]{1,}-\\d+\\b");

  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_-]+");

  /** The same rule the validation probes used, so the counted 48,9% stays comparable. */
  private static final Pattern TEST_PATH =
      Pattern.compile("(^|/)test|test(s)?/", Pattern.CASE_INSENSITIVE);

  /**
   * Exactly JavaScript's {@code \s}, spelled out.
   *
   * <p>Java's {@code \s} is ASCII-only and its Unicode form includes U+0085, which JavaScript's
   * does not; neither matches. The reference extraction counted words in the Mongo shell, and
   * eleven pull requests of the population contain a non-breaking or ideographic space, so the two
   * definitions disagree on those eleven and only those. Writing the class out means the port
   * reproduces the reference rather than approximating it.
   */
  private static final Pattern WHITESPACE = Pattern.compile(
      "[\t\n\u000B\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+");

  private static final int BATCH = 2_000;

  private final MongoDatabase source;
  private final String sourceName;
  private final Progress progress;

  public MongoTableSource(final MongoClient client, final String sourceDb,
      final Progress progress) {

    this.source = client.getDatabase(sourceDb);
    this.sourceName = sourceDb;
    this.progress = progress;
  }

  @Override
  public Tables load(final Collection<String> populationIds) {
    final Tables.Builder builder = new Tables.Builder();

    progress.phase(1, 5, "reading pull requests and resolving their projects");
    final Map<String, String> projectBySystem = projectBySystem();
    long pullRequests = 0;
    for (final Document doc : source.getCollection("pull_request").find()
        .projection(Projections.include(
            "creator_id", "created_at", "pull_request_system_id"))) {

      final ObjectId systemId = doc.getObjectId("pull_request_system_id");
      final String project = systemId == null ? "?"
          : projectBySystem.getOrDefault(systemId.toHexString(), "?");
      final ObjectId creator = doc.getObjectId("creator_id");
      final Date opened = doc.getDate("created_at");
      builder.pullRequest(doc.getObjectId("_id").toHexString(),
          creator == null ? null : creator.toHexString(),
          opened == null ? null : opened.getTime(), project);
      pullRequests++;
    }
    progress.detail("%,d pull requests, %,d projects", pullRequests, projectBySystem.size());

    progress.phase(2, 5, "reading merge and close events");
    long merges = 0;
    long closes = 0;
    for (final Document doc : source.getCollection("pull_request_event")
        .find(Filters.in("event_type", "merged", "closed"))
        .projection(Projections.include(
            "pull_request_id", "author_id", "created_at", "event_type"))) {

      final ObjectId prId = doc.getObjectId("pull_request_id");
      final Date at = doc.getDate("created_at");
      if (prId == null || at == null) {
        continue;
      }
      final String pr = prId.toHexString();
      // The first close, whichever kind closed it, is what decides "still open at t0". A merge
      // closes a pull request too, so both event types feed the same earliest-close table.
      builder.closed(pr, at.getTime());
      closes++;
      if ("merged".equals(doc.getString("event_type"))) {
        final ObjectId actor = doc.getObjectId("author_id");
        // Indexed by the project of the pull request, because the event itself names no project.
        final String project = builder.projectOf(pr);
        builder.merge(project == null ? "?" : project, at.getTime(),
            actor == null ? null : actor.toHexString(), pr);
        merges++;
      }
    }
    progress.detail("%,d merges, %,d closing events", merges, closes);

    progress.phase(3, 5, "reading comments and formal reviews");
    long contacts = 0;
    for (final Document doc : source.getCollection("pull_request_comment").find()
        .projection(Projections.include("pull_request_id", "author_id", "created_at"))) {

      contacts += contact(builder, doc, "author_id", "created_at");
    }
    for (final Document doc : source.getCollection("pull_request_review").find()
        .projection(Projections.include("pull_request_id", "creator_id", "submitted_at"))) {

      contacts += contact(builder, doc, "creator_id", "submitted_at");
    }
    // pull_request_review_comment stays out by specification: 31,8% of it has no author and
    // 76.595 rows have no instant, so a ninety-day window over it could not be defended.
    progress.detail("%,d contacts kept (self-authored and undated rows dropped)", contacts);

    progress.phase(4, 5, "measuring the title and body of the population");
    text(builder, populationIds);

    progress.phase(5, 7, "finding when each project first showed activity");
    final int origins = origins(builder);
    progress.detail("%,d projects with a first commit or a first issue", origins);

    progress.phase(6, 7, "reading the consolidated diff of the population");
    final Set<String> interesting = diffs(builder, populationIds);
    progress.detail("%,d distinct (project, path) pairs the population touches", interesting.size());

    progress.phase(7, 7, "reading the commit history of those paths");
    activity(builder, interesting);

    return builder.build();
  }

  /**
   * The consolidated diff of each unit, and which paths it touches.
   *
   * <p>The final state, and the source of five of the six technical constructs. Read here in one
   * pass keyed on the population so the million rows of {@code pull_request_file} are never all
   * held at once.
   *
   * @return the {@code project\0path} keys the hotness window will need
   */
  private Set<String> diffs(final Tables.Builder builder, final Collection<String> populationIds) {
    final Map<String, Integer> commitCount = new HashMap<>(populationIds.size() * 2);
    for (final Document doc : source.getCollection("pull_request_commit").find()
        .projection(Projections.include("pull_request_id"))) {

      final ObjectId prId = doc.getObjectId("pull_request_id");
      if (prId != null) {
        commitCount.merge(prId.toHexString(), 1, Integer::sum);
      }
    }

    final Set<String> interesting = new HashSet<>(1 << 16);
    final List<ObjectId> ids = populationIds.stream().map(ObjectId::new).toList();
    for (int from = 0; from < ids.size(); from += BATCH) {
      final List<ObjectId> batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
      final Map<String, List<Document>> byPr = new HashMap<>(batch.size() * 2);
      for (final Document doc : source.getCollection("pull_request_file")
          .find(Filters.in("pull_request_id", batch))
          .projection(Projections.include(
              "pull_request_id", "path", "additions", "deletions"))) {

        byPr.computeIfAbsent(doc.getObjectId("pull_request_id").toHexString(),
            key -> new ArrayList<>()).add(doc);
      }
      for (final ObjectId id : batch) {
        final String pr = id.toHexString();
        final String project = builder.projectOf(pr);
        final List<Document> files = byPr.getOrDefault(pr, List.of());
        final Set<String> paths = new LinkedHashSet<>(files.size() * 2);
        long additions = 0;
        long deletions = 0;
        boolean test = false;
        for (final Document file : files) {
          final String path = file.getString("path");
          if (path != null) {
            paths.add(path);
            if (TEST_PATH.matcher(path).find()) {
              test = true;
            }
            if (project != null) {
              interesting.add(project + '\u0000' + path);
            }
          }
          additions += number(file, "additions");
          deletions += number(file, "deletions");
        }
        builder.diff(pr, new Tables.Diff(paths.size(), additions, deletions, test,
            commitCount.getOrDefault(pr, 0)), new ArrayList<>(paths));
      }
    }
    return interesting;
  }

  private static long number(final Document doc, final String field) {
    final Object value = doc.get(field);
    return value instanceof Number ? ((Number) value).longValue() : 0L;
  }

  /**
   * How much each touched path moved, and how much the project moved, both as dated events.
   *
   * <p>Only the paths the population touches are loaded -- 48.631 of the corpus's 952.760 files --
   * so {@code file_action} is reached through its {@code file_id} index in batches rather than
   * scanned whole. Six million rows would otherwise cross the wire to be mostly discarded.
   */
  private void activity(final Tables.Builder builder, final Set<String> interesting) {
    final Map<String, String> projectByVcs = new HashMap<>(1 << 8);
    final Map<String, String> names = projectNames();
    for (final Document doc : source.getCollection("vcs_system").find()
        .projection(Projections.include("project_id"))) {

      final ObjectId projectId = doc.getObjectId("project_id");
      final String name = projectId == null ? null : names.get(projectId.toHexString());
      if (name != null) {
        projectByVcs.put(doc.getObjectId("_id").toHexString(), name);
      }
    }

    // A dense index per commit, so a commit recorded twice against one file collapses to one.
    final Map<String, long[]> commitFacts = new HashMap<>(1 << 19);
    int index = 0;
    for (final Document doc : source.getCollection("commit").find()
        .projection(Projections.include("vcs_system_id", "committer_date"))) {

      final ObjectId vcs = doc.getObjectId("vcs_system_id");
      final Date at = doc.getDate("committer_date");
      if (vcs == null || at == null) {
        continue;
      }
      final String project = projectByVcs.get(vcs.toHexString());
      if (project == null) {
        continue;
      }
      builder.commit(project, at.getTime());
      commitFacts.put(doc.getObjectId("_id").toHexString(), new long[] {at.getTime(), index++});
    }
    progress.detail("%,d commits indexed across %,d projects",
        commitFacts.size(), projectByVcs.size());

    final Map<String, String[]> kept = new HashMap<>(1 << 16);
    for (final Document doc : source.getCollection("file").find()
        .projection(Projections.include("vcs_system_id", "path"))) {

      final ObjectId vcs = doc.getObjectId("vcs_system_id");
      final String path = doc.getString("path");
      final String project = vcs == null ? null : projectByVcs.get(vcs.toHexString());
      if (project == null || path == null || !interesting.contains(project + '\u0000' + path)) {
        continue;
      }
      kept.put(doc.getObjectId("_id").toHexString(), new String[] {project, path});
    }
    progress.detail("%,d files of interest", kept.size());

    final List<ObjectId> fileIds = kept.keySet().stream().map(ObjectId::new).toList();
    long actions = 0;
    for (int from = 0; from < fileIds.size(); from += BATCH) {
      final List<ObjectId> batch = fileIds.subList(from, Math.min(from + BATCH, fileIds.size()));
      for (final Document doc : source.getCollection("file_action")
          .find(Filters.in("file_id", batch))
          .projection(Projections.include("file_id", "commit_id"))) {

        final ObjectId fileId = doc.getObjectId("file_id");
        final ObjectId commitId = doc.getObjectId("commit_id");
        if (fileId == null || commitId == null) {
          continue;
        }
        final String[] where = kept.get(fileId.toHexString());
        final long[] when = commitFacts.get(commitId.toHexString());
        if (where == null || when == null) {
          continue;
        }
        builder.touched(where[0], where[1], when[0], (int) when[1]);
        actions++;
      }
    }
    progress.detail("%,d file actions on those paths", actions);
  }

  private static int contact(final Tables.Builder builder, final Document doc,
      final String authorField, final String instantField) {

    final ObjectId prId = doc.getObjectId("pull_request_id");
    final ObjectId author = doc.getObjectId(authorField);
    final Date at = doc.getDate(instantField);
    if (prId == null) {
      return 0;
    }
    return builder.contact(prId.toHexString(), author == null ? null : author.toHexString(),
        at == null ? null : at.getTime()) ? 1 : 0;
  }

  /**
   * Title and body, reduced to four numbers at read time.
   *
   * <p>Counted here and not stored as text, for the same reason the validation probes did it:
   * nothing downstream needs the prose, and a pipeline that carries it around invites somebody to
   * start mining it.
   */
  private void text(final Tables.Builder builder, final Collection<String> populationIds) {
    final List<ObjectId> ids = populationIds.stream().map(ObjectId::new).toList();
    for (int from = 0; from < ids.size(); from += BATCH) {
      final List<ObjectId> batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
      for (final Document doc : source.getCollection("pull_request")
          .find(Filters.in("_id", batch))
          .projection(Projections.include("title", "description"))) {

        final String title = doc.getString("title") == null ? "" : doc.getString("title");
        final String body = doc.getString("description") == null ? "" : doc.getString("description");
        final String whole = title + " " + body;
        int words = 0;
        for (final String word : WHITESPACE.split(whole)) {
          if (!word.isEmpty()) {
            words++;
          }
        }
        builder.text(doc.getObjectId("_id").toHexString(), new Tables.Text(words,
            HASH_REFERENCE.matcher(whole).find(),
            JIRA_REFERENCE.matcher(whole).find(),
            MENTION.matcher(whole).find()));
      }
    }
  }

  /**
   * The first commit and the first issue of each project, as two separate events.
   *
   * <p>Two, not one, because the age is {@code min} of them and the truncated view has to be able
   * to remove either. The order between them is not a detail: in 24 of the 77 projects the first
   * issue precedes the first commit, which is why the earlier proxy -- the project's first pull
   * request -- was abandoned.
   */
  private int origins(final Tables.Builder builder) {
    int found = 0;
    found += earliest(builder, "vcs_system", "commit", "vcs_system_id", "committer_date", "commit");
    found += earliest(builder, "issue_system", "issue", "issue_system_id", "created_at", "issue");
    return found;
  }

  private int earliest(final Tables.Builder builder, final String systemCollection,
      final String collection, final String systemField, final String instantField,
      final String label) {

    final Map<String, String> projectBySystem = new HashMap<>(1 << 8);
    final Map<String, String> nameById = projectNames();
    for (final Document doc : source.getCollection(systemCollection).find()
        .projection(Projections.include("project_id"))) {

      final ObjectId projectId = doc.getObjectId("project_id");
      final String name = projectId == null ? null : nameById.get(projectId.toHexString());
      if (name != null) {
        projectBySystem.put(doc.getObjectId("_id").toHexString(), name);
      }
    }
    // $min by system rather than a sort per project: without a compound index the sort would read
    // the whole collection once per project, and commit alone has 452.286 documents.
    int found = 0;
    for (final Document doc : source.getCollection(collection).aggregate(List.of(
        Aggregates.group("$" + systemField, Accumulators.min("first", "$" + instantField))))) {

      final Object systemId = doc.get("_id");
      final Date first = doc.getDate("first");
      if (!(systemId instanceof ObjectId) || first == null) {
        continue;
      }
      final String project = projectBySystem.get(((ObjectId) systemId).toHexString());
      if (project != null) {
        builder.origin(project, first.getTime(), label);
        found++;
      }
    }
    return found;
  }

  private Map<String, String> projectNames() {
    final Map<String, String> names = new HashMap<>(1 << 8);
    for (final Document doc : source.getCollection("project").find()
        .projection(Projections.include("name"))) {

      names.put(doc.getObjectId("_id").toHexString(), doc.getString("name"));
    }
    return names;
  }

  private Map<String, String> projectBySystem() {
    final Map<String, String> names = projectNames();
    final Map<String, String> bySystem = new HashMap<>(1 << 8);
    for (final Document doc : source.getCollection("pull_request_system").find()
        .projection(Projections.include("project_id"))) {

      final ObjectId projectId = doc.getObjectId("project_id");
      final String name = projectId == null ? null : names.get(projectId.toHexString());
      if (name != null) {
        bySystem.put(doc.getObjectId("_id").toHexString(), name);
      }
    }
    return bySystem;
  }

  @Override
  public Set<String> reads() {
    return new HashSet<>(READS);
  }

  @Override
  public String describe() {
    return sourceName + " (somente leitura)";
  }
}
