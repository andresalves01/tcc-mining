package app.pipeline.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The sentence-embedding model the description-similarity chain reads, pinned: all-MiniLM-L6-v2 at
 * one Hugging Face revision, its weights, its configuration and the tokenizer files, each with
 * its SHA-256.
 *
 * <p>The hashes were taken from the files as served at that revision; the weights' matches the
 * object id Hugging Face records for them. A file whose bytes hash otherwise is a different model,
 * or a corrupted one, and is refused at download and again at every load.
 *
 * <p>The model's limit is the 256 tokens sentence-transformers truncates to
 * ({@code max_seq_length}), not the 512 positions the network has: it was trained at 256 or
 * fewer, and the reference vectors the test compares with were produced under that setting.
 */
public final class EmbeddingModel {

  /** The Hugging Face repository. */
  public static final String REPOSITORY = "sentence-transformers/all-MiniLM-L6-v2";

  /** The commit of that repository every file is read at. */
  public static final String REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41";

  /** The most tokens one chunk may hold, the classifier and separator tokens included. */
  public static final int MAX_TOKENS = 256;

  /**
   * The chunking policy {@link MiniLmEmbedder} applies, as the embedding cache keys it: a change to
   * how a text is cut, pooled or combined must change this line, and so empties the cache.
   */
  public static final String CHUNKING = "chunking-1: consecutive chunks of at most " + MAX_TOKENS
      + " tokens, special tokens included; mean pooling over the attention mask at length one; "
      + "chunks averaged weighted by content tokens, at length one";

  /** The number of components of every vector the model gives. */
  public static final int DIMENSION = 384;

  /** The network's weights, relative to the model directory. */
  public static final String WEIGHTS = "model.safetensors";

  /** The network's shape, relative to the model directory. */
  public static final String CONFIG = "config.json";

  /** The pooling sentence-transformers applies, relative to the model directory. */
  public static final String POOLING = "1_Pooling/config.json";

  /** One pinned file: its path in the repository and in the model directory, and its SHA-256. */
  public record File(String path, String sha256) {}

  /** Every file the embedder reads, the weights first. */
  public static final List<File> FILES = List.of(
      new File(WEIGHTS, "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db"),
      new File(CONFIG, "953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41"),
      new File(POOLING, "4be450dde3b0273bb9787637cfbd28fe04a7ba6ab9d36ac48e92b11e350ffc23"),
      new File("vocab.txt", "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
      new File("tokenizer_config.json",
          "acb92769e8195aabd29b7b2137a9e6d6e25c476a4f15aa4355c233426c61576b"),
      new File("special_tokens_map.json",
          "303df45a03609e4ead04bc3dc1536d0ab19b5358db685b6f3da123d05ec200e3"));

  private EmbeddingModel() {}

  /**
   * Refuses unless every file is under {@code dir} with its pinned SHA-256; names each one that is
   * not.
   */
  public static void verify(final Path dir, final List<File> files) throws IOException {
    final List<String> problems = new ArrayList<>();
    for (final File f : files) {
      final Path p = dir.resolve(f.path());
      if (!Files.isRegularFile(p)) {
        problems.add(f.path() + " is missing");
        continue;
      }
      final String actual = sha256(p);
      if (!actual.equals(f.sha256())) {
        problems.add(f.path() + " has SHA-256 " + actual + ", pinned " + f.sha256());
      }
    }
    if (!problems.isEmpty()) {
      throw new ModelFileMismatch("model directory " + dir + " refused: "
          + String.join("; ", problems));
    }
  }

  /** The SHA-256 of a file, in lowercase hexadecimal, read as a stream. */
  public static String sha256(final Path file) throws IOException {
    final MessageDigest digest = digest();
    try (InputStream in = Files.newInputStream(file)) {
      final byte[] buffer = new byte[1 << 16];
      for (int n; (n = in.read(buffer)) > 0;) {
        digest.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** The SHA-256 of some bytes, in lowercase hexadecimal. */
  public static String sha256(final byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java platform has SHA-256", e);
    }
  }
}
