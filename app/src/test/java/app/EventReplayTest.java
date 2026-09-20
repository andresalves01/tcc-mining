package app;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that an issue's event stream still reconstructs the issue's own final state.
 *
 * <p>Every date this study depends on — when an issue was created, when it was first resolved — is
 * recovered by replaying {@code event}. If that stream is not a faithful record, the temporal split
 * and the time-to-code are both built on sand, and nothing downstream would reveal it: the numbers
 * would simply be wrong and plausible.
 *
 * <p>The check replays the stream and compares the final value it arrives at against the field the
 * corpus already stores. It is a regression test with a known answer, not an experiment.
 *
 * <p><b>Read the status figure with suspicion.</b> Agreement on {@code status} measures 100.00% on
 * every slice tried, which is the signature of a field SmartSHARK itself derived from these events
 * rather than an independent record. A comparison against a value computed from the same source
 * cannot fail, so it guards against corpus damage and nothing more. The assignee figure is the
 * informative one: it sits near 93%, so the two sides are genuinely independent there.
 */
class EventReplayTest {

  private static final String MONGO_URI = "mongodb://127.0.0.1:27017";
  private static final int SAMPLE = 2_000;

  /** Measured 2026-09-20 over random samples: status 100.00%, assignee 93.2%. */
  private static final double STATUS_FLOOR = 0.99;
  private static final double ASSIGNEE_FLOOR = 0.88;

  private static String sourceDb() {
    final String configured = System.getenv("SOURCE_DB");
    return configured == null || configured.isBlank() ? "smartshark_2_2" : configured;
  }

  /** The last value the stream assigns to {@code field}, or empty when it never touches it. */
  private static String replay(final List<Document> events, final String field) {
    String last = null;
    for (final Document event : events) {
      if (field.equals(event.getString("status"))) {
        final Object value = event.get("new_value");
        last = value == null ? null : value.toString();
      }
    }
    return last;
  }

  @Test
  @DisplayName("replaying an issue's events still lands on the state the corpus records")
  void eventStreamReconstructsIssueState() {
    try (MongoClient client = MongoClients.create(MONGO_URI)) {
      final MongoDatabase db = client.getDatabase(sourceDb());
      final List<Document> issues = new ArrayList<>();
      try {
        db.getCollection("issue")
            .aggregate(List.of(Aggregates.sample(SAMPLE),
                Aggregates.project(Projections.include("status", "assignee_id"))))
            .forEach(issues::add);
      } catch (final MongoException e) {
        assumeTrue(false, "no MongoDB at " + MONGO_URI + ": " + e.getMessage());
        return;
      }
      assumeTrue(!issues.isEmpty(), sourceDb() + ".issue is empty or absent; nothing to replay");

      int withStatus = 0;
      int statusAgrees = 0;
      int withAssignee = 0;
      int assigneeAgrees = 0;

      for (final Document issue : issues) {
        final List<Document> events = new ArrayList<>();
        db.getCollection("event")
            .find(Filters.eq("issue_id", issue.getObjectId("_id")))
            .projection(Projections.include("status", "new_value", "created_at"))
            .sort(Sorts.ascending("created_at"))
            .forEach(events::add);

        final String status = replay(events, "status");
        if (status != null) {
          withStatus++;
          if (status.equals(issue.getString("status"))) {
            statusAgrees++;
          }
        }
        final String assignee = replay(events, "assignee_id");
        if (assignee != null) {
          withAssignee++;
          final ObjectId stored = issue.getObjectId("assignee_id");
          if (stored != null && assignee.equals(stored.toHexString())) {
            assigneeAgrees++;
          }
        }
      }

      assumeTrue(withStatus > 0 && withAssignee > 0,
          "the sample carries no status or assignee events; nothing to compare");

      final double statusRate = (double) statusAgrees / withStatus;
      final double assigneeRate = (double) assigneeAgrees / withAssignee;
      final String measured = String.format(
          "status %.2f%% of %,d, assignee %.2f%% of %,d",
          100 * statusRate, withStatus, 100 * assigneeRate, withAssignee);

      assertTrue(statusRate >= STATUS_FLOOR,
          "event replay no longer reproduces issue.status (" + measured + ")");
      assertTrue(assigneeRate >= ASSIGNEE_FLOOR,
          "event replay no longer reproduces issue.assignee_id (" + measured
              + ") -- every reconstructed date rests on this stream");
    }
  }
}
