package app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.identity.Strictness;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PipelineConfigTest {

  private static PipelineConfig configOf(final String sourceDb, final String targetDb) {
    return new PipelineConfig(
        "mongodb://127.0.0.1:27017", sourceDb, targetDb, "smartshark_issues", targetDb,
        Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME,
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
  @DisplayName("reads the prepared collections from TARGET_DB unless PREPARED_DB names another")
  void preparedDatabase() {
    final PipelineConfig same = configOf("smartshark_2_2", "smartshark_tcc");
    assertEquals("smartshark_tcc", same.preparedDb());
    final PipelineConfig own = new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
    assertTrue(own.describe().contains("prepared (read only): smartshark_tcc"));
    assertTrue(own.describe().contains("smartshark_first_commit_issues"));
  }

  @Test
  @DisplayName("refuses to record runs in the database the prepared collections are read from")
  void refusesIssuesInPrepared() {
    assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(
        "mongodb://127.0.0.1:27017", "smartshark_2_2", "smartshark_first_commit",
        "smartshark_tcc", "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, Optional.empty()));
  }
}
