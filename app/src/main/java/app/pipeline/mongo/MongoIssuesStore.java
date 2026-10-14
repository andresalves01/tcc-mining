package app.pipeline.mongo;

import app.pullrequests.port.ResultStore;
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

  // The first runs of the issue-duration study were recorded under these names in
  // smartshark_issues before the package was renamed; the names stay so the record stays whole.
  public static final String EXECUCAO = "paper6_execucao";
  public static final String RESULTADO = "paper6_resultado";

  private final MongoDatabase database;
  private final String name;

  public MongoIssuesStore(final MongoClient client, final String database) {
    this.database = client.getDatabase(database);
    this.name = database;
  }

  /** The database this writes to, so a report can name it instead of implying it. */
  public String databaseName() {
    return name;
  }

  @Override
  public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
    final ObjectId execution = new ObjectId();
    final Document header = new Document(run);
    header.put("_id", execution);
    header.put("gravado_em", new Date());
    database.getCollection(EXECUCAO).insertOne(header);

    if (!rows.isEmpty()) {
      final List<Document> documents = new ArrayList<>(rows.size());
      for (final Map<String, Object> row : rows) {
        final Document document = new Document(row);
        document.put("execucao_id", execution);
        documents.add(document);
      }
      database.getCollection(RESULTADO).insertMany(documents);
    }
    return execution.toHexString();
  }
}
