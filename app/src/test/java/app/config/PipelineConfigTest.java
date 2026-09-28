package app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.identity.Strictness;
import app.model.Columns;
import app.labels.TtcTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PipelineConfigTest {

  private static PipelineConfig configOf(final String sourceDb, final String targetDb) {
    return configOf(sourceDb, targetDb, targetDb);
  }

  private static PipelineConfig configOf(
      final String sourceDb, final String targetDb, final String ledgerDb) {
    return new PipelineConfig(
        "mongodb://127.0.0.1:27017", sourceDb, targetDb, ledgerDb, targetDb, "smartshark_paper5",
        "smartshark_paper6",
        Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, 1.0, 1.7283, true, 0.70, 5,
        Duration.ofDays(90), Columns.Profile.ISSUE, TtcTarget.TOTAL,
        Optional.empty());
  }

  @Test
  @DisplayName("refuses a target equal to the source database")
  void refusesToTargetTheSourceDatabase() {
    // Provenance guard. Reconstructed documents written into smartshark_2_2 would be
    // indistinguishable from ones that shipped in the release, and re-restoring the 686 GB
    // archive would silently discard them. Checked at startup, so a misconfigured run fails in
    // under a second rather than after reading 7 GB of repositories.
    final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> configOf("smartshark_2_2", "smartshark_2_2"));

    assertTrue(thrown.getMessage().contains("must differ"));
  }

  @Test
  @DisplayName("accepts a distinct target")
  void acceptsDistinctTarget() {
    final PipelineConfig config = configOf("smartshark_2_2", "smartshark_tcc");

    assertEquals("smartshark_2_2", config.sourceDb());
    assertEquals("smartshark_tcc", config.targetDb());
    assertTrue(config.describe().contains("smartshark_tcc"));
  }

  @Test
  @DisplayName("ledgerDb can differ from targetDb -- reading an already-built ledger without "
      + "writing to the database it came from")
  void ledgerDbCanDifferFromTarget() {
    // The paper-4 use case this field exists for: TARGET_DB is a fresh database for pull-request
    // labels, LEDGER_DB is smartshark_tcc, already built and never written to by that run.
    final PipelineConfig config = configOf("smartshark_2_2", "smartshark_paper4", "smartshark_tcc");

    assertEquals("smartshark_paper4", config.targetDb());
    assertEquals("smartshark_tcc", config.ledgerDb());
  }
}
