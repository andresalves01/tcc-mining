package app.cli.commands;

import app.config.PipelineConfig;
import app.identity.Strictness;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** The first-commit study's own databases, which every stage requires before reading anything. */
final class OwnDatabases {

  private OwnDatabases() {}

  static PipelineConfig config() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2",
        "smartshark_first_commit", "smartshark_first_commit_issues", "smartshark_tcc",
        Path.of("build/work"), Path.of("build/cache"), Duration.ofMillis(250),
        Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }
}
