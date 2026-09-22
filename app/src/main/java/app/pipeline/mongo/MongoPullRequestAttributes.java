package app.pipeline.mongo;

import app.features.IssueAttributes;
import app.features.port.IssueAttributeSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * What a pull request recorded about itself when it was opened, in the shape
 * {@link app.pipeline.BuildFeaturesTask} already knows how to consume.
 *
 * <p>Only {@code descriptionLength} carries anything: a pull request has no Jira issue type, and
 * this study does not attempt to recover Jira issues mentioned in a pull request's title or body
 * from free text. Both stay absent, exactly the way {@link IssueAttributes} already represents a
 * field the corpus never recorded -- no change to that type or to the columns that read it was
 * needed for this to fall out correctly: the type indicator columns read as all-zero for a label
 * with no type, and the standardiser drops what it finds constant.
 */
public final class MongoPullRequestAttributes implements IssueAttributeSource {

  public static final Set<String> READS = Set.of("pull_request");

  private static final String PULL_REQUEST = "pull_request";

  private static final int BATCH = 2_000;

  private final MongoDatabase source;

  public MongoPullRequestAttributes(final MongoClient client, final String sourceDb) {
    this.source = client.getDatabase(sourceDb);
  }

  @Override
  public Map<String, IssueAttributes> of(final Collection<String> pullRequestIds) {
    final Map<String, IssueAttributes> found = new HashMap<>(pullRequestIds.size() * 2);
    final List<ObjectId> all = new ArrayList<>(pullRequestIds.size());
    for (final String id : pullRequestIds) {
      all.add(new ObjectId(id));
    }
    for (int from = 0; from < all.size(); from += BATCH) {
      final List<ObjectId> batch = all.subList(from, Math.min(from + BATCH, all.size()));
      for (final Document pr : source.getCollection(PULL_REQUEST)
          .find(Filters.in("_id", batch))
          .projection(Projections.include("description"))) {
        found.put(pr.getObjectId("_id").toHexString(), attributes(pr));
      }
    }
    return found;
  }

  private static IssueAttributes attributes(final Document pr) {
    final String description = pr.getString("description");
    return new IssueAttributes(
        true,
        description == null ? OptionalInt.empty() : OptionalInt.of(description.length()),
        Optional.empty(),
        OptionalInt.empty());
  }
}
