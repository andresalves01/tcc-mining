package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pipeline.embedding.EmbeddingModel;
import app.pipeline.embedding.ModelDownload;
import app.pipeline.embedding.ModelFileMismatch;
import app.pipeline.embedding.MiniLmEmbedder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Downloads the study's pinned sentence-embedding model into MODEL_DIR,
 * each file checked by SHA-256 before it takes its place. Reads neither the corpus nor Mongo and
 * records nothing; a file already there with the pinned hash is not fetched again.
 */
public final class IssuesDownloadModelCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final String fromEnv = System.getenv(MiniLmEmbedder.MODEL_DIR);
    final String dir = fromEnv == null || fromEnv.isBlank()
        ? System.getProperty(MiniLmEmbedder.MODEL_DIR) : fromEnv;
    if (dir == null || dir.isBlank()) {
      throw new CommandFailedException(List.of("  " + MiniLmEmbedder.MODEL_DIR
          + "=<model directory> is required"));
    }
    System.out.printf("  %s at revision %s into %s%n", EmbeddingModel.REPOSITORY,
        EmbeddingModel.REVISION, dir);
    final List<ModelDownload.Outcome> outcomes;
    try {
      outcomes = ModelDownload.pinned().into(Path.of(dir));
    } catch (final ModelFileMismatch e) {
      throw new CommandFailedException(List.of("  refused: " + e.getMessage()));
    } catch (final IOException e) {
      throw new CommandFailedException(List.of("  could not download into " + dir + ": " + e));
    }
    for (final ModelDownload.Outcome o : outcomes) {
      System.out.printf("    %-26s %,12d bytes  %s%n", o.path(), o.bytes(),
          o.fetched() ? "downloaded, SHA-256 verified" : "already present, SHA-256 verified");
    }
  }
}
