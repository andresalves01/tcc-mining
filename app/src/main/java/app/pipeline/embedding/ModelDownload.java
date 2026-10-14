package app.pipeline.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Fetches the pinned model files into a directory, each checked by SHA-256 before it takes its
 * place.
 *
 * <p>A file is written to a temporary name beside its destination while its hash is computed, and
 * moved into place only when the hash is the pinned one; otherwise the temporary file is deleted and
 * the download refused, so the directory never holds a file that was not verified. A file already
 * present with the pinned hash is kept and not fetched again; one present with another hash is
 * deleted and fetched again.
 */
public final class ModelDownload {

  /** Where the files come from: Hugging Face, or a fake in the tests. */
  @FunctionalInterface
  public interface Source {
    /** The bytes of {@code path} in the repository at the pinned revision. */
    InputStream open(String path) throws IOException;
  }

  /** What became of one file. */
  public record Outcome(String path, boolean fetched, long bytes) {}

  private final List<EmbeddingModel.File> files;
  private final Source source;

  public ModelDownload(final List<EmbeddingModel.File> files, final Source source) {
    this.files = List.copyOf(files);
    this.source = source;
  }

  /** The pinned files, from Hugging Face at the pinned revision. */
  public static ModelDownload pinned() {
    return new ModelDownload(EmbeddingModel.FILES, huggingFace());
  }

  /**
   * Installs every file under {@code dir}, verified; refuses at the first file whose SHA-256 is not
   * the pinned one, leaving the files already verified in place.
   */
  public List<Outcome> into(final Path dir) throws IOException {
    final List<Outcome> out = new ArrayList<>();
    for (final EmbeddingModel.File f : files) {
      final Path target = dir.resolve(f.path());
      if (Files.isRegularFile(target)) {
        if (EmbeddingModel.sha256(target).equals(f.sha256())) {
          out.add(new Outcome(f.path(), false, Files.size(target)));
          continue;
        }
        Files.delete(target);
      }
      Files.createDirectories(target.getParent());
      final Path partial = target.resolveSibling(target.getFileName() + ".partial");
      try {
        final MessageDigest digest = EmbeddingModel.digest();
        try (InputStream in = new DigestInputStream(source.open(f.path()), digest)) {
          Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        final String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(f.sha256())) {
          throw new ModelFileMismatch(f.path() + " downloaded with SHA-256 " + actual
              + ", pinned " + f.sha256() + "; not installed");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        out.add(new Outcome(f.path(), true, Files.size(target)));
      } finally {
        Files.deleteIfExists(partial);
      }
    }
    return out;
  }

  /** The files as Hugging Face serves them at {@link EmbeddingModel#REVISION}. */
  public static Source huggingFace() {
    final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();
    return path -> {
      final URI uri = URI.create("https://huggingface.co/" + EmbeddingModel.REPOSITORY
          + "/resolve/" + EmbeddingModel.REVISION + "/" + path);
      final HttpResponse<InputStream> response;
      try {
        // The timeout bounds the wait for the answer, not the transfer of the 90 MB body.
        response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET()
            .build(),
            HttpResponse.BodyHandlers.ofInputStream());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while fetching " + uri, e);
      }
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IOException(uri + " answered HTTP " + response.statusCode());
      }
      return response.body();
    };
  }
}
