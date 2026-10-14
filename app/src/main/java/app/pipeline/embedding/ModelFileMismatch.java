package app.pipeline.embedding;

import java.io.IOException;

/** A model file that is missing or whose SHA-256 is not the pinned one: refused, never used. */
public final class ModelFileMismatch extends IOException {

  private static final long serialVersionUID = 1L;

  ModelFileMismatch(final String message) {
    super(message);
  }
}
