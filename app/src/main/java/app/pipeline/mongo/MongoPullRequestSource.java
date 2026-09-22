package app.pipeline.mongo;

import app.paper4.MergedPullRequest;
import app.paper4.port.MergedPullRequestSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Reads merged pull requests from the published corpus, restricted to projects this study already
 * has issue labels for.
 *
 * <p><b>Two databases, on purpose.</b> Pull request data comes from the source corpus; which
 * projects qualify comes from the already-built {@code issue_label} in whatever database
 * {@code LEDGER_DB} names -- never {@code TARGET_DB}, which for this stage starts empty. See
 * {@link app.config.PipelineConfig#ledgerDb()}.
 *
 * <p><b>Why the outcome is opened-to-merged rather than first-commit-to-merged.</b> The natural
 * reading of "since work began" would anchor on the pull request's earliest commit. That commit's
 * timestamp is recoverable only by matching {@code pull_request_commit.commit_sha} against
 * {@code commit.revision_hash} in the mined repository, and that join fails for the majority of
 * merged pull requests -- measured over merged pull requests specifically, 61.3% without any
 * history of branch rewriting and 75.5% with it, because squash and rebase merges routinely retire
 * the original commit objects from the branch history that gets mined, while the GitHub-reported
 * metadata on {@code pull_request_commit} survives regardless. {@code pull_request.created_at} and
 * the {@code merged} event are both native to the pull request record and complete for every one of
 * them, so the outcome this source reports is opened-to-merged, not branch-creation-to-merged.
 */
public final class MongoPullRequestSource implements MergedPullRequestSource {

  /** The collections this source opens, in the source database. */
  public static final Set<String> READS = Set.of(
      "pull_request", "pull_request_event", "pull_request_commit", "pull_request_file",
      "pull_request_system", "project");

  private static final String PULL_REQUEST = "pull_request";
  private static final String PULL_REQUEST_EVENT = "pull_request_event";
  private static final String PULL_REQUEST_COMMIT = "pull_request_commit";
  private static final String PULL_REQUEST_FILE = "pull_request_file";
  private static final String PULL_REQUEST_SYSTEM = "pull_request_system";
  private static final String PROJECT = "project";
  private static final String ISSUE_LABEL = "issue_label";

  private static final String MERGED = "merged";

  private final MongoDatabase source;
  private final MongoDatabase ledger;
  private final String sourceName;
  private final String ledgerName;

  public MongoPullRequestSource(
      final MongoClient client, final String sourceDb, final String ledgerDb) {
    this.source = client.getDatabase(sourceDb);
    this.ledger = client.getDatabase(ledgerDb);
    this.sourceName = sourceDb;
    this.ledgerName = ledgerDb;
  }

  @Override
  public List<MergedPullRequest> scan() {
    final Set<String> labelledProjects = new HashSet<>(
        ledger.getCollection(ISSUE_LABEL).distinct("project", String.class).into(new ArrayList<>()));

    final Map<String, String> projectNameById = new HashMap<>(1 << 8);
    for (final Document p : source.getCollection(PROJECT).find().projection(Projections.include("name"))) {
      projectNameById.put(p.getObjectId("_id").toHexString(), p.getString("name"));
    }
    final Map<String, String> projectBySystem = new HashMap<>(1 << 8);
    for (final Document s : source.getCollection(PULL_REQUEST_SYSTEM).find()
        .projection(Projections.include("project_id"))) {
      final ObjectId projectId = s.getObjectId("project_id");
      final String name = projectId == null ? null : projectNameById.get(projectId.toHexString());
      if (name != null) {
        projectBySystem.put(s.getObjectId("_id").toHexString(), name);
      }
    }

    final Map<String, Instant> mergedAt = new HashMap<>(1 << 15);
    for (final Document e : source.getCollection(PULL_REQUEST_EVENT)
        .find(Filters.eq("event_type", MERGED))
        .projection(Projections.include("pull_request_id", "created_at"))) {
      final String prId = e.getObjectId("pull_request_id").toHexString();
      final Instant at = e.getDate("created_at").toInstant();
      mergedAt.merge(prId, at, (a, b) -> a.isBefore(b) ? a : b);
    }

    // authorId(pr) is the single distinct author across its linked commits, or null for zero or
    // several -- see MergedPullRequest's javadoc. A field the GitHub API reports on every commit
    // record directly, so this costs nothing even where the commit's own sha matches nothing mined.
    final Map<String, Set<String>> authorsByPr = new HashMap<>(1 << 15);
    final Map<String, Integer> commitCountByPr = new HashMap<>(1 << 15);
    for (final Document c : source.getCollection(PULL_REQUEST_COMMIT)
        .find().projection(Projections.include("pull_request_id", "author_id"))) {
      final String prId = c.getObjectId("pull_request_id").toHexString();
      commitCountByPr.merge(prId, 1, Integer::sum);
      final ObjectId author = c.getObjectId("author_id");
      if (author != null) {
        authorsByPr.computeIfAbsent(prId, k -> new HashSet<>(4)).add(author.toHexString());
      }
    }

    final Map<String, long[]> javaLines = new HashMap<>(1 << 15); // [added, deleted]
    for (final Document f : source.getCollection(PULL_REQUEST_FILE)
        .find().projection(Projections.include("pull_request_id", "path", "additions", "deletions"))) {
      final String path = f.getString("path");
      if (path == null || !path.endsWith(".java")) {
        continue;
      }
      final String prId = f.getObjectId("pull_request_id").toHexString();
      final long[] totals = javaLines.computeIfAbsent(prId, k -> new long[2]);
      totals[0] += numberOr(f, "additions", 0);
      totals[1] += numberOr(f, "deletions", 0);
    }

    final List<MergedPullRequest> out = new ArrayList<>(mergedAt.size());
    for (final Document pr : source.getCollection(PULL_REQUEST)
        .find().projection(Projections.include(
            "pull_request_system_id", "created_at", "description"))) {
      final String prId = pr.getObjectId("_id").toHexString();
      final Instant merged = mergedAt.get(prId);
      if (merged == null) {
        continue;
      }
      final ObjectId systemId = pr.getObjectId("pull_request_system_id");
      final String project = systemId == null ? null : projectBySystem.get(systemId.toHexString());
      if (project == null || !labelledProjects.contains(project)) {
        continue;
      }
      final Set<String> authors = authorsByPr.get(prId);
      final String author = authors != null && authors.size() == 1 ? authors.iterator().next() : null;
      final long[] lines = javaLines.getOrDefault(prId, new long[2]);
      final String description = pr.getString("description");

      out.add(new MergedPullRequest(
          prId,
          project,
          author,
          pr.getDate("created_at").toInstant(),
          merged,
          lines[0],
          lines[1],
          commitCountByPr.getOrDefault(prId, 0),
          description == null ? 0 : description.length()));
    }
    return out;
  }

  private static long numberOr(final Document doc, final String field, final long fallback) {
    final Number n = doc.get(field, Number.class);
    return n == null ? fallback : n.longValue();
  }

  @Override
  public String describe() {
    return sourceName + ".pull_request* (merged), projects from " + ledgerName + "." + ISSUE_LABEL;
  }
}
