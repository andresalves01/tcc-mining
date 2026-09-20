package app.pipeline.mongo;

import app.cost.Contribution;
import app.cost.ContributionScan;
import app.cost.port.ContributionSource;
import app.pipeline.port.Progress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Builds the ledger from {@code file_action} and {@code commit}.
 *
 * <p><b>Why two passes and not an aggregation.</b> The volume of a commit lives in
 * {@code file_action}, one row per touched file; the author and date live in {@code commit}. A
 * {@code $lookup} between them would be a join over 6.2M and 452k documents with the 84 GB side as
 * the outer, on a machine whose binding constraint is 15 GB of RAM shared with the database. Read
 * separately, {@code file_action} is only 250 MB on disk and collapses to one number per commit
 * before {@code commit} is opened at all.
 *
 * <p><b>The projection is not an optimisation, it is a requirement.</b> {@code commit} averages
 * 334 KB per document because each carries a materialised {@code code_entity_states} array; the
 * fields this class needs total a few dozen bytes. Reading the collection without an inclusion
 * projection pulls 141 GB through the driver.
 *
 * <p><b>Iterating per repository</b> uses the {@code vcs_system_id} index and, more usefully, makes
 * the scan legible while it runs: 98 progress lines instead of one number climbing for half an
 * hour. The ordering is imposed afterwards in memory rather than with a {@code sort} stage — with
 * documents this large the server would need a 1.5 GB sort buffer per repository and refuse, while
 * the rows this class keeps are two orders of magnitude smaller.
 */
public final class MongoContributionSource implements ContributionSource {

  private static final String JAVA_SUFFIX = ".java";

  private static final String PROJECT = "project";
  private static final String VCS_SYSTEM = "vcs_system";
  private static final String FILE = "file";
  private static final String FILE_ACTION = "file_action";
  private static final String COMMIT = "commit";

  /** The file-action pass has no cheap total, so it reports how far it has got, not how far to go. */
  private static final int PROGRESS_EVERY = 1_000_000;

  /** The collections this source opens. Declared here so it cannot drift from
   * the queries above it; {@link app.pipeline.mongo.MongoCorpusCheck} verifies it. */
  public static final Set<String> READS = Set.of(PROJECT, VCS_SYSTEM, FILE, FILE_ACTION, COMMIT);

  private final MongoClient client;
  private final String sourceDb;
  private final Progress progress;

  public MongoContributionSource(final MongoClient client, final String sourceDb,
      final Progress progress) {
    this.client = client;
    this.sourceDb = sourceDb;
    this.progress = progress;
  }

  @Override
  public ContributionScan scan(
      final Map<String, String> canonicalByPerson, final Set<String> bots, final String pilot) {

    final MongoDatabase db = client.getDatabase(sourceDb);

    progress.phase(1, 4, "projects");
    final Map<ObjectId, String> projectNames = new HashMap<>();
    for (final Document project : db.getCollection(PROJECT).find()
        .projection(Projections.include("name"))) {
      projectNames.put(project.getObjectId("_id"), project.getString("name"));
    }
    final Map<ObjectId, Repository> repositories = new HashMap<>();
    for (final Document vcs : db.getCollection(VCS_SYSTEM).find()
        .projection(Projections.include("project_id", "url"))) {
      final ObjectId projectId = vcs.getObjectId("project_id");
      repositories.put(vcs.getObjectId("_id"), new Repository(
          vcs.getObjectId("_id").toHexString(),
          projectId == null ? null : projectId.toHexString(),
          projectNames.getOrDefault(projectId, "?")));
    }
    if (pilot != null) {
      repositories.values().removeIf(repo -> !repo.projectName().contains(pilot));
      progress.detail("pilot '%s' -> %,d repositories", pilot, repositories.size());
      if (repositories.isEmpty()) {
        throw new IllegalArgumentException("no project matches pilot '" + pilot + "'");
      }
    }
    progress.detail("%,d repositories in %,d projects",
        repositories.size(), projectNames.size());

    progress.phase(2, 4, "java files");
    final Set<ObjectId> javaFiles = new HashSet<>(1 << 20);
    long files = 0;
    for (final Document file : db.getCollection(FILE).find()
        .projection(Projections.include("path"))) {
      files++;
      final String path = file.getString("path");
      if (path != null && path.endsWith(JAVA_SUFFIX)) {
        javaFiles.add(file.getObjectId("_id"));
      }
    }
    progress.count(javaFiles.size(), files, "files are .java (%.1f%%)",
        100.0 * javaFiles.size() / Math.max(1, files));

    progress.phase(3, 4, "file actions");
    final Map<ObjectId, int[]> volumes = new HashMap<>(1 << 20);
    long fileActions = 0;
    for (final Document action : db.getCollection(FILE_ACTION).find()
        .projection(Projections.include("commit_id", "file_id", "lines_added", "lines_deleted"))) {
      fileActions++;
      final ObjectId commitId = action.getObjectId("commit_id");
      if (commitId == null) {
        continue;
      }
      final int added = intValue(action.get("lines_added"));
      final int deleted = intValue(action.get("lines_deleted"));
      final boolean isJava = javaFiles.contains(action.getObjectId("file_id"));
      final int[] volume = volumes.computeIfAbsent(commitId, ignored -> new int[6]);
      volume[0] += added;
      volume[1] += deleted;
      volume[4]++;
      if (isJava) {
        volume[2] += added;
        volume[3] += deleted;
        volume[5]++;
      }
      if (fileActions % PROGRESS_EVERY == 0) {
        progress.detail("%,d actions over %,d commits", fileActions, volumes.size());
      }
    }
    progress.detail("%,d actions over %,d commits", fileActions, volumes.size());

    progress.phase(4, 4, "commits");
    final List<Contribution> contributions = new ArrayList<>(1 << 19);
    long commitsScanned = 0;
    long merges = 0;
    long unresolved = 0;
    long withoutAction = 0;
    long bot = 0;
    int repository = 0;

    for (final Map.Entry<ObjectId, Repository> entry : repositories.entrySet()) {
      final Repository repo = entry.getValue();
      repository++;
      long here = 0;
      for (final Document commit : db.getCollection(COMMIT)
          .find(Filters.eq("vcs_system_id", entry.getKey()))
          .projection(Projections.include(
              "revision_hash", "author_id", "author_date", "parents", "vcs_system_id"))) {

        commitsScanned++;
        final List<String> parents = commit.getList("parents", String.class);
        if (parents != null && parents.size() > 1) {
          merges++;
          continue;
        }
        final ObjectId authorId = commit.getObjectId("author_id");
        final Date authoredAt = commit.getDate("author_date");
        if (authorId == null || authoredAt == null) {
          unresolved++;
          continue;
        }
        final String canonicalId = canonicalByPerson.get(authorId.toHexString());
        if (canonicalId == null) {
          unresolved++;
          continue;
        }
        final int[] volume = volumes.get(commit.getObjectId("_id"));
        if (volume == null) {
          withoutAction++;
          continue;
        }
        final boolean isBot = bots.contains(canonicalId);
        if (isBot) {
          bot++;
        }
        contributions.add(new Contribution(
            commit.getObjectId("_id").toHexString(),
            commit.getString("revision_hash"),
            repo.projectId(),
            repo.projectName(),
            repo.vcsSystemId(),
            authorId.toHexString(),
            canonicalId,
            isBot,
            authoredAt.toInstant(),
            volume[0], volume[1], volume[2], volume[3], volume[4], volume[5]));
        here++;
      }
      progress.item(repository, repositories.size(), repo.projectName(),
          "%,7d contributions", here);
    }

    contributions.sort(Comparator
        .comparing(Contribution::projectName)
        .thenComparing(Contribution::authoredAt));

    return new ContributionScan(
        contributions, commitsScanned, merges, unresolved, fileActions, withoutAction, bot);
  }

  /** {@code lines_added} is absent on binary actions and may arrive as any BSON number. */
  private static int intValue(final Object raw) {
    return raw instanceof Number number ? number.intValue() : 0;
  }

  private record Repository(String vcsSystemId, String projectId, String projectName) {}
}
