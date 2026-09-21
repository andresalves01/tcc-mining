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
 * Reads what each issue recorded about itself when it was opened.
 *
 * <p>The only part of the features stage that touches the published corpus. It is fetched by id in
 * batches rather than scanned, because the labels are a twentieth of {@code issue} and the ids are
 * already in hand — a full scan here would cost twenty times as much to answer the same question.
 *
 * <p>A field the corpus does not record stays absent. {@code issue_links} is the one that matters:
 * it is stored only for issues that have links, so reading its absence as "no links" is a claim
 * about the corpus, and that claim belongs to whoever fits the model rather than to the reader.
 */
public final class MongoIssueAttributes implements IssueAttributeSource {

  /** Named so {@code check} can refuse before the stage discovers the gap itself. */
  public static final Set<String> READS = Set.of("issue");

  private static final String ISSUE = "issue";

  private static final int BATCH = 2_000;

  private final MongoDatabase source;

  public MongoIssueAttributes(final MongoClient client, final String sourceDb) {
    this.source = client.getDatabase(sourceDb);
  }

  @Override
  public Map<String, IssueAttributes> of(final Collection<String> issueIds) {
    final Map<String, IssueAttributes> found = new HashMap<>(issueIds.size() * 2);
    final List<ObjectId> all = new ArrayList<>(issueIds.size());
    for (final String id : issueIds) {
      all.add(new ObjectId(id));
    }
    for (int from = 0; from < all.size(); from += BATCH) {
      final List<ObjectId> batch = all.subList(from, Math.min(from + BATCH, all.size()));
      for (final Document issue : source.getCollection(ISSUE)
          .find(Filters.in("_id", batch))
          .projection(Projections.include("issue_type", "desc", "issue_links"))) {

        found.put(issue.getObjectId("_id").toHexString(), attributes(issue));
      }
    }
    return found;
  }

  private static IssueAttributes attributes(final Document issue) {
    final String description = issue.getString("desc");
    final List<?> links = issue.getList("issue_links", Object.class);
    return new IssueAttributes(
        true,
        description == null ? OptionalInt.empty() : OptionalInt.of(description.length()),
        Optional.ofNullable(issue.getString("issue_type")),
        links == null ? OptionalInt.empty() : OptionalInt.of(links.size()));
  }
}
