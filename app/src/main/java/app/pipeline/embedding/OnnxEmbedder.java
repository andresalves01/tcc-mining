package app.pipeline.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import app.issues.Chunks;
import app.issues.Embedder;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * all-MiniLM-L6-v2 run in Java: the Hugging Face tokenizer through DJL, the model's own ONNX
 * export through ONNX Runtime, and the pooling sentence-transformers applies, so the vectors are
 * the model's and not an approximation of it.
 *
 * <p>The text is tokenised once with no special tokens and no truncation, cut by {@link Chunks}
 * into consecutive chunks of at most {@link EmbeddingModel#MAX_TOKENS} tokens with the classifier
 * and separator tokens added to each, and each chunk is run alone (no padding, every position
 * attended, token types all zero). A chunk's vector is the mean of its token vectors over the
 * attention mask at length one; the text's is the chunks' mean weighted by content tokens, at
 * length one. Nothing is truncated, so no part of a description is silently cut off.
 *
 * <p>Loading refuses a directory whose files do not hash to the pinned SHA-256, before either
 * library reads them. Not thread-safe: one instance per thread.
 */
public final class OnnxEmbedder implements Embedder, AutoCloseable {

  /** The environment variable naming the directory the model files are in. */
  public static final String MODEL_DIR = "MODEL_DIR";

  private final HuggingFaceTokenizer tokenizer;
  private final OrtEnvironment environment;
  private final OrtSession session;
  private final long cls;
  private final long sep;
  private final boolean tokenTypes;

  private OnnxEmbedder(final HuggingFaceTokenizer tokenizer, final OrtEnvironment environment,
      final OrtSession session, final long cls, final long sep) throws OrtException {
    this.tokenizer = tokenizer;
    this.environment = environment;
    this.session = session;
    this.cls = cls;
    this.sep = sep;
    this.tokenTypes = session.getInputNames().contains("token_type_ids");
  }

  /** The embedder over the model files in {@code dir}, refused unless each has its pinned hash. */
  public static OnnxEmbedder load(final Path dir) throws IOException {
    EmbeddingModel.verify(dir, EmbeddingModel.FILES);
    final Map<String, String> options = new HashMap<>();
    options.put("addSpecialTokens", "false");
    options.put("truncation", "false");
    options.put("padding", "false");
    final HuggingFaceTokenizer tokenizer =
        HuggingFaceTokenizer.newInstance(dir.resolve(EmbeddingModel.TOKENIZER), options);
    OrtSession session = null;
    try {
      // The tokenizer's own special tokens: what it wraps an empty text in.
      final long[] empty = tokenizer.encode("", true, false).getIds();
      if (empty.length != Chunks.SPECIAL_TOKENS) {
        throw new IOException("the tokenizer wraps a text in " + Arrays.toString(empty)
            + ", not one classifier and one separator token");
      }
      final OrtEnvironment environment = OrtEnvironment.getEnvironment();
      try (OrtSession.SessionOptions settings = new OrtSession.SessionOptions()) {
        session = environment.createSession(dir.resolve(EmbeddingModel.ONNX).toString(),
            settings);
      }
      return new OnnxEmbedder(tokenizer, environment, session, empty[0], empty[1]);
    } catch (final OrtException | RuntimeException | IOException e) {
      try {
        if (session != null) {
          session.close();
        }
      } catch (final OrtException suppressed) {
        e.addSuppressed(suppressed);
      } finally {
        tokenizer.close();
      }
      throw e instanceof IOException io ? io
          : new IOException("could not load " + dir.resolve(EmbeddingModel.ONNX), e);
    }
  }

  /** The token ids of each chunk {@code text} is embedded in, special tokens included. */
  public List<long[]> chunks(final String text) {
    final long[] content = tokenizer.encode(text, false, false).getIds();
    return Chunks.split(content, EmbeddingModel.MAX_TOKENS, cls, sep);
  }

  @Override
  public Embedding embed(final String text) {
    final List<long[]> chunks = chunks(text);
    final List<float[]> vectors = new ArrayList<>(chunks.size());
    final int[] content = new int[chunks.size()];
    for (int c = 0; c < chunks.size(); c++) {
      vectors.add(run(chunks.get(c)));
      content[c] = chunks.get(c).length - Chunks.SPECIAL_TOKENS;
    }
    return new Embedding(Chunks.combine(vectors, content), chunks.size());
  }

  private float[] run(final long[] ids) {
    final long[] shape = {1, ids.length};
    final long[] mask = new long[ids.length];
    Arrays.fill(mask, 1);
    final Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape));
      inputs.put("attention_mask",
          OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape));
      if (tokenTypes) {
        inputs.put("token_type_ids",
            OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[ids.length]), shape));
      }
      try (OrtSession.Result result = session.run(inputs)) {
        final float[][][] hidden = (float[][][]) result.get(0).getValue();
        return Chunks.meanPool(hidden[0], mask);
      }
    } catch (final OrtException e) {
      throw new IllegalStateException("ONNX Runtime failed on a chunk of " + ids.length
          + " tokens", e);
    } finally {
      inputs.values().forEach(OnnxTensor::close);
    }
  }

  @Override
  public void close() throws OrtException {
    try {
      session.close();
    } finally {
      tokenizer.close();
    }
  }
}
