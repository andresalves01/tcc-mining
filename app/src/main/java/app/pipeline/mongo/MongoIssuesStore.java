package app.pipeline.mongo;

import app.issues.port.ResultStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Persists the issue transposition's results into its own database, and touches nothing else.
 *
 * <p>Section 1.8 of the handout asks for this: attributes and results in a database of the study's
 * own, never as columns added to a collection someone else's work depends on. {@code PipelineConfig}
 * refuses to start if the name collides with the source, the units or the ledger, so the guarantee
 * is enforced before this class is ever constructed.
 *
 * <p><b>Nothing here drops or replaces.</b> Every write is an insert under a fresh execution id. If
 * the reserved partition is ever scored twice, the database holds two executions and the second one
 * cannot hide behind the first -- which is the property that matters, because a silent overwrite
 * would erase the evidence of exactly the mistake this design exists to make visible.
 */
public final class MongoIssuesStore implements ResultStore {

  public static final String RUNS = "runs";
  public static final String RESULTS = "results";

  private final MongoDatabase database;
  private final String name;

  public MongoIssuesStore(final MongoClient client, final String database) {
    this.database = client.getDatabase(database);
    this.name = database;
  }

  @Override
  public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
    final ObjectId execution = new ObjectId();
    final Document header = new Document(run);
    header.put("_id", execution);
    header.put("recorded_at", new Date());
    database.getCollection(RUNS).insertOne(header);

    if (!rows.isEmpty()) {
      final List<Document> documents = new ArrayList<>(rows.size());
      for (final Map<String, Object> row : rows) {
        final Document document = new Document(row);
        document.put("run_id", execution);
        documents.add(document);
      }
      database.getCollection(RESULTS).insertMany(documents);
    }
    return execution.toHexString();
  }
}
