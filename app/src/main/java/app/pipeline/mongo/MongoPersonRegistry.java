package app.pipeline.mongo;

import app.identity.Identity;
import app.identity.PersonRecord;
import app.identity.RefusedBridge;
import app.identity.Source;
import app.identity.port.PersonRegistry;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * MongoDB adapter for {@link PersonRegistry}.
 *
 * <p>Writes two collections into a database separate from the corpus:
 *
 * <ul>
 *   <li>{@code people} — the registry. Uses the original {@code ObjectId}s as {@code _id} and the
 *       field names {@code pycoshark} declares, so it is a drop-in for the collection the release
 *       omits: existing SmartSHARK code expecting {@code people} works unchanged against it.
 *   <li>{@code person_canonical} — the equivalence relation, indexed on {@code canonical_id}.
 * </ul>
 *
 * <p>Only {@code person_canonical} is safe to publish. It holds nothing but opaque
 * {@code ObjectId}s already present in the public release, whereas {@code people} holds names and
 * addresses — personal data the dataset authors chose to withhold. Publishing the equivalence map
 * together with this code gives full reproducibility without redistributing anyone's identity.
 */
public final class MongoPersonRegistry implements PersonRegistry {

  public static final String PEOPLE = "people";
  public static final String PERSON_CANONICAL = "person_canonical";
  public static final String BRIDGE_REFUSED = "identity_bridge_refused";

  private static final int BATCH_SIZE = 2_000;

  private final MongoDatabase target;
  private final String targetName;

  public MongoPersonRegistry(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public Map<String, PersonRecord> readAll() {
    final Map<String, PersonRecord> out = new LinkedHashMap<>();
    for (final Document doc : target.getCollection(PEOPLE).find()) {
      final ObjectId id = doc.getObjectId("_id");
      if (id == null) {
        continue;
      }
      final Identity identity = new Identity(doc.getString("name"), doc.getString("email"));
      out.put(id.toHexString(), new PersonRecord(
          id.toHexString(), identity, doc.getString("username"), sourceOf(doc.getString("source"))));
    }
    return out;
  }

  @Override
  public Map<String, Integer> readAuthoredCommits() {
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final Document doc : target.getCollection(PERSON_CANONICAL).find()
        .projection(Projections.include("n_authored"))) {
      final ObjectId id = doc.getObjectId("_id");
      final Integer authored = doc.getInteger("n_authored");
      if (id != null && authored != null && authored > 0) {
        out.put(id.toHexString(), authored);
      }
    }
    return out;
  }

  @Override
  public Map<String, String> readCanonical() {
    final Map<String, String> out = new LinkedHashMap<>();
    for (final Document doc : target.getCollection(PERSON_CANONICAL).find()
        .projection(Projections.include("canonical_id"))) {
      final ObjectId id = doc.getObjectId("_id");
      final ObjectId canonical = doc.getObjectId("canonical_id");
      if (id != null && canonical != null) {
        out.put(id.toHexString(), canonical.toHexString());
      }
    }
    return out;
  }

  @Override
  public Set<String> readBots() {
    // Flagged per raw identity, but consumed per canonical person: one bot alias is enough to make
    // the merged person a bot, since the merge asserts they are the same actor.
    final Set<String> out = new LinkedHashSet<>();
    for (final Document doc : target.getCollection(PERSON_CANONICAL).find()
        .projection(Projections.include("canonical_id", "is_bot"))) {
      final ObjectId canonical = doc.getObjectId("canonical_id");
      if (canonical != null && Boolean.TRUE.equals(doc.getBoolean("is_bot"))) {
        out.add(canonical.toHexString());
      }
    }
    return out;
  }

  @Override
  public long saveIdentities(final Collection<PersonRecord> records) {
    final List<WriteModel<Document>> operations = new ArrayList<>(records.size());
    final ReplaceOptions upsert = new ReplaceOptions().upsert(true);
    for (final PersonRecord record : records) {
      final ObjectId id = new ObjectId(record.personId());
      final Document doc = new Document("_id", id)
          .append("name", record.identity().name())
          .append("email", record.identity().email())
          .append("username", record.username())
          .append("source", record.source().name());
      operations.add(new ReplaceOneModel<>(new Document("_id", id), doc, upsert));
    }
    return bulk(PEOPLE, operations);
  }

  @Override
  public long saveCanonical(
      final Map<String, String> canonical,
      final Map<String, PersonRecord> records,
      final Map<String, Integer> authoredCommits) {

    final List<WriteModel<Document>> operations = new ArrayList<>(canonical.size());
    final ReplaceOptions upsert = new ReplaceOptions().upsert(true);
    for (final Map.Entry<String, String> entry : canonical.entrySet()) {
      final PersonRecord record = records.get(entry.getKey());
      final ObjectId id = new ObjectId(entry.getKey());
      final Document doc = new Document("_id", id)
          .append("canonical_id", new ObjectId(entry.getValue()))
          .append("is_bot", record != null && record.identity().isBot())
          .append("source", record == null ? Source.GIT.name() : record.source().name())
          .append("n_authored", authoredCommits.getOrDefault(entry.getKey(), 0));
      operations.add(new ReplaceOneModel<>(new Document("_id", id), doc, upsert));
    }
    final long written = bulk(PERSON_CANONICAL, operations);
    target.getCollection(PERSON_CANONICAL)
        .createIndex(Indexes.ascending("canonical_id"), new IndexOptions().background(true));
    return written;
  }

  @Override
  public long saveRefusedBridges(final Collection<RefusedBridge> refusals) {
    target.getCollection(BRIDGE_REFUSED).drop(); // a refusal set describes one run of the rules
    final List<WriteModel<Document>> operations = new ArrayList<>(refusals.size());
    for (final RefusedBridge refusal : refusals) {
      final Document doc = new Document("_id", new ObjectId(refusal.jiraPersonId()))
          .append("handle", refusal.handle())
          .append("git_person_ids",
              refusal.gitPersonIds().stream().map(ObjectId::new).toList())
          .append("n_names", refusal.names().size());
      operations.add(new ReplaceOneModel<>(new Document("_id", doc.get("_id")), doc,
          new ReplaceOptions().upsert(true)));
    }
    return bulk(BRIDGE_REFUSED, operations);
  }

  @Override
  public String describe() {
    return targetName;
  }

  @Override
  public String describeIdentities() {
    return targetName + "." + PEOPLE;
  }

  @Override
  public String describeCanonical() {
    return targetName + "." + PERSON_CANONICAL;
  }

  private static Source sourceOf(final String raw) {
    if (raw == null) {
      return Source.GIT;
    }
    try {
      return Source.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      return Source.GIT;
    }
  }

  private long bulk(final String collectionName, final List<WriteModel<Document>> operations) {
    if (operations.isEmpty()) {
      return 0;
    }
    final MongoCollection<Document> collection = target.getCollection(collectionName);
    long total = 0;
    for (int i = 0; i < operations.size(); i += BATCH_SIZE) {
      final int end = Math.min(operations.size(), i + BATCH_SIZE);
      final List<WriteModel<Document>> batch = operations.subList(i, end);
      final BulkWriteResult result = collection.bulkWrite(batch);
      total += result.getInsertedCount() + result.getModifiedCount() + result.getUpserts().size();
    }
    return total;
  }
}
