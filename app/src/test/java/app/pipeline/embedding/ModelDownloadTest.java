package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The download refuses a file whose SHA-256 is not the pinned one, and installs the rest. */
class ModelDownloadTest {

  /** Serves fixed bytes per file and remembers what was asked. */
  private static final class Fake implements ModelDownload.Source {
    private final Map<String, String> served;
    private final List<String> asked = new ArrayList<>();

    Fake(final Map<String, String> served) {
      this.served = served;
    }

    @Override
    public java.io.InputStream open(final String path) {
      asked.add(path);
      return new ByteArrayInputStream(served.get(path).getBytes(StandardCharsets.US_ASCII));
    }
  }

  private static List<EmbeddingModel.File> pinned(final String model, final String vocab) {
    return List.of(new EmbeddingModel.File("onnx/model.onnx", EmbeddingModel.sha256(
        model.getBytes(StandardCharsets.US_ASCII))), new EmbeddingModel.File("vocab.txt",
        EmbeddingModel.sha256(vocab.getBytes(StandardCharsets.US_ASCII))));
  }

  @Test
  @DisplayName("the SHA-256 of a known text is the one sha256sum prints")
  void knownHash() {
    assertEquals("9372c470eeadd5ecd9c3c74c2b3cb633f8e2f2fad799250a0f70d652b6b825e4",
        EmbeddingModel.sha256("model".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  @DisplayName("files whose SHA-256 is the pinned one are installed under the directory")
  void installs(@TempDir final Path dir) throws IOException {
    final Fake source = new Fake(Map.of("onnx/model.onnx", "model", "vocab.txt", "vocab"));
    new ModelDownload(pinned("model", "vocab"), source).into(dir);

    assertArrayEquals("model".getBytes(StandardCharsets.US_ASCII),
        Files.readAllBytes(dir.resolve("onnx/model.onnx")));
    assertArrayEquals("vocab".getBytes(StandardCharsets.US_ASCII),
        Files.readAllBytes(dir.resolve("vocab.txt")));
  }

  @Test
  @DisplayName("a file with a wrong SHA-256 is refused at download and never left in the directory")
  void refusesWrongHash(@TempDir final Path dir) {
    final Fake source = new Fake(Map.of("onnx/model.onnx", "model", "vocab.txt", "tampered"));
    final ModelFileMismatch e = assertThrows(ModelFileMismatch.class,
        () -> new ModelDownload(pinned("model", "vocab"), source).into(dir));

    assertTrue(e.getMessage().contains("vocab.txt"), e.getMessage());
    assertFalse(Files.exists(dir.resolve("vocab.txt")));
    try (var left = Files.walk(dir)) {
      assertEquals(List.of(), left.filter(Files::isRegularFile)
          .filter(p -> !p.endsWith("onnx/model.onnx")).toList());
    } catch (final IOException io) {
      throw new AssertionError(io);
    }
  }

  @Test
  @DisplayName("a file already present with the pinned SHA-256 is not fetched again")
  void keepsVerified(@TempDir final Path dir) throws IOException {
    Files.writeString(dir.resolve("vocab.txt"), "vocab", StandardCharsets.US_ASCII);
    final Fake source = new Fake(Map.of("onnx/model.onnx", "model", "vocab.txt", "vocab"));
    new ModelDownload(pinned("model", "vocab"), source).into(dir);

    assertEquals(List.of("onnx/model.onnx"), source.asked);
  }

  @Test
  @DisplayName("a file already present with another SHA-256 is fetched again and replaced")
  void replacesCorrupted(@TempDir final Path dir) throws IOException {
    Files.writeString(dir.resolve("vocab.txt"), "corrupted", StandardCharsets.US_ASCII);
    final Fake source = new Fake(Map.of("onnx/model.onnx", "model", "vocab.txt", "vocab"));
    new ModelDownload(pinned("model", "vocab"), source).into(dir);

    assertEquals("vocab", Files.readString(dir.resolve("vocab.txt")));
  }

  @Test
  @DisplayName("a file already present with another SHA-256 is gone even when its download is "
      + "refused")
  void dropsCorruptedOnRefusal(@TempDir final Path dir) throws IOException {
    Files.writeString(dir.resolve("vocab.txt"), "corrupted", StandardCharsets.US_ASCII);
    final Fake source = new Fake(Map.of("onnx/model.onnx", "model", "vocab.txt", "tampered"));
    assertThrows(ModelFileMismatch.class,
        () -> new ModelDownload(pinned("model", "vocab"), source).into(dir));

    assertFalse(Files.exists(dir.resolve("vocab.txt")));
  }

  @Test
  @DisplayName("verifying a directory refuses a file with a wrong SHA-256 or a missing one")
  void verify(@TempDir final Path dir) throws IOException {
    Files.createDirectories(dir.resolve("onnx"));
    Files.writeString(dir.resolve("onnx/model.onnx"), "model", StandardCharsets.US_ASCII);
    Files.writeString(dir.resolve("vocab.txt"), "tampered", StandardCharsets.US_ASCII);
    final ModelFileMismatch wrong = assertThrows(ModelFileMismatch.class,
        () -> EmbeddingModel.verify(dir, pinned("model", "vocab")));
    assertTrue(wrong.getMessage().contains("vocab.txt"), wrong.getMessage());

    Files.delete(dir.resolve("vocab.txt"));
    final ModelFileMismatch missing = assertThrows(ModelFileMismatch.class,
        () -> EmbeddingModel.verify(dir, pinned("model", "vocab")));
    assertTrue(missing.getMessage().contains("vocab.txt"), missing.getMessage());
  }

  @Test
  @DisplayName("the pinned files are the ONNX model and the tokenizer files of the pinned revision")
  void pinnedFiles() {
    assertEquals("sentence-transformers/all-MiniLM-L6-v2", EmbeddingModel.REPOSITORY);
    assertEquals("1110a243fdf4706b3f48f1d95db1a4f5529b4d41", EmbeddingModel.REVISION);
    assertEquals(List.of("onnx/model.onnx", "tokenizer.json", "tokenizer_config.json",
        "special_tokens_map.json", "vocab.txt"),
        EmbeddingModel.FILES.stream().map(EmbeddingModel.File::path).toList());
    // Hugging Face's own record of the ONNX file at that revision (its LFS object id).
    assertEquals("6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452",
        EmbeddingModel.FILES.get(0).sha256());
  }
}
