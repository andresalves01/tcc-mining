package app.pipeline.mongo;

import app.issues.FirstCommit;
import app.issues.SplitRule;
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
  private final String runs;
  private final String results;

  public MongoIssuesStore(final MongoClient client, final String database) {
    this(client.getDatabase(database), "");
  }

  /** The store over {@code runs} and {@code results}, each with {@code suffix}. */
  private MongoIssuesStore(final MongoDatabase database, final String suffix) {
    this.database = database;
    this.runs = RUNS + suffix;
    this.results = RESULTS + suffix;
  }

  /**
   * The random design's runs in {@code runs} and {@code results}, as always; another design's in
   * the same names with its suffix ({@link SplitRule#collectionSuffix}), such as {@code
   * runs_temporal}.
   */
  @Override
  public ResultStore design(final SplitRule rule) {
    return new MongoIssuesStore(database, rule.collectionSuffix());
  }

  /** The design of every run in this store's run collection; reads two fields of each. */
  @Override
  public java.util.Optional<java.util.Set<SplitRule>> designs() {
    final java.util.Set<SplitRule> out = java.util.EnumSet.noneOf(SplitRule.class);
    for (final Document d : database.getCollection(runs).find()
        .projection(new Document(SplitRule.DESIGN_FIELD, 1).append(SplitRule.RULE_FIELD, 1))) {
      out.add(SplitRule.recordedBy(d));
    }
    return java.util.Optional.of(out);
  }

  /** The k of the latest first-commit extraction run in this store's run collection. */
  @Override
  public double waitFenceK() {
    final Document latest = database.getCollection(runs)
        .find(new Document("stage", "issues-in-progress-extract")
            .append("study_population", app.issues.Universe.Population.FIRST_COMMIT.name()))
        .projection(new Document("wait_fence_k", 1))
        .sort(new Document("recorded_at", -1)).first();
    final Object k = latest == null ? null : latest.get("wait_fence_k");
    return FirstCommit.waitFenceK(k == null ? null : k.toString());
  }

  /** The runs of {@code stage} in this store's run collection, newest first. */
  @Override
  public java.util.Optional<List<Map<String, Object>>> recordedRuns(final String stage) {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final Document d : database.getCollection(runs).find(new Document("stage", stage))
        .sort(new Document("recorded_at", -1))) {
      out.add(d);
    }
    return java.util.Optional.of(List.copyOf(out));
  }

  @Override
  public java.util.Optional<Boolean> recorded(final String stage) {
    return java.util.Optional.of(database.getCollection(runs)
        .countDocuments(new Document("stage", stage)) > 0);
  }

  @Override
  public String record(final Map<String, Object> run, final List<Map<String, Object>> rows) {
    final ObjectId execution = new ObjectId();
    final Document header = new Document(run);
    header.put("_id", execution);
    header.put("recorded_at", new Date());
    database.getCollection(runs).insertOne(header);

    if (!rows.isEmpty()) {
      final List<Document> documents = new ArrayList<>(rows.size());
      for (final Map<String, Object> row : rows) {
        final Document document = new Document(row);
        document.put("run_id", execution);
        documents.add(document);
      }
      database.getCollection(results).insertMany(documents);
    }
    return execution.toHexString();
  }
}
