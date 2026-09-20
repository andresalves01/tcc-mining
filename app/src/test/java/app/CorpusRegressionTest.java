package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A regression test on the <em>environment</em>, not on this code: it asserts that the restored
 * corpus is still the one every number in the README was measured from.
 *
 * <p>Worth having because the failures it catches are silent ones. A re-restore that stopped early,
 * a {@code SOURCE_DB} pointing at the small release, a collection dropped by accident — none of
 * these make a stage crash. They make it produce a smaller, plausible-looking result that nobody
 * can tell from a real finding until the paper is being written.
 *
 * <p>Counts come from collection metadata rather than a scan, so the whole class answers in
 * milliseconds. That is the point: a full {@code countDocuments} on {@code commit} reads 84 GB and
 * takes minutes, which is a check nobody would run. The trade is that metadata can drift from the
 * truth after an unclean shutdown — if these numbers ever disagree with a scan, trust the scan and
 * treat the disagreement as its own finding.
 *
 * <p>Skipped, not failed, when no MongoDB answers: the other 81 tests are the ones that must run
 * anywhere.
 */
class CorpusRegressionTest {

  private static final String MONGO_URI = "mongodb://127.0.0.1:27017";

  /** Collections in the full SmartSHARK 2.2 release. The small one omits {@code code_entity_state}. */
  private static final int COLLECTIONS = 31;

  /**
   * Measured on the restored corpus on 2026-09-20, and the same figures the README reports.
   * A change here is either a different corpus or a damaged one; it is never a routine update.
   */
  private static final Map<String, Long> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("project", 98L);
    EXPECTED.put("vcs_system", 98L);
    EXPECTED.put("issue_system", 111L);
    EXPECTED.put("commit", 452_286L);
    EXPECTED.put("issue", 207_400L);
    EXPECTED.put("file", 952_760L);
    EXPECTED.put("event", 1_589_368L);
    EXPECTED.put("file_action", 6_202_314L);
    EXPECTED.put("hunk", 21_047_012L);
    EXPECTED.put("code_entity_state", 563_762_961L);
  }

  private static String sourceDb() {
    final String configured = System.getenv("SOURCE_DB");
    return configured == null || configured.isBlank() ? "smartshark_2_2" : configured;
  }

  @Test
  @DisplayName("the restored corpus still holds the counts every reported number was measured from")
  void corpusMatchesTheMeasuredCounts() {
    try (MongoClient client = MongoClients.create(MONGO_URI)) {
      final MongoDatabase db = client.getDatabase(sourceDb());
      final List<String> names;
      try {
        names = db.listCollectionNames().into(new ArrayList<>());
      } catch (final MongoException e) {
        assumeTrue(false, "no MongoDB at " + MONGO_URI + ": " + e.getMessage());
        return;
      }
      assumeTrue(!names.isEmpty(), sourceDb() + " is empty or absent; nothing to regress against");

      // Every mismatch is collected rather than thrown on, because "which collections drifted" is
      // the diagnosis. Failing on the first one would report a short restore as one missing
      // collection.
      final List<String> drift = new ArrayList<>();
      if (names.size() != COLLECTIONS) {
        // The small release is only a candidate explanation when the rest of the corpus is there;
        // against an unrelated database everything is missing and naming one collection would
        // point at the wrong cause.
        final boolean looksLikeACorpus = db.getCollection("commit").estimatedDocumentCount() > 0;
        drift.add("collections: expected " + COLLECTIONS + ", found " + names.size()
            + (looksLikeACorpus && !names.contains("code_entity_state")
                ? " (no code_entity_state: this is the small release)" : ""));
      }
      EXPECTED.forEach((collection, expected) -> {
        final long actual = db.getCollection(collection).estimatedDocumentCount();
        if (actual != expected) {
          drift.add(String.format("%s: expected %,d, found %,d", collection, expected, actual));
        }
      });

      assertEquals(List.of(), drift,
          "the corpus in '" + sourceDb() + "' is not the one the reported numbers came from");
    }
  }
}
