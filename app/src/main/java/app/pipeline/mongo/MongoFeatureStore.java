package app.pipeline.mongo;

import app.features.FeatureRow;
import app.features.TeamStatistics;
import app.features.port.FeatureStore;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.OptionalInt;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Writes {@code issue_features}, one document per label.
 *
 * <p>A separate collection rather than columns on the label, because {@code issue_label} is dropped
 * and rewritten by every funnel run: a predictor stored there would vanish the next time the sample
 * is rebuilt, silently and only for whoever read it afterwards.
 *
 * <p>Dropped before writing for the reason the label store gives about itself — the key space
 * follows the labels, and rows from a looser earlier run would be indistinguishable from current
 * ones.
 *
 * <p><b>Absent fields are absent, not zero.</b> A predictor that could not be observed is left off
 * the document entirely, so a consumer that forgets to check gets nothing rather than a plausible
 * number. That is the whole reason {@code McCC_tot} does not appear at all yet.
 */
public final class MongoFeatureStore implements FeatureStore {

  public static final String ISSUE_FEATURES = "issue_features";
  public static final String FEATURE_META = "feature_meta";

  private static final int BATCH_SIZE = 2_000;

  private final MongoDatabase target;
  private final String targetName;

  public MongoFeatureStore(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public long save(final Collection<FeatureRow> rows, final Duration teamWindow) {
    final MongoCollection<Document> collection = target.getCollection(ISSUE_FEATURES);
    collection.drop();
    final ReplaceOptions upsert = new ReplaceOptions().upsert(true);
    final List<WriteModel<Document>> batch = new ArrayList<>(BATCH_SIZE);
    long written = 0;
    for (final FeatureRow row : rows) {
      final ObjectId id = new ObjectId(row.issueId());
      batch.add(new ReplaceOneModel<>(new Document("_id", id), document(row, id), upsert));
      if (batch.size() == BATCH_SIZE) {
        written += write(collection, batch);
      }
    }
    written += write(collection, batch);

    collection.createIndex(Indexes.ascending("project"), new IndexOptions().background(true));
    collection.createIndex(Indexes.ascending("canonical_id"), new IndexOptions().background(true));
    // What is absent is a property of the rows, not a constant: it stops being true the moment
    // the per-commit complexity table exists, and nothing downstream should have to guess.
    final boolean withComplexity = rows.stream()
        .anyMatch(row -> row.developer().complexity().isPresent());
    target.getCollection(FEATURE_META).replaceOne(new Document("_id", "current"),
        meta(rows.size(), teamWindow,
            withComplexity ? List.of() : List.of("mccc_tot", "mu_m", "s_m")), upsert);
    return written;
  }

  private static Document document(final FeatureRow row, final ObjectId id) {
    final Document doc = new Document("_id", id)
        .append("project", row.projectName())
        .append("canonical_id", new ObjectId(row.canonicalAuthorId()))
        .append("created_at", Date.from(row.createdAt()))

        // The published predictor set. Raw: the logarithms and the standardisation are both fitted
        // decisions, and the scaling uses the training side's own statistics.
        .append("loc_tot_java", row.developer().javaLines())
        .append("loc_tot_all", row.developer().allLines())
        .append("loc_iss", row.taskLines())
        .append("mccc_i", row.taskComplexity())
        .append("nl_i", row.taskNesting())
        .append("iss_dev", row.load().developerInProject())
        .append("iss_team", row.load().team())

        // Outside the specification, and marked so by name rather than by a comment in a script.
        .append("extra_dev_commits", row.developer().commits())
        .append("extra_dev_tenure_days", row.developer().tenureDays())
        .append("extra_team_size", row.team().size())
        .append("extra_iss_dev_career", row.load().developerAcrossProjects())

        // Diagnostics, so a zero can be read as a first day or as missing evidence.
        .append("dev_known_to_ledger", row.developerKnownToLedger())
        .append("dev_had_history", row.developerHadHistory())
        .append("team_empty", row.team().isEmpty())
        .append("attributes_found", row.attributes().found());

    row.developer().complexity().ifPresent(c -> doc
        .append("mccc_tot", c.mccc())
        .append("nl_tot", c.nl())
        .append("extra_dev_methods", c.methods())
        .append("extra_dev_methods_unrecoverable", c.unrecoverable()));
    row.team().accumulatedLines().ifPresent(stats -> put(doc, "mu_l", "s_l", stats));
    row.team().accumulatedComplexity().ifPresent(stats -> put(doc, "mu_m", "s_m", stats));
    putIfPresent(doc, "extra_desc_length", row.attributes().descriptionLength());
    putIfPresent(doc, "extra_linked_issues", row.attributes().linkedIssues());
    row.attributes().issueType().ifPresent(type -> doc.append("extra_issue_type", type));
    // Os preditores de pull request ja vem nomeados pelo campo que Columns le, e um ausente fica
    // ausente: as colunas de exame nao existem para um rotulo que ninguem examinou.
    row.extras().forEach(doc::append);
    return doc;
  }

  private static void put(final Document doc, final String mean, final String deviation,
      final TeamStatistics statistics) {

    doc.append(mean, statistics.mean()).append(deviation, statistics.deviation());
  }

  private static void putIfPresent(final Document doc, final String field,
      final OptionalInt value) {

    value.ifPresent(present -> doc.append(field, present));
  }

  /**
   * One document describing the run, so nothing downstream has to assume a window or guess why a
   * predictor is missing.
   */
  private static Document meta(final int rows, final Duration teamWindow,
      final List<String> absent) {

    return new Document("_id", "current")
        .append("computed_at", new Date())
        .append("rows", rows)
        .append("team_window_days", teamWindow.toDays())
        .append("bounds", "none -- every stored label, whatever its duration")
        .append("absent", absent)
        .append("absent_reason", absent.isEmpty() ? "nothing"
            : "McCC_tot needs the per-commit complexity table that commit-methods builds; the "
                + "funnel keeps only the set deduplicated once per issue, which is a different "
                + "rule");
  }

  private static long write(final MongoCollection<Document> collection,
      final List<WriteModel<Document>> batch) {

    if (batch.isEmpty()) {
      return 0;
    }
    final BulkWriteResult result = collection.bulkWrite(batch);
    batch.clear();
    return result.getInsertedCount() + result.getUpserts().size() + result.getModifiedCount();
  }

  @Override
  public String describe() {
    return targetName + "." + ISSUE_FEATURES;
  }
}
