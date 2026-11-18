package app.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The same-model calibration is a command of its own, described as what it is. */
class SameModelCommandTest {

  @Test
  @DisplayName("issues-first-commit-same-model-calibration is a command, its help a sensitivity "
      + "after the opening that chooses nothing, with its settings")
  void registered() {
    final Command c = Command.parse("issues-first-commit-same-model-calibration").orElseThrow();
    final String help = c.help();
    assertTrue(help.contains("SENSITIVITY"), help);
    assertTrue(help.contains("chooses nothing"), help);
    for (final String setting : new String[] {"MATRIX", "ELASTIC_NET_PARAMETERS",
        "TEST_PREDICTIONS", "REPORT_DIR", "SPLIT_RULE=revised",
        "issues-first-commit-test-revised"}) {
      assertTrue(help.contains(setting), setting);
    }
  }
}
