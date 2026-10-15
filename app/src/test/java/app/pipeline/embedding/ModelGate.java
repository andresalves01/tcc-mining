package app.pipeline.embedding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Whether the tests that need the model on disk run, skip or fail. MODEL_DIR names the model's
 * directory; unset, those tests skip, so a plain test run needs no download. REQUIRE_MODEL=1 (or
 * {@code true}) turns that skip into a failure, for a run that must show the model was compared;
 * {@code 0}, {@code false} or unset leave it off, and any other value is refused rather than read
 * as off. A directory the loader refuses fails either way. Both are read from the environment,
 * then from the system properties.
 */
final class ModelGate {

  /** The environment variable that makes the model-gated tests fail instead of skipping. */
  static final String REQUIRE_MODEL = "REQUIRE_MODEL";

  /** Loads the model from a directory. */
  @FunctionalInterface
  interface Loader<M> {
    M load(Path dir) throws IOException;
  }

  /**
   * @param model the loaded model, null when the tests skip or fail
   * @param failure why the tests fail, null when they run or skip
   */
  record Gate<M>(M model, String failure) {}

  private ModelGate() {}

  /** The gate MODEL_DIR and REQUIRE_MODEL in the environment, or the properties, give. */
  static <M> Gate<M> fromEnvironment(final Loader<M> loader) {
    return open(setting(MiniLmEmbedder.MODEL_DIR), setting(REQUIRE_MODEL), loader);
  }

  /** The gate a MODEL_DIR and a REQUIRE_MODEL value give, either possibly null. */
  static <M> Gate<M> open(final String dir, final String require, final Loader<M> loader) {
    final boolean required = required(require);
    if (dir == null || dir.isBlank()) {
      return new Gate<>(null, required ? REQUIRE_MODEL + " is set but " + MiniLmEmbedder.MODEL_DIR
          + " is unset: the comparison with sentence-transformers needs the model on disk (run "
          + "issues-download-model into a directory and set " + MiniLmEmbedder.MODEL_DIR
          + " to it)" : null);
    }
    try {
      return new Gate<>(loader.load(Path.of(dir)), null);
    } catch (final IOException | RuntimeException e) {
      return new Gate<>(null, MiniLmEmbedder.MODEL_DIR + "=" + dir + " holds no valid model ("
          + e.getMessage() + ")" + (required ? "; " + REQUIRE_MODEL + " is set" : ""));
    }
  }

  static boolean required(final String value) {
    final String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "1", "true" -> true;
      case "", "0", "false" -> false;
      default -> throw new IllegalArgumentException(REQUIRE_MODEL + "=" + value
          + " is neither 1/true nor 0/false");
    };
  }

  private static String setting(final String name) {
    final String fromEnv = System.getenv(name);
    return fromEnv == null || fromEnv.isBlank() ? System.getProperty(name) : fromEnv;
  }
}
