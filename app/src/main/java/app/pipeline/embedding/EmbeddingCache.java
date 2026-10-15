package app.pipeline.embedding;

import app.issues.DescriptionCleaner;
import app.issues.Embedder;
import app.model.Parallel;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The embeddings of descriptions already embedded, kept on disk so a rerun need not run the model
 * again: an {@link Embedder} that answers from the file and asks the model only for a text it has
 * not seen under the same key.
 *
 * <p><b>Key.</b> A vector depends on the text and on everything that turned it into the vector:
 * the model, its revision, the implementation that ran it, the chunking policy and the cleaner
 * version that produced the text. The last five name the file, by the SHA-256 of their line, so a
 * change in any of them reads a different, empty file and the old one is left as it was; the text
 * keys a line within it, by its own SHA-256 in UTF-8.
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
 * previous file whole.
 *
 * <p><b>Threads.</b> {@link #embedAll} asks the model for the texts it misses on up to the
 * threads it was opened with, each text on one thread, computed alone in the model's own fixed
 * order, so a vector is the same bits however many threads ran. Everything else -- the counts,
 * the map, the order the answers are read in -- stays on the calling thread and follows the texts'
 * order, exactly as embedding them one by one would: the same vectors in the same order, the same
 * hits and misses, the same entries, and a failure surfacing at the text it would have stopped at,
 * with the texts before it kept and none after. The model is asked once per distinct text: a
 * repeated text it gave no vector for is answered again from that one call, which the loop would
 * have asked anew of a model that, being deterministic, answers the same. One thread is that
 * loop, and a supported setting. The cache itself is not thread-safe: it is called from one
 * thread.
 */
public final class EmbeddingCache implements Embedder {

  /** The environment variable naming the cache directory. */
  public static final String ENV = "EMBEDDING_CACHE";

  /** The version of the file layout, the first field of its header. */
  public static final String FORMAT = "embedding-cache-1";

  /** What, besides the text, decides a vector. Tabs and line breaks are not allowed in a part. */
  public record Key(String model, String revision, String implementation, String chunking,
      String cleaner) {

    public Key {
      for (final String part : List.of(model, revision, implementation, chunking, cleaner)) {
        if (part.contains("\t") || part.contains("\n") || part.contains("\r")) {
          throw new IllegalArgumentException("a key part holds a tab or line break: " + part);
        }
      }
    }

    String line() {
      return String.join("\t", "model=" + model, "revision=" + revision,
          "implementation=" + implementation, "chunking=" + chunking, "cleaner=" + cleaner);
    }
  }

  private final Path file;
  private final String header;
  private final Embedder model;
  private final Map<String, Embedding> entries;
  private final int threads;
  private int hits;
  private int misses;
  private long modelNanos;

  private EmbeddingCache(final Path file, final String header, final Embedder model,
      final Map<String, Embedding> entries, final int threads) {
    this.file = file;
    this.header = header;
    this.model = model;
    this.entries = entries;
    this.threads = threads;
  }

  /** The key of the pinned model, run by {@link MiniLmEmbedder}, and of the current cleaner. */
  public static Key pinned() {
    return new Key(EmbeddingModel.REPOSITORY, EmbeddingModel.REVISION,
        MiniLmEmbedder.IMPLEMENTATION, EmbeddingModel.CHUNKING, DescriptionCleaner.VERSION);
  }

  /**
   * The cache of {@code key} in {@code dir}, empty when it has no file yet, asking the model on
   * one thread.
   */
  public static EmbeddingCache open(final Path dir, final Key key, final Embedder model)
      throws IOException {
    return open(dir, key, model, 1);
  }

  /**
   * The cache of {@code key} in {@code dir}, empty when it has no file yet, asking the model for
   * the texts {@link #embedAll} misses on up to {@code threads} threads; {@code model} must then
   * be safe to call from several threads at once, as {@link MiniLmEmbedder} is.
   */
  public static EmbeddingCache open(final Path dir, final Key key, final Embedder model,
      final int threads) throws IOException {
    if (threads < 1) {
      throw new IllegalArgumentException(threads + " threads: at least one is needed");
    }
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
    return new EmbeddingCache(file, header, model, entries, threads);
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

  /**
   * The embeddings of {@code texts}, in their order, as embedding them one by one with {@link
   * #embed} gives and leaves them; only the model runs on several threads (see the class).
   */
  @Override
  public List<Embedding> embedAll(final List<String> texts) {
    final String[] hashes = new String[texts.size()];
    final Map<String, Integer> asked = new HashMap<>();
    final List<String> missing = new ArrayList<>();
    for (int i = 0; i < hashes.length; i++) {
      hashes[i] = EmbeddingModel.sha256(texts.get(i).getBytes(StandardCharsets.UTF_8));
      if (!entries.containsKey(hashes[i]) && !asked.containsKey(hashes[i])) {
        asked.put(hashes[i], missing.size());
        missing.add(texts.get(i));
      }
    }
    final List<Answer> answers = ask(missing);
    // read back as embed would have run, text by text: a text the model gave a vector for is a
    // hit when it comes again; one it gave none for is counted a miss every time, as the loop
    // counts it, though the model was asked for it once and its one answer is reused
    final List<Embedding> out = new ArrayList<>(texts.size());
    for (final String hash : hashes) {
      final Embedding known = entries.get(hash);
      if (known != null) {
        hits++;
        out.add(new Embedding(known.vector().clone(), known.chunks()));
        continue;
      }
      misses++;
      final Answer a = answers.get(asked.get(hash));
      if (a.failure() instanceof RuntimeException r) {
        throw r;
      }
      if (a.failure() instanceof Error r) {
        throw r;
      }
      if (a == SKIPPED) {
        throw new IllegalStateException("a text after a failure was read back before it");
      }
      final Embedding e = a.embedding();
      if (e != null && e.vector() != null) {
        entries.put(hash, new Embedding(e.vector().clone(), e.chunks()));
      }
      out.add(e);
    }
    return out;
  }

  /** What the model answered for one text: its embedding, or what it threw. */
  private record Answer(Embedding embedding, Throwable failure) {}

  /** The answer of a text never asked for, because one before it had already failed. */
  private static final Answer SKIPPED = new Answer(null, null);

  /**
   * The model's answers for {@code texts}, in their order, on up to {@link #threads} threads. Once
   * a text has failed, the texts after it are not asked for: the loop would never reach them.
   */
  private List<Answer> ask(final List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    final AtomicInteger failed = new AtomicInteger(Integer.MAX_VALUE);
    final List<Callable<Answer>> jobs = new ArrayList<>(texts.size());
    for (int k = 0; k < texts.size(); k++) {
      final int at = k;
      final String text = texts.get(k);
      jobs.add(() -> {
        if (at > failed.get()) {
          return SKIPPED;
        }
        try {
          return new Answer(model.embed(text), null);
        } catch (final RuntimeException | Error e) {
          failed.accumulateAndGet(at, Math::min);
          return new Answer(null, e);
        }
      });
    }
    final long began = System.nanoTime();
    final List<Answer> answers = Parallel.map(threads, jobs);
    modelNanos += System.nanoTime() - began;
    return answers;
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

  /**
   * The time spent in the model, on the misses: for {@link #embedAll}, the wall time the model's
   * threads took, not the sum of each thread's.
   */
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
