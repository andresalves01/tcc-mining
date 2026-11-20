package app.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The same-model calibration is a command of its own, described as the official intervals. */
class SameModelCommandTest {

  @Test
  @DisplayName("issues-first-commit-same-model-calibration is a command, its help the revised "
      + "design's official prediction intervals, chosen before the opening, never a sensitivity, "
      + "with its settings")
  void registered() {
    final Command c = Command.parse("issues-first-commit-same-model-calibration").orElseThrow();
    final String help = c.help();
    assertTrue(help.contains("OFFICIAL PREDICTION INTERVALS"), help);
    assertTrue(help.contains("chosen before the outer test was opened"), help);
    assertTrue(help.contains("approximation"), help);
    assertTrue(help.contains("every inner validation issue"), help);
    assertTrue(!help.contains("SENSITIVITY") && !help.contains("chooses nothing"), help);
    for (final String setting : new String[] {"MATRIX", "ELASTIC_NET_PARAMETERS",
        "TEST_PREDICTIONS", "REPORT_DIR", "SPLIT_RULE=revised",
        "issues-first-commit-test-revised"}) {
      assertTrue(help.contains(setting), setting);
    }
  }
}
