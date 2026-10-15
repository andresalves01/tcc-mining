package app.pipeline.embedding;

import app.issues.Chunks;
import app.issues.Embedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * all-MiniLM-L6-v2 run in plain Java, with no native code: {@link WordPieceTokenizer} gives the
 * token ids the Hugging Face tokenizer gives, {@link BertEncoder} runs the network on the model's
 * own weights, and the pooling is sentence-transformers', so the vectors are the model's and not an
 * approximation of it.
 *
 * <p>The text is tokenised once with no special tokens and no truncation, cut by {@link Chunks}
 * into consecutive chunks of at most {@link EmbeddingModel#MAX_TOKENS} tokens with the classifier
 * and separator tokens added to each, and each chunk is run alone (no padding, every position
 * attended, token types all zero). A chunk's vector is the mean of its token vectors at length
 * one; the text's is the chunks' mean weighted by content tokens, at length one. Nothing is
 * truncated, so no part of a description is silently cut off.
 *
 * <p>Loading refuses a directory whose files do not hash to the pinned SHA-256, before any is
 * read, and a pooling other than the mean of the token vectors. Immutable: one instance may embed
 * from many threads at once, and a text's vector does not depend on which thread, or how many,
 * computed it.
 */
public final class MiniLmEmbedder implements Embedder {

  /** The environment variable naming the directory the model files are in. */
  public static final String MODEL_DIR = "MODEL_DIR";

  /**
   * This implementation, as the embedding cache keys it and the run records it: a change to the
   * tokenizer or the network's arithmetic that could move a vector must change this line, so
   * vectors of two implementations are never mixed.
   */
  public static final String IMPLEMENTATION = "java-minilm-1";

  private final WordPieceTokenizer tokenizer;
  private final BertEncoder encoder;

  private MiniLmEmbedder(final WordPieceTokenizer tokenizer, final BertEncoder encoder) {
    this.tokenizer = tokenizer;
    this.encoder = encoder;
  }

  /** The embedder over the model files in {@code dir}, refused unless each has its pinned hash. */
  public static MiniLmEmbedder load(final Path dir) throws IOException {
    EmbeddingModel.verify(dir, EmbeddingModel.FILES);
    meanPooling(dir.resolve(EmbeddingModel.POOLING));
    final WordPieceTokenizer tokenizer = WordPieceTokenizer.load(dir);
    final BertEncoder encoder = BertEncoder.load(dir.resolve(EmbeddingModel.CONFIG),
        dir.resolve(EmbeddingModel.WEIGHTS));
    if (tokenizer.vocabularySize() != encoder.vocabularySize()) {
      throw new IOException(dir + " refused: " + WordPieceTokenizer.VOCAB + " holds "
          + tokenizer.vocabularySize() + " tokens, " + EmbeddingModel.CONFIG + " "
          + encoder.vocabularySize());
    }
    if (encoder.dimension() != EmbeddingModel.DIMENSION) {
      throw new IOException(dir.resolve(EmbeddingModel.CONFIG) + " refused: a width of "
          + encoder.dimension() + ", not " + EmbeddingModel.DIMENSION);
    }
    return new MiniLmEmbedder(tokenizer, encoder);
  }

  /** Refuses a pooling configuration other than the mean of the token vectors at the width. */
  private static void meanPooling(final Path file) throws IOException {
    final JsonNode c = new ObjectMapper().readTree(file.toFile());
    final List<String> problems = new ArrayList<>();
    if (c == null || !c.isObject()) {
      throw new IOException(file + " refused: not a JSON object");
    }
    if (c.path("word_embedding_dimension").asInt() != EmbeddingModel.DIMENSION) {
      problems.add("word_embedding_dimension is " + c.get("word_embedding_dimension"));
    }
    for (final Iterator<String> it = c.fieldNames(); it.hasNext();) {
      final String key = it.next();
      if (key.startsWith("pooling_mode_")
          && c.get(key).asBoolean() != key.equals("pooling_mode_mean_tokens")) {
        problems.add(key + " is " + c.get(key));
      }
    }
    if (!c.path("pooling_mode_mean_tokens").asBoolean()) {
      problems.add("pooling_mode_mean_tokens is " + c.get("pooling_mode_mean_tokens"));
    }
    if (!problems.isEmpty()) {
      throw new IOException(file + " refused: only mean pooling is implemented; "
          + String.join("; ", problems));
    }
  }

  /** The token ids of each chunk {@code text} is embedded in, special tokens included. */
  public List<long[]> chunks(final String text) {
    return Chunks.split(tokenizer.encode(text), EmbeddingModel.MAX_TOKENS, tokenizer.cls(),
        tokenizer.sep());
  }

  @Override
  public Embedding embed(final String text) {
    final List<long[]> chunks = chunks(text);
    final List<float[]> vectors = new ArrayList<>(chunks.size());
    final int[] content = new int[chunks.size()];
    for (int c = 0; c < chunks.size(); c++) {
      final long[] ids = chunks.get(c);
      final long[] mask = new long[ids.length];
      Arrays.fill(mask, 1);
      vectors.add(Chunks.meanPool(encoder.hidden(ids), mask));
      content[c] = ids.length - Chunks.SPECIAL_TOKENS;
    }
    return new Embedding(Chunks.combine(vectors, content), chunks.size());
  }
}
