package app.pipeline.mongo;

import app.labels.TtcTarget;
import app.model.Columns;
import app.model.Observation;
import app.model.Observations;
import app.model.PairHistory;
import app.model.Partition;
import app.model.port.ObservationSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
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
  private final TtcTarget outcomeTarget;

  public MongoObservations(final MongoClient client, final String targetDb) {
    this(client, targetDb, TtcTarget.TOTAL);
  }

  /** Additive: the two-argument form is the whole life, which is what every issue stage wants. */
  public MongoObservations(
      final MongoClient client, final String targetDb, final TtcTarget outcomeTarget) {

    this.target = client.getDatabase(targetDb);
    this.targetName = targetDb;
    this.outcomeTarget = outcomeTarget;
  }

  /** The stored field each target reads. Absent on a row means the row cannot serve that target. */
  private String outcomeField() {
    return switch (outcomeTarget) {
      case TOTAL -> "ttc_days";
      case ESPERA -> "ttc_espera";
      case AUTOR -> "ttc_autor";
      case TOTAL_UTIL -> "ttc_util";
      case ESPERA_UTIL -> "ttc_espera_util";
      case AUTOR_UTIL -> "ttc_autor_util";
    };
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

    // De onde a mediana do par pode olhar. Nunca a particao que se esta pontuando: no protocolo
    // interno o ajuste e FIT, no externo e TRAIN inteiro. Sem isso a coluna seria vazamento.
    final Bson poolFilter = switch (partition) {
      case FIT, SELECTION ->
          Filters.and(Filters.eq("split", "TRAIN"), Filters.eq("split_inner", "TRAIN"));
      case TRAIN_ALL, HELD_OUT -> Filters.eq("split", "TRAIN");
    };

    final String field = outcomeField();
    // Só o perfil de pull request declara a coluna; para o pipeline de issues isto seria uma
    // varredura a mais de issue_label sem consumidor nenhum.
    final boolean needsHistory = Columns.names().contains(Columns.PAIR_MEDIAN);
    final List<PairHistory.Entry> pool = new ArrayList<>(needsHistory ? 1 << 14 : 0);
    for (final Document label : needsHistory
        ? target.getCollection(ISSUE_LABEL).find(poolFilter)
            .projection(Projections.include(field, "project", "canonical_id", "created_at"))
        : List.<Document>of()) {
      final Double value = label.getDouble(field);
      if (value == null || value <= 0) {
        continue;
      }
      final String project = label.getString("project");
      pool.add(new PairHistory.Entry(
          label.getObjectId("canonical_id").toHexString() + '\u0000' + project,
          project, label.getDate("created_at").getTime(), Math.log(value + 1e-6)));
    }
    final PairHistory history = PairHistory.of(pool);
    final Map<ObjectId, Double> outcome = new HashMap<>(1 << 15);
    int withoutOutcome = 0;
    // Resolution minus first linked commit, which Population selects on. Read here rather than
    // recomputed downstream because both instants live on the label and nowhere else.
    final Map<ObjectId, Double> postCommit = new HashMap<>(1 << 15);
    for (final Document label : target.getCollection(ISSUE_LABEL)
        .find(filter).projection(Projections.include(field, "resolved_at", "first_commit_at"))) {
      final Double value = label.getDouble(field);
      // Sem exame externo registrado nao ha as duas parcelas, e a linha sai deste alvo -- sem sair
      // do alvo TOTAL, onde ela e tao observada quanto qualquer outra. E a decomposicao que e
      // parcial, nao o desfecho.
      if (value == null) {
        withoutOutcome++;
        continue;
      }
      // Um intervalo nao positivo nao tem logaritmo. Acontece na parcela de espera quando o exame
      // caiu no mesmo instante da abertura.
      if (value <= 0) {
        withoutOutcome++;
        continue;
      }
      final ObjectId id = label.getObjectId("_id");
      outcome.put(id, value);
      final java.util.Date resolved = label.getDate("resolved_at");
      final java.util.Date first = label.getDate("first_commit_at");
      postCommit.put(id, resolved == null || first == null ? Double.NaN
          : (resolved.getTime() - first.getTime()) / 86_400_000.0);
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
          values(features, history),
          postCommit.getOrDefault(features.getObjectId("_id"), Double.NaN)));
    }
    return new Observations(Columns.names(), rows, excluded, withoutOutcome);
  }

  private static double[] values(final Document features, final PairHistory history) {
    final double[] out = new double[Columns.all().size()];
    final String type = features.getString("extra_issue_type");
    for (int i = 0; i < Columns.all().size(); i++) {
      final Columns.Column column = Columns.all().get(i);
      if (!column.field().isEmpty()) {
        final Number raw = features.get(column.field(), Number.class);
        final double value = raw == null ? 0 : raw.doubleValue();
        out[i] = column.log() ? Math.log1p(Math.max(value, 0)) : value;
      } else if (column.name().equals(Columns.PAIR_MEDIAN)) {
        final String project = features.getString("project");
        final Date at = features.getDate("created_at");
        out[i] = history.medianFor(
            features.getObjectId("canonical_id").toHexString() + '\u0000' + project,
            project, at == null ? Long.MAX_VALUE : at.getTime());
      } else if (column.name().equals(Columns.LINES_PER_COMMIT)) {
        // Volume por commit. Um denominador zero e um par sem historico anterior, cujo volume
        // tambem e zero: a razao vira zero, que e o mesmo valor que ln(1+0) daria, e o indicador
        // dev_had_history ja separa esse caso de um volume medido em zero.
        final double lines = number(features, "loc_tot_java");
        final double commits = number(features, "extra_dev_commits");
        out[i] = commits <= 0 ? 0 : Math.log1p(Math.max(lines, 0) / commits);
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

  private static double number(final Document features, final String field) {
    final Number raw = features.get(field, Number.class);
    return raw == null ? 0 : raw.doubleValue();
  }

  @Override
  public String describe() {
    return targetName + "." + ISSUE_FEATURES + " joined to " + ISSUE_LABEL;
  }
}
