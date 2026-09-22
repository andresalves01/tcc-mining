package app.pipeline.mongo;

import app.cost.Contribution;
import app.cost.port.ContributionLedger;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Who was present in a project, read from pull requests rather than from commits.
 *
 * <p>Answers only the presence half of {@link app.features.AsOfLedger}: which humans were active in
 * a project inside a window. It is a {@link ContributionLedger} because that is the shape that half
 * consumes -- a row with a person, a project and an instant -- not because any of this is a
 * contribution ledger in the accounting sense. The volume fields are zero and are never read: the
 * project index inside {@code AsOfLedger} uses {@code canonicalId}, {@code projectName} and
 * {@code authoredAt} and nothing else.
 *
 * <p><b>Why this exists.</b> Git history in this corpus is mined to a median last commit of
 * 2018-12-25 per project, while pull request records run to 2021. Reading presence from the commit
 * ledger for a task created in 2020 reports an empty team for every project -- not because the
 * project went quiet, but because the corpus stopped watching. Measured over the pull-request
 * labels: 83.6% of the test partition had an empty active team when presence came from commits,
 * and 0.8% when it came from pull requests.
 *
 * <p><b>Opening a pull request counts, merging it is not required.</b> Presence is about being
 * there, the same way an unmerged commit still puts its author in the project that week. Requiring
 * a merge would make presence a property of someone else's decision.
 *
 * <p><b>What it undercounts.</b> A creator whose identity does not resolve to a canonical person is
 * dropped, which is 58.7% of pull requests in this corpus. The team this reports is therefore
 * "those present whose identity is recoverable", never "those present". It is an undercount with a
 * known direction, not an estimate.
 *
 * <p>Read-only: {@link #save} refuses rather than pretending, because nothing should ever write a
 * derived presence index back over a mined ledger.
 */
public final class MongoPullRequestPresence implements ContributionLedger {

  public static final Set<String> READS =
      Set.of("pull_request", "pull_request_system", "project");

  private static final String PULL_REQUEST = "pull_request";
  private static final String PULL_REQUEST_SYSTEM = "pull_request_system";
  private static final String PROJECT = "project";
  private static final String PERSON_CANONICAL = "person_canonical";

  private final MongoDatabase source;
  private final MongoDatabase ledger;
  private final String sourceName;

  public MongoPullRequestPresence(
      final MongoClient client, final String sourceDb, final String ledgerDb) {
    this.source = client.getDatabase(sourceDb);
    this.ledger = client.getDatabase(ledgerDb);
    this.sourceName = sourceDb;
  }

  @Override
  public List<Contribution> readAll() {
    final Map<String, String> canonical = new HashMap<>(1 << 14);
    for (final Document c : ledger.getCollection(PERSON_CANONICAL).find()
        .projection(Projections.include("canonical_id"))) {
      final ObjectId to = c.getObjectId("canonical_id");
      if (to != null) {
        canonical.put(c.getObjectId("_id").toHexString(), to.toHexString());
      }
    }

    final Map<String, String> projectNameById = new HashMap<>(1 << 8);
    for (final Document p : source.getCollection(PROJECT).find()
        .projection(Projections.include("name"))) {
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

    final List<Contribution> rows = new ArrayList<>(1 << 15);
    for (final Document pr : source.getCollection(PULL_REQUEST).find()
        .projection(Projections.include("pull_request_system_id", "created_at", "creator_id"))) {
      final ObjectId systemId = pr.getObjectId("pull_request_system_id");
      final String project = systemId == null ? null : projectBySystem.get(systemId.toHexString());
      final ObjectId creator = pr.getObjectId("creator_id");
      if (project == null || creator == null || pr.getDate("created_at") == null) {
        continue;
      }
      final String person = canonical.get(creator.toHexString());
      if (person == null) {
        continue; // unresolvable identity: an undercount, declared in this class's javadoc
      }
      rows.add(new Contribution(
          pr.getObjectId("_id").toHexString(), null, null, project, null,
          creator.toHexString(), person, false, pr.getDate("created_at").toInstant(),
          0, 0, 0, 0, 0, 0));
    }
    return rows;
  }

  @Override
  public long count() {
    return source.getCollection(PULL_REQUEST).countDocuments();
  }

  @Override
  public long save(final Collection<Contribution> contributions) {
    throw new UnsupportedOperationException(
        "presence is derived from pull requests on every read and is never stored");
  }

  @Override
  public String describe() {
    return sourceName + "." + PULL_REQUEST + " (presenca por abertura de PR)";
  }
}
