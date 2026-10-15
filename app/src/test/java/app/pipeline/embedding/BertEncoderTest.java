package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The BERT forward pass on a tiny model written by hand: what it refuses to load, and a case whose
 * output follows from the layer-norm definition alone. The pinned model's numbers are checked
 * against sentence-transformers by the embedder's test.
 */
class BertEncoderTest {

  private static final int VOCAB = 10;
  private static final int HIDDEN = 4;
  private static final int INTERMEDIATE = 8;
  private static final int POSITIONS = 8;

  @TempDir
  Path dir;

  private static String config(final UnaryOperator<ObjectNode> change) {
    final ObjectNode c = new ObjectMapper().createObjectNode();
    c.putArray("architectures").add("BertModel");
    c.put("model_type", "bert");
    c.put("hidden_act", "gelu");
    c.put("hidden_size", HIDDEN);
    c.put("intermediate_size", INTERMEDIATE);
    c.put("layer_norm_eps", 1e-12);
    c.put("max_position_embeddings", POSITIONS);
    c.put("num_attention_heads", 2);
    c.put("num_hidden_layers", 1);
    c.put("position_embedding_type", "absolute");
    c.put("type_vocab_size", 2);
    c.put("vocab_size", VOCAB);
    return change.apply(c).toString();
  }

  /** One tensor to write: its shape and its elements, row-major. */
  private record Tensor(int[] shape, float[] data) {}

  /** Every tensor of a one-layer model, each filled with {@code fill}, the shapes the config's. */
  private static Map<String, Tensor> tensors(final float fill) {
    final Map<String, int[]> shapes = new LinkedHashMap<>();
    shapes.put("embeddings.word_embeddings.weight", new int[] {VOCAB, HIDDEN});
    shapes.put("embeddings.position_embeddings.weight", new int[] {POSITIONS, HIDDEN});
    shapes.put("embeddings.token_type_embeddings.weight", new int[] {2, HIDDEN});
    shapes.put("embeddings.LayerNorm.weight", new int[] {HIDDEN});
    shapes.put("embeddings.LayerNorm.bias", new int[] {HIDDEN});
    final String l = "encoder.layer.0.";
    for (final String p : new String[] {"attention.self.query", "attention.self.key",
        "attention.self.value", "attention.output.dense"}) {
      shapes.put(l + p + ".weight", new int[] {HIDDEN, HIDDEN});
      shapes.put(l + p + ".bias", new int[] {HIDDEN});
    }
    shapes.put(l + "attention.output.LayerNorm.weight", new int[] {HIDDEN});
    shapes.put(l + "attention.output.LayerNorm.bias", new int[] {HIDDEN});
    shapes.put(l + "intermediate.dense.weight", new int[] {INTERMEDIATE, HIDDEN});
    shapes.put(l + "intermediate.dense.bias", new int[] {INTERMEDIATE});
    shapes.put(l + "output.dense.weight", new int[] {HIDDEN, INTERMEDIATE});
    shapes.put(l + "output.dense.bias", new int[] {HIDDEN});
    shapes.put(l + "output.LayerNorm.weight", new int[] {HIDDEN});
    shapes.put(l + "output.LayerNorm.bias", new int[] {HIDDEN});
    final Map<String, Tensor> out = new LinkedHashMap<>();
    shapes.forEach((name, shape) -> {
      final float[] v = new float[Arrays.stream(shape).reduce(1, (a, b) -> a * b)];
      Arrays.fill(v, fill);
      out.put(name, new Tensor(shape, v));
    });
    return out;
  }

  /** Writes {@code config} and {@code tensors} as config.json and model.safetensors. */
  private Path model(final String config, final Map<String, Tensor> tensors) throws IOException {
    Files.writeString(dir.resolve("config.json"), config);
    final ObjectNode header = new ObjectMapper().createObjectNode();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (final Map.Entry<String, Tensor> t : tensors.entrySet()) {
      final ObjectNode e = header.putObject(t.getKey());
      e.put("dtype", "F32");
      final var s = e.putArray("shape");
      for (final int d : t.getValue().shape()) {
        s.add(d);
      }
      final float[] v = t.getValue().data();
      final ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
      for (final float x : v) {
        b.putFloat(x);
      }
      e.putArray("data_offsets").add(data.size()).add(data.size() + b.capacity());
      data.write(b.array());
    }
    final byte[] h = header.toString().getBytes(StandardCharsets.UTF_8);
    final ByteArrayOutputStream file = new ByteArrayOutputStream();
    file.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(h.length).array());
    file.write(h);
    file.write(data.toByteArray());
    Files.write(dir.resolve("model.safetensors"), file.toByteArray());
    return dir;
  }

  private BertEncoder load(final Path d) throws IOException {
    return BertEncoder.load(d.resolve("config.json"), d.resolve("model.safetensors"));
  }

  @Test
  @DisplayName("when every weight before the last layer norm is zero, each token's output is that "
      + "norm's bias, whatever the tokens")
  void zeroWeightsGiveTheLastBias() throws IOException {
    final Map<String, Tensor> t = tensors(0);
    t.put("encoder.layer.0.output.LayerNorm.bias",
        new Tensor(new int[] {HIDDEN}, new float[] {0.5f, -1, 2, 0}));
    final BertEncoder encoder = load(model(config(c -> c), t));
    final float[][] out = encoder.hidden(new long[] {1, 7, 3});
    assertEquals(3, out.length);
    for (final float[] row : out) {
      assertArrayEquals(new float[] {0.5f, -1, 2, 0}, row);
    }
    assertEquals(HIDDEN, encoder.dimension());
  }

  @Test
  @DisplayName("a configuration it does not implement is refused: another activation, relative "
      + "positions, a decoder, heads that do not divide the width")
  void refusesOtherConfigurations() throws IOException {
    for (final UnaryOperator<ObjectNode> change : List.<UnaryOperator<ObjectNode>>of(
        c -> c.put("hidden_act", "gelu_new"),
        c -> c.put("position_embedding_type", "relative_key"),
        c -> c.put("is_decoder", true),
        c -> c.put("num_attention_heads", 3),
        c -> c.put("model_type", "roberta"),
        c -> c.put("hidden_size", 4.5),
        c -> {
          c.remove("layer_norm_eps");
          return c;
        })) {
      final String cfg = config(change);
      final IOException e = assertThrows(IOException.class,
          () -> load(model(cfg, tensors(0))), cfg);
      assertTrue(e.getMessage().contains("config.json"), e.getMessage());
    }
  }

  @Test
  @DisplayName("weights missing a tensor, or of a shape other than the configuration's, are "
      + "refused")
  void refusesWeightsOfAnotherShape() throws IOException {
    final Map<String, Tensor> missing = tensors(0);
    missing.remove("encoder.layer.0.intermediate.dense.weight");
    assertThrows(IOException.class, () -> load(model(config(c -> c), missing)));
    final Map<String, Tensor> wider = tensors(0);
    assertThrows(IOException.class,
        () -> load(model(config(c -> c.put("intermediate_size", 16)), wider)));
  }

  @Test
  @DisplayName("a token id outside the vocabulary, or more tokens than positions, is refused")
  void refusesBadInput() throws IOException {
    final BertEncoder encoder = load(model(config(c -> c), tensors(0.01f)));
    assertThrows(IllegalArgumentException.class, () -> encoder.hidden(new long[] {VOCAB}));
    assertThrows(IllegalArgumentException.class, () -> encoder.hidden(new long[] {-1}));
    assertThrows(IllegalArgumentException.class,
        () -> encoder.hidden(new long[POSITIONS + 1]));
    assertThrows(IllegalArgumentException.class, () -> encoder.hidden(new long[0]));
  }
}
