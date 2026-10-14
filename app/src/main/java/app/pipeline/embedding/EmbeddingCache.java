package app.pipeline.embedding;

import app.issues.DescriptionCleaner;
import app.issues.Embedder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The embeddings of descriptions already embedded, kept on disk so a rerun need not run the model
 * again: an {@link Embedder} that answers from the file and asks the model only for a text it has
 * not seen under the same key.
 *
 * <p><b>Key.</b> A vector depends on the text and on everything that turned it into the vector:
 * the model, its revision, the chunking policy and the cleaner version that produced the text. The
 * last four name the file, by the SHA-256 of their line, so a change in any of them reads a
 * different, empty file and the old one is left as it was; the text keys a line within it, by its
 * own SHA-256 in UTF-8.
 *
 * <p><b>File.</b> {@code embeddings-<sha256>.tsv} in the cache directory. The first line is
 * {@link #FORMAT} and the key, tab-separated, and is checked on open: a file whose header is not
 * its key's is refused, never read. Every other line is the text's SHA-256, the chunk count and the
 * vector as Base64 of its floats' IEEE 754 bits, big-endian, so it reads back bit for bit. A
 * malformed line, or a text given twice, refuses the file too. The vectors are kept as the model
 * gave them; whether one is a valid unit vector is decided where it is read, by the stage.
 *
 * <p>Nothing is written until {@link #save}, which writes every entry, sorted by hash, to a
 * temporary file beside the cache and moves it into place, so a run that stops halfway leaves the
 * previous file whole. Not thread-safe, as the model it wraps.
 */
public final class EmbeddingCache implements Embedder {

  /** The environment variable naming the cache directory. */
  public static final String ENV = "EMBEDDING_CACHE";

  /** The version of the file layout, the first field of its header. */
  public static final String FORMAT = "embedding-cache-1";

  /** What, besides the text, decides a vector. Tabs and line breaks are not allowed in a part. */
  public record Key(String model, String revision, String chunking, String cleaner) {

    public Key {
      for (final String part : List.of(model, revision, chunking, cleaner)) {
        if (part.contains("\t") || part.contains("\n") || part.contains("\r")) {
          throw new IllegalArgumentException("a key part holds a tab or line break: " + part);
        }
      }
    }

    String line() {
      return String.join("\t", "model=" + model, "revision=" + revision, "chunking=" + chunking,
          "cleaner=" + cleaner);
    }
  }

  private final Path file;
  private final String header;
  private final Embedder model;
  private final Map<String, Embedding> entries;
  private int hits;
  private int misses;
  private long modelNanos;

  private EmbeddingCache(final Path file, final String header, final Embedder model,
      final Map<String, Embedding> entries) {
    this.file = file;
    this.header = header;
    this.model = model;
    this.entries = entries;
  }

  /** The key of the pinned model and of the current cleaner. */
  public static Key pinned() {
    return new Key(EmbeddingModel.REPOSITORY, EmbeddingModel.REVISION, EmbeddingModel.CHUNKING,
        DescriptionCleaner.VERSION);
  }

  /** The cache of {@code key} in {@code dir}, empty when it has no file yet. */
  public static EmbeddingCache open(final Path dir, final Key key, final Embedder model)
      throws IOException {
    final String header = FORMAT + "\t" + key.line();
    final Path file = dir.resolve("embeddings-"
        + EmbeddingModel.sha256(key.line().getBytes(StandardCharsets.UTF_8)) + ".tsv");
    final Map<String, Embedding> entries = new TreeMap<>();
    if (Files.exists(file)) {
      final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      if (lines.isEmpty() || !lines.get(0).equals(header)) {
        throw new IOException(file + " is not the embedding cache of " + header.replace('\t', ' ')
            + "; its header reads " + (lines.isEmpty() ? "nothing" : lines.get(0)));
      }
      for (int n = 1; n < lines.size(); n++) {
        if (entries.put(hashOf(file, n + 1, lines.get(n)), parse(file, n + 1, lines.get(n)))
            != null) {
          throw malformed(file, n + 1, "a text already given on an earlier line");
        }
      }
    }
    return new EmbeddingCache(file, header, model, entries);
  }

  @Override
  public Embedding embed(final String text) {
    final String hash = EmbeddingModel.sha256(text.getBytes(StandardCharsets.UTF_8));
    final Embedding known = entries.get(hash);
    if (known != null) {
      hits++;
      return new Embedding(known.vector().clone(), known.chunks());
    }
    misses++;
    final long began = System.nanoTime();
    final Embedding e = model.embed(text);
    modelNanos += System.nanoTime() - began;
    if (e != null && e.vector() != null) {
      entries.put(hash, new Embedding(e.vector().clone(), e.chunks()));
    }
    return e;
  }

  /** Writes every entry, replacing the file in one move. */
  public void save() throws IOException {
    Files.createDirectories(file.getParent());
    final Path partial = file.resolveSibling(file.getFileName() + ".partial");
    try {
      try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
        out.write(header);
        out.newLine();
        for (final Map.Entry<String, Embedding> e : entries.entrySet()) {
          out.write(e.getKey() + "\t" + e.getValue().chunks() + "\t"
              + encode(e.getValue().vector()));
          out.newLine();
        }
      }
      Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /** The file this cache reads and writes. */
  public Path file() {
    return file;
  }

  /** Texts answered from the cache. */
  public int hits() {
    return hits;
  }

  /** Texts the model was asked for. */
  public int misses() {
    return misses;
  }

  /** The time spent in the model, on the misses. */
  public double modelSeconds() {
    return modelNanos / 1e9;
  }

  private static String encode(final float[] v) {
    final ByteBuffer bytes = ByteBuffer.allocate(Float.BYTES * v.length);
    for (final float x : v) {
      bytes.putInt(Float.floatToRawIntBits(x));
    }
    return Base64.getEncoder().encodeToString(bytes.array());
  }

  private static String hashOf(final Path file, final int line, final String text)
      throws IOException {
    final String hash = fields(file, line, text)[0];
    if (!hash.matches("[0-9a-f]{64}")) {
      throw malformed(file, line, "no SHA-256 in the first field");
    }
    return hash;
  }

  private static Embedding parse(final Path file, final int line, final String text)
      throws IOException {
    final String[] f = fields(file, line, text);
    try {
      final int chunks = Integer.parseInt(f[1]);
      if (chunks < 1) {
        throw malformed(file, line, chunks + " chunks");
      }
      final byte[] bytes = Base64.getDecoder().decode(f[2]);
      if (bytes.length % Float.BYTES != 0) {
        throw malformed(file, line, bytes.length + " bytes are not whole floats");
      }
      final ByteBuffer in = ByteBuffer.wrap(bytes);
      final float[] v = new float[bytes.length / Float.BYTES];
      for (int d = 0; d < v.length; d++) {
        v[d] = Float.intBitsToFloat(in.getInt());
      }
      return new Embedding(v, chunks);
    } catch (final IllegalArgumentException e) {
      throw malformed(file, line, e.getMessage());
    }
  }

  private static String[] fields(final Path file, final int line, final String text)
      throws IOException {
    final String[] f = text.split("\t", -1);
    if (f.length != 3) {
      throw malformed(file, line, f.length + " fields, expected 3");
    }
    return f;
  }

  private static IOException malformed(final Path file, final int line, final String why) {
    return new IOException(file + " line " + line + " is malformed (" + why + "); delete the "
        + "file to embed again");
  }
}
