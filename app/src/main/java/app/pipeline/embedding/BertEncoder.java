package app.pipeline.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The BERT encoder of all-MiniLM-L6-v2 run in plain Java: the network Hugging Face's
 * {@code BertModel} computes, from the shape {@code config.json} gives and the weights of
 * {@code model.safetensors}, for one unpadded sequence of token ids.
 *
 * <p><b>Network.</b> Each token starts as the sum of its word, position and token-type embeddings
 * (token type zero throughout, as for a single sentence), layer-normalised. Each layer then runs
 * multi-head self-attention over every position (an unpadded sequence needs no mask): queries,
 * keys and values are linear maps of the input, a head's weights are the softmax of its queries'
 * dot products with its keys divided by the square root of the head width, and the heads' weighted
 * values, joined, pass through a linear map, are added to the layer's input and layer-normalised.
 * A feed-forward block follows: a linear map to the intermediate width, the exact GELU,
 * {@code x * (1 + erf(x / sqrt 2)) / 2}, a linear map back, added to its input and
 * layer-normalised. The output is every token's vector after the last layer; the pooler, which
 * sentence-transformers does not use, is not read.
 *
 * <p><b>Arithmetic.</b> Weights and activations are 32-bit floats as in the original. Every sum
 * runs in a fixed order, each product added with a fused multiply-add, so the same ids give the
 * same vectors bit for bit on every run, whether or not the JIT runs a loop in vector registers.
 * Layer-norm statistics and softmax normalisers are summed in double; the softmax's exponential
 * and the GELU's erf are {@link FloatMath}'s, in float as in the original, within a few units in
 * the last place of the exact functions.
 *
 * <p>The configuration is refused unless it describes exactly this network: a BERT model with the
 * erf GELU, absolute positions, no decoder, heads dividing the width; and the weights unless every
 * tensor has the shape the configuration implies. Immutable once loaded, the weights only read,
 * so one instance may serve many threads at once.
 */
final class BertEncoder {
  /** The block of tokens one pass of a linear map over its weights serves. */
  private static final int TILE_TOKENS = 16;

  /**
   * A linear map: {@code y = W x + b}, its weight matrix kept transposed, one array per input
   * component holding that component's weight in every output.
   */
  private record Linear(float[][] rows, float[] bias) {
    int out() {
      return bias.length;
    }
  }

  /** A layer norm's scale and shift. */
  private record Norm(float[] weight, float[] bias) {}

  /** One encoder layer. */
  private record Layer(Linear query, Linear key, Linear value, Linear attended, Norm attention,
      Linear intermediate, Linear output, Norm feedForward) {}

  private final int vocab;
  private final int positions;
  private final int hidden;
  private final int heads;
  private final double eps;
  private final float[] words;
  private final float[] positionEmbeddings;
  private final float[] tokenType;
  private final Norm embeddingNorm;
  private final List<Layer> layers;

  private BertEncoder(final int vocab, final int positions, final int hidden, final int heads,
      final double eps, final float[] words, final float[] positionEmbeddings,
      final float[] tokenType, final Norm embeddingNorm, final List<Layer> layers) {
    this.vocab = vocab;
    this.positions = positions;
    this.hidden = hidden;
    this.heads = heads;
    this.eps = eps;
    this.words = words;
    this.positionEmbeddings = positionEmbeddings;
    this.tokenType = tokenType;
    this.embeddingNorm = embeddingNorm;
    this.layers = layers;
  }

  /** The encoder {@code config} describes, with the weights of {@code weights}. */
  static BertEncoder load(final Path config, final Path weights) throws IOException {
    final JsonNode c = new ObjectMapper().readTree(config.toFile());
    final List<String> problems = new ArrayList<>();
    if (c == null || !c.isObject()) {
      throw new IOException(config + " refused: not a JSON object");
    }
    if (!c.path("model_type").asText().equals("bert")) {
      problems.add("model_type is " + c.get("model_type") + ", not bert");
    }
    if (!c.path("architectures").isArray() || c.get("architectures").size() != 1
        || !c.get("architectures").get(0).asText().equals("BertModel")) {
      problems.add("architectures is " + c.get("architectures") + ", not [BertModel]");
    }
    if (!c.path("hidden_act").asText().equals("gelu")) {
      problems.add("hidden_act is " + c.get("hidden_act") + "; only gelu, with erf, is "
          + "implemented");
    }
    if (c.has("position_embedding_type")
        && !c.get("position_embedding_type").asText().equals("absolute")) {
      problems.add("position_embedding_type is " + c.get("position_embedding_type")
          + "; only absolute is implemented");
    }
    for (final String flag : List.of("is_decoder", "add_cross_attention")) {
      if (c.path(flag).asBoolean(false)) {
        problems.add(flag + " is true; only an encoder is implemented");
      }
    }
    final int vocab = positive(c, "vocab_size", problems);
    final int positions = positive(c, "max_position_embeddings", problems);
    final int types = positive(c, "type_vocab_size", problems);
    final int hidden = positive(c, "hidden_size", problems);
    final int heads = positive(c, "num_attention_heads", problems);
    final int inter = positive(c, "intermediate_size", problems);
    final int count = positive(c, "num_hidden_layers", problems);
    if (heads > 0 && hidden % heads != 0) {
      problems.add(heads + " heads do not divide a width of " + hidden);
    }
    final double eps = c.path("layer_norm_eps").asDouble(-1);
    if (!c.path("layer_norm_eps").isNumber() || !(eps > 0)) {
      problems.add("layer_norm_eps is " + c.get("layer_norm_eps") + ", not a positive number");
    }
    if (!problems.isEmpty()) {
      throw new IOException(config + " refused: " + String.join("; ", problems));
    }

    final Safetensors w = Safetensors.read(weights);
    final String e = "embeddings.";
    final float[] words = w.floats(e + "word_embeddings.weight", vocab, hidden);
    final float[] pos = w.floats(e + "position_embeddings.weight", positions, hidden);
    final float[] type = w.floats(e + "token_type_embeddings.weight", types, hidden);
    final Norm embeddingNorm = norm(w, e + "LayerNorm", hidden);
    final List<Layer> layers = new ArrayList<>();
    for (int l = 0; l < count; l++) {
      final String p = "encoder.layer." + l + ".";
      layers.add(new Layer(
          linear(w, p + "attention.self.query", hidden, hidden),
          linear(w, p + "attention.self.key", hidden, hidden),
          linear(w, p + "attention.self.value", hidden, hidden),
          linear(w, p + "attention.output.dense", hidden, hidden),
          norm(w, p + "attention.output.LayerNorm", hidden),
          linear(w, p + "intermediate.dense", hidden, inter),
          linear(w, p + "output.dense", inter, hidden),
          norm(w, p + "output.LayerNorm", hidden)));
    }
    final float[] typeZero = new float[hidden];
    System.arraycopy(type, 0, typeZero, 0, hidden);
    return new BertEncoder(vocab, positions, hidden, heads, eps, words, pos, typeZero,
        embeddingNorm, List.copyOf(layers));
  }

  private static int positive(final JsonNode c, final String key, final List<String> problems) {
    if (!c.path(key).isIntegralNumber() || !c.get(key).canConvertToInt()
        || c.get(key).asInt() < 1) {
      problems.add(key + " is " + c.get(key) + ", not a positive integer");
      return 0;
    }
    return c.get(key).asInt();
  }

  private static Linear linear(final Safetensors w, final String name, final int in,
      final int out) throws IOException {
    final float[] weight = w.floats(name + ".weight", out, in);
    final float[][] rows = new float[in][out];
    for (int o = 0; o < out; o++) {
      for (int i = 0; i < in; i++) {
        rows[i][o] = weight[o * in + i];
      }
    }
    return new Linear(rows, w.floats(name + ".bias", out));
  }

  private static Norm norm(final Safetensors w, final String name, final int width)
      throws IOException {
    return new Norm(w.floats(name + ".weight", width), w.floats(name + ".bias", width));
  }

  /** The number of token ids the word embeddings cover, from zero. */
  int vocabularySize() {
    return vocab;
  }

  /** The width of every token's output vector. */
  int dimension() {
    return hidden;
  }

  /** Every token's vector after the last layer, for one unpadded sequence of token ids. */
  float[][] hidden(final long[] ids) {
    final int n = ids.length;
    if (n == 0 || n > positions) {
      throw new IllegalArgumentException(n + " tokens; the model takes 1 to " + positions);
    }
    final int h = hidden;
    final float[][] x = new float[n][h];
    for (int t = 0; t < n; t++) {
      if (ids[t] < 0 || ids[t] >= vocab) {
        throw new IllegalArgumentException("token id " + ids[t] + " is outside the vocabulary of "
            + vocab);
      }
      final int word = (int) ids[t] * h;
      for (int d = 0; d < h; d++) {
        x[t][d] = words[word + d] + positionEmbeddings[t * h + d] + tokenType[d];
      }
      layerNorm(x[t], embeddingNorm);
    }

    final float[][] q = new float[n][h];
    final float[][] k = new float[n][h];
    final float[][] v = new float[n][h];
    final float[][] context = new float[n][h];
    final float[][] attended = new float[n][h];
    float[][] inter = null;
    for (final Layer layer : layers) {
      apply(layer.query(), x, q);
      apply(layer.key(), x, k);
      apply(layer.value(), x, v);
      attention(q, k, v, context);
      apply(layer.attended(), context, attended);
      for (int t = 0; t < n; t++) {
        addInto(attended[t], x[t]);
        layerNorm(x[t], layer.attention());
      }

      if (inter == null || inter[0].length != layer.intermediate().out()) {
        inter = new float[n][layer.intermediate().out()];
      }
      apply(layer.intermediate(), x, inter);
      for (final float[] row : inter) {
        gelu(row);
      }
      apply(layer.output(), inter, attended);
      for (int t = 0; t < n; t++) {
        addInto(attended[t], x[t]);
        layerNorm(x[t], layer.feedForward());
      }
    }
    return x;
  }

  /**
   * {@code out = W x + b} for each row of {@code x}. Each output component sums its products in
   * the order of the inputs; the tiling only keeps a few weight rows in cache while they serve a
   * block of tokens.
   */
  private static void apply(final Linear l, final float[][] x, final float[][] out) {
    final float[][] w = l.rows();
    final int in = w.length;
    final int n = x.length;
    final int width = l.out();
    for (final float[] row : out) {
      System.arraycopy(l.bias(), 0, row, 0, width);
    }
    for (int t0 = 0; t0 < n; t0 += TILE_TOKENS) {
      final int t1 = Math.min(n, t0 + TILE_TOKENS);
      int i = 0;
      for (; i + 3 < in; i += 4) {
        for (int t = t0; t < t1; t++) {
          final float[] xt = x[t];
          madd4(out[t], width, w[i], xt[i], w[i + 1], xt[i + 1], w[i + 2], xt[i + 2], w[i + 3],
              xt[i + 3]);
        }
      }
      for (; i < in; i++) {
        for (int t = t0; t < t1; t++) {
          madd(out[t], width, w[i], x[t][i]);
        }
      }
    }
  }

  /**
   * Multi-head self-attention over all positions, the heads joined into {@code out}. Worked
   * position-major, one array per key position holding its score from every query, so each
   * kernel runs over all positions at once: a score sums over the head's components in order, a
   * context component over the key positions in order.
   */
  private void attention(final float[][] q, final float[][] k, final float[][] v,
      final float[][] out) {
    final int n = q.length;
    final int width = hidden / heads;
    final float scale = (float) (1 / Math.sqrt(width));
    // the queries and values transposed, one array per component over the positions
    final float[][] queries = transpose(q);
    final float[][] values = transpose(v);
    // weights[s][t]: the weight query t gives key s, first its score
    final float[][] weights = new float[n][n];
    final float[] max = new float[n];
    final double[] sum = new double[n];
    final float[] context = new float[n];
    for (int head = 0; head < heads; head++) {
      final int first = head * width;
      final int end = first + width;
      for (int s = 0; s < n; s++) {
        final float[] ks = k[s];
        final float[] row = weights[s];
        Arrays.fill(row, 0f);
        int d = first;
        for (; d + 3 < end; d += 4) {
          madd4(row, n, queries[d], ks[d], queries[d + 1], ks[d + 1], queries[d + 2], ks[d + 2],
              queries[d + 3], ks[d + 3]);
        }
        for (; d < end; d++) {
          madd(row, n, queries[d], ks[d]);
        }
      }
      // the softmax over the keys of each query, its normaliser summed in key order
      Arrays.fill(max, Float.NEGATIVE_INFINITY);
      for (final float[] row : weights) {
        scaleAndMax(row, n, scale, max);
      }
      Arrays.fill(sum, 0);
      for (final float[] row : weights) {
        exponentiate(row, n, max, sum);
      }
      for (int t = 0; t < n; t++) {
        max[t] = (float) (1 / sum[t]);
      }
      for (final float[] row : weights) {
        multiply(row, n, max);
      }
      for (int d = first; d < end; d++) {
        final float[] value = values[d];
        Arrays.fill(context, 0f);
        int s = 0;
        for (; s + 3 < n; s += 4) {
          madd4(context, n, weights[s], value[s], weights[s + 1], value[s + 1], weights[s + 2],
              value[s + 2], weights[s + 3], value[s + 3]);
        }
        for (; s < n; s++) {
          madd(context, n, weights[s], value[s]);
        }
        for (int t = 0; t < n; t++) {
          out[t][d] = context[t];
        }
      }
    }
  }

  /** {@code row[t] *= scale}, then {@code max[t]} the larger of it and {@code row[t]}. */
  private static void scaleAndMax(final float[] row, final int n, final float scale,
      final float[] max) {
    for (int t = 0; t < n; t++) {
      row[t] *= scale;
      max[t] = Math.max(max[t], row[t]);
    }
  }

  /** {@code row[t] = exp(row[t] - max[t])}, then added to {@code sum[t]}. */
  private static void exponentiate(final float[] row, final int n, final float[] max,
      final double[] sum) {
    for (int t = 0; t < n; t++) {
      row[t] = FloatMath.exp(row[t] - max[t]);
    }
    for (int t = 0; t < n; t++) {
      sum[t] += row[t];
    }
  }

  /** The GELU, {@code x * (1 + erf(x / sqrt 2)) / 2}, of each component of {@code row}. */
  private static void gelu(final float[] row) {
    for (int i = 0; i < row.length; i++) {
      final float x = row[i];
      row[i] = 0.5f * x * (1 + FloatMath.erf(x * FloatMath.INV_SQRT2));
    }
  }

  /** {@code row[t] *= by[t]}. */
  private static void multiply(final float[] row, final int n, final float[] by) {
    for (int t = 0; t < n; t++) {
      row[t] *= by[t];
    }
  }

  /** {@code m} transposed: one array per column, over the rows. */
  private static float[][] transpose(final float[][] m) {
    final float[][] t = new float[m[0].length][m.length];
    for (int r = 0; r < m.length; r++) {
      for (int c = 0; c < m[r].length; c++) {
        t[c][r] = m[r][c];
      }
    }
    return t;
  }

  /**
   * {@code acc[o] += xa a[o] + xb b[o] + xc c[o] + xd d[o]} for {@code o < len}, the four products
   * added in that order, each with a fused multiply-add, so the result is the same whether or not
   * the loop runs in vector registers. The kernels are small methods, called often, because the
   * JIT vectorises such a loop only when every array is read at the same index (so that it can
   * rule out overlap) and reliably only in a method compiled on its own.
   */
  private static void madd4(final float[] acc, final int len, final float[] a, final float xa,
      final float[] b, final float xb, final float[] c, final float xc, final float[] d,
      final float xd) {
    for (int o = 0; o < len; o++) {
      acc[o] = Math.fma(xd, d[o], Math.fma(xc, c[o], Math.fma(xb, b[o], Math.fma(xa, a[o],
          acc[o]))));
    }
  }

  /** {@code acc[o] += xa a[o]} for {@code o < len}, with a fused multiply-add. */
  private static void madd(final float[] acc, final int len, final float[] a, final float xa) {
    for (int o = 0; o < len; o++) {
      acc[o] = Math.fma(xa, a[o], acc[o]);
    }
  }

  private static void addInto(final float[] from, final float[] to) {
    for (int i = 0; i < to.length; i++) {
      to[i] += from[i];
    }
  }

  /** {@code x} at mean zero and variance one, then scaled and shifted. */
  private void layerNorm(final float[] x, final Norm norm) {
    final int h = x.length;
    double mean = 0;
    for (int d = 0; d < h; d++) {
      mean += x[d];
    }
    mean /= h;
    double variance = 0;
    for (int d = 0; d < h; d++) {
      final double c = x[d] - mean;
      variance += c * c;
    }
    variance /= h;
    final double inverse = 1 / Math.sqrt(variance + eps);
    for (int d = 0; d < h; d++) {
      x[d] = (float) ((x[d] - mean) * inverse) * norm.weight()[d] + norm.bias()[d];
    }
  }
}
