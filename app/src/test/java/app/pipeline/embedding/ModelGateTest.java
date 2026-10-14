package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When the model-gated tests skip, run or fail, decided without the model. */
class ModelGateTest {

  private static final ModelGate.Loader<String> LOADS = dir -> "model in " + dir;
  private static final ModelGate.Loader<String> REFUSES = dir -> {
    throw new IOException("SHA-256 differs");
  };

  @Test
  @DisplayName("without REQUIRE_MODEL an unset MODEL_DIR skips: no model, no failure")
  void skips() {
    final ModelGate.Gate<String> g = ModelGate.open(null, null, LOADS);
    assertNull(g.model());
    assertNull(g.failure());
    assertNull(ModelGate.open(" ", "0", LOADS).failure());
    assertNull(ModelGate.open("", "", LOADS).model());
  }

  @Test
  @DisplayName("with REQUIRE_MODEL=1 an unset MODEL_DIR, or one the loader refuses, is a failure "
      + "naming both variables")
  void fails() {
    final ModelGate.Gate<String> unset = ModelGate.open(null, "1", LOADS);
    assertNull(unset.model());
    assertTrue(unset.failure().contains("REQUIRE_MODEL") && unset.failure().contains("MODEL_DIR"),
        unset.failure());
    final ModelGate.Gate<String> refused = ModelGate.open("/m", "true", REFUSES);
    assertTrue(refused.failure().contains("SHA-256 differs"), refused.failure());
    assertTrue(refused.failure().contains("REQUIRE_MODEL"), refused.failure());
  }

  @Test
  @DisplayName("a MODEL_DIR the loader accepts gives the model, required or not")
  void loads() {
    assertEquals("model in " + Path.of("/m"), ModelGate.open("/m", null, LOADS).model());
    assertEquals("model in " + Path.of("/m"), ModelGate.open("/m", "1", LOADS).model());
  }

  @Test
  @DisplayName("without REQUIRE_MODEL a model the loader refuses is still a failure, as before")
  void refusedUnrequired() {
    assertTrue(ModelGate.open("/m", null, REFUSES).failure().contains("SHA-256 differs"));
  }

  @Test
  @DisplayName("a REQUIRE_MODEL value that is neither on nor off is refused, not read as off")
  void unclear() {
    assertThrows(IllegalArgumentException.class, () -> ModelGate.open(null, "yes please", LOADS));
  }
}
