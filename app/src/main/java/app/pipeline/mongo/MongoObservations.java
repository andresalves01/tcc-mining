package app.pipeline.mongo;

import app.model.Columns;
import app.model.Observation;
import app.model.Observations;
import app.model.Partition;
import app.model.port.ObservationSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

/**
 * Joins {@code issue_features} to the outcome and the partition recorded on {@code issue_label}.
 *
 * <p>The join is here rather than in a port method on either store because it belongs to neither:
 * the features know nothing about the split, and the labels know nothing about the matrix. Doing it
 * in one adapter keeps both of those true.
 *
 * <p>The held-out set became reachable on 2026-09-21; {@link Partition} records when and in what
 * state, because that is what a reader needs in order to know how to weigh the number.
 */
public final class MongoObservations implements ObservationSource {

  private static final String ISSUE_LABEL = "issue_label";
  private static final String ISSUE_FEATURES = "issue_features";

  private final MongoDatabase target;
  private final String targetName;

  public MongoObservations(final MongoClient client, final String targetDb) {
    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
  }

  @Override
  public Observations of(final Partition partition) {
    final Bson filter = switch (partition) {
      case FIT -> Filters.and(Filters.eq("split", "TRAIN"), Filters.eq("split_inner", "TRAIN"));
      case SELECTION ->
          Filters.and(Filters.eq("split", "TRAIN"), Filters.eq("split_inner", "TEST"));
      case TRAIN_ALL -> Filters.eq("split", "TRAIN");
      case HELD_OUT -> Filters.eq("split", "TEST");
    };

    final Map<ObjectId, Double> outcome = new HashMap<>(1 << 15);
    for (final Document label : target.getCollection(ISSUE_LABEL)
        .find(filter).projection(Projections.include("ttc_days"))) {
      outcome.put(label.getObjectId("_id"), label.getDouble("ttc_days"));
    }

    final List<Observation> rows = new ArrayList<>(outcome.size());
    int excluded = 0;
    for (final Document features : target.getCollection(ISSUE_FEATURES)
        .find(Filters.in("_id", outcome.keySet()))) {

      if (features.getBoolean("team_empty", false)) {
        excluded++; // the study excludes these from the full model and counts them
        continue;
      }
      rows.add(new Observation(
          features.getObjectId("_id").toHexString(),
          features.getString("project"),
          features.getObjectId("canonical_id").toHexString(),
          outcome.get(features.getObjectId("_id")),
          values(features)));
    }
    return new Observations(Columns.names(), rows, excluded);
  }

  private static double[] values(final Document features) {
    final double[] out = new double[Columns.all().size()];
    final String type = features.getString("extra_issue_type");
    for (int i = 0; i < Columns.all().size(); i++) {
      final Columns.Column column = Columns.all().get(i);
      if (!column.field().isEmpty()) {
        final Number raw = features.get(column.field(), Number.class);
        final double value = raw == null ? 0 : raw.doubleValue();
        out[i] = column.log() ? Math.log1p(Math.max(value, 0)) : value;
      } else if (column.name().equals("descricao ausente")) {
        out[i] = features.get("extra_desc_length") == null ? 1 : 0;
      } else if (column.name().equals("ligacoes ausentes")) {
        out[i] = features.get("extra_linked_issues") == null ? 1 : 0;
      } else if (column.name().startsWith("tipo=")) {
        out[i] = column.name().substring(5).equals(type) ? 1 : 0;
      }
    }
    return out;
  }

  @Override
  public String describe() {
    return targetName + "." + ISSUE_FEATURES + " joined to " + ISSUE_LABEL;
  }
}
