package app.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The same-model calibration is a command of its own, described as the official intervals. */
class SameModelCommandTest {

  @Test
  @DisplayName("issues-first-commit-same-model-calibration is a command, its help the official "
      + "prediction intervals, chosen before the opening, never a sensitivity")
  void registered() {
    final Command c = Command.parse("issues-first-commit-same-model-calibration").orElseThrow();
    final String help = c.help().replaceAll("\\s+", " ");
    assertTrue(help.contains("the official prediction intervals"), help);
    assertTrue(help.contains("chosen before the outer test was opened"), help);
    assertTrue(help.contains("approximation"), help);
    assertTrue(help.contains("every inner validation issue"), help);
    assertTrue(!help.contains("SENSITIVITY") && !help.contains("chooses nothing"), help);
  }
}
