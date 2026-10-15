package app.pipeline.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plain-Java tokenizer: against the Hugging Face tokenizer on edge cases, and on a small
 * vocabulary written by hand.
 *
 * <p><b>tokenizer-cases.json.</b> The expected token ids come from the original Hugging Face
 * tokenizer, not from this code. They were generated once, before that tokenizer left the
 * project, by a throwaway test that called {@code ai.djl.huggingface:tokenizers} 0.38.0 (which
 * wraps the Rust Hugging Face tokenizers 0.21.0): {@code HuggingFaceTokenizer.newInstance} on the
 * {@code tokenizer.json} of sentence-transformers/all-MiniLM-L6-v2 at revision
 * 1110a243fdf4706b3f48f1d95db1a4f5529b4d41, with {@code addSpecialTokens=false},
 * {@code truncation=false} and {@code padding=false}, then {@code encode(text, false, false)
 * .getIds()} for each of 275 hand-written strings: accents and combining marks, CJK, emoji and
 * other characters outside the BMP, control and zero-width characters, every ASCII punctuation
 * mark alone and inside a word, URLs, file paths, stack-trace lines, Java identifiers, numbers and
 * versions, words over 100 characters, long runs of whitespace, mixed scripts, Jira markup, and
 * characters whose Unicode class changed after version 8.0.
 * Strings holding a lone surrogate were left out: they cannot be written as UTF-8, and the corpus,
 * stored as UTF-8, cannot hold them. The file's {@code source} field repeats the call. The same
 * throwaway comparison, run once on every code point alone and between two letters, found no
 * difference either.
 *
 * <p>The comparison needs the vocabulary on disk: MODEL_DIR, as for the embedder's tests (see
 * {@link ModelGate}).
 */
class WordPieceTokenizerTest {

  private static WordPieceTokenizer pinned;
  private static String failure;

  @TempDir
  Path dir;

  @BeforeAll
  static void load() {
    final ModelGate.Gate<WordPieceTokenizer> gate = ModelGate.fromEnvironment(d -> {
      EmbeddingModel.verify(d, EmbeddingModel.FILES);
      return WordPieceTokenizer.load(d);
    });
    pinned = gate.model();
    failure = gate.failure();
  }

  @Test
  @DisplayName("every edge case gives exactly the token ids of the Hugging Face tokenizer")
  void reproducesHuggingFace() throws IOException {
    if (failure != null) {
      fail(failure);
    }
    assumeTrue(pinned != null, "MODEL_DIR is unset: the comparison needs the pinned "
        + "vocabulary; skipped");
    final JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("tokenizer-cases.json")) {
      root = new ObjectMapper().readTree(in);
    }
    final List<String> wrong = new ArrayList<>();
    int cases = 0;
    for (final JsonNode item : root.get("items")) {
      final long[] expected = new long[item.get("ids").size()];
      for (int i = 0; i < expected.length; i++) {
        expected[i] = item.get("ids").get(i).asLong();
      }
      final long[] got = pinned.encode(item.get("text").asText());
      if (!Arrays.equals(expected, got)) {
        wrong.add(item.get("text").asText().codePoints()
            .mapToObj(c -> c < 0x20 || c > 0x7e ? String.format("\\u%04X", c)
                : Character.toString(c)).reduce("", String::concat)
            + "\n    expected " + Arrays.toString(expected) + "\n    got      "
            + Arrays.toString(got));
      }
      cases++;
    }
    assertEquals(275, cases);
    assertEquals(List.of(), wrong, wrong.size() + " cases differ:\n" + String.join("\n", wrong));
    assertEquals(101, pinned.cls());
    assertEquals(102, pinned.sep());
  }

  /** A model directory holding {@code vocab}, the tokenizer settings {@code config}. */
  private Path model(final List<String> vocab, final String config) throws IOException {
    Files.write(dir.resolve("vocab.txt"), vocab);
    Files.writeString(dir.resolve("tokenizer_config.json"), config);
    Files.writeString(dir.resolve("special_tokens_map.json"), "{\"unk_token\": \"[UNK]\", "
        + "\"sep_token\": \"[SEP]\", \"pad_token\": \"[PAD]\", \"cls_token\": \"[CLS]\", "
        + "\"mask_token\": \"[MASK]\"}");
    return dir;
  }

  private static final String CONFIG = "{\"do_lower_case\": true, \"unk_token\": \"[UNK]\", "
      + "\"sep_token\": \"[SEP]\", \"pad_token\": \"[PAD]\", \"cls_token\": \"[CLS]\", "
      + "\"mask_token\": \"[MASK]\", \"tokenize_chinese_chars\": true, \"strip_accents\": null, "
      + "\"name_or_path\": \"nreimers/MiniLM-L6-H384-uncased\", \"do_basic_tokenize\": true, "
      + "\"never_split\": null, \"tokenizer_class\": \"BertTokenizer\", "
      + "\"model_max_length\": 512}";

  // ids:                  0        1        2        3        4         5     6      7
  private static final List<String> VOCAB = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
      "un", "##aff", "##able", "aff", ".", "cafe", "中");
  //  8    9     10      11

  @Test
  @DisplayName("a word is cut greedily into the longest pieces the vocabulary holds, later pieces "
      + "marked ##, and a word with no such cut is one [UNK]")
  void wordPiece() throws IOException {
    final WordPieceTokenizer t = WordPieceTokenizer.load(model(VOCAB, CONFIG));
    assertArrayEquals(new long[] {5, 6, 7}, t.encode("unaffable"));
    assertArrayEquals(new long[] {8, 1, 5}, t.encode("aff unaffx un"));
    assertArrayEquals(new long[] {2}, new long[] {t.cls()});
    assertArrayEquals(new long[] {3}, new long[] {t.sep()});
  }

  @Test
  @DisplayName("text is lowercased, stripped of accents, split at whitespace and punctuation, CJK "
      + "characters stand alone, and control characters are dropped")
  void normalisesAndSplits() throws IOException {
    final WordPieceTokenizer t = WordPieceTokenizer.load(model(VOCAB, CONFIG));
    assertArrayEquals(new long[] {10, 9, 8}, t.encode("  CAFÉ.\u0000ÁFF\t"));
    assertArrayEquals(new long[] {5, 11, 8}, t.encode("un中aff"));
  }

  @Test
  @DisplayName("a word of more than 100 characters is one [UNK], one of 100 is cut")
  void longWord() throws IOException {
    final List<String> vocab = new ArrayList<>(VOCAB);
    vocab.add("a");
    vocab.add("##a");
    final WordPieceTokenizer t = WordPieceTokenizer.load(model(vocab, CONFIG));
    assertEquals(100, t.encode("a".repeat(100)).length);
    assertArrayEquals(new long[] {1}, t.encode("a".repeat(101)));
  }

  @Test
  @DisplayName("a special token written in the text is that token, matched before normalisation")
  void specialTokensInText() throws IOException {
    final WordPieceTokenizer t = WordPieceTokenizer.load(model(VOCAB, CONFIG));
    assertArrayEquals(new long[] {5, 3, 8}, t.encode("un[SEP]aff"));
    assertArrayEquals(new long[] {1, 1, 1}, t.encode("[sep]"));
  }

  @Test
  @DisplayName("settings it does not implement are refused: no lowercasing, accents kept, no CJK "
      + "split, words never split, an unknown setting, a special token outside the vocabulary")
  void refusesOtherSettings() throws IOException {
    for (final String changed : List.of(
        CONFIG.replace("\"do_lower_case\": true", "\"do_lower_case\": false"),
        CONFIG.replace("\"strip_accents\": null", "\"strip_accents\": false"),
        CONFIG.replace("\"tokenize_chinese_chars\": true", "\"tokenize_chinese_chars\": false"),
        CONFIG.replace("\"do_basic_tokenize\": true", "\"do_basic_tokenize\": false"),
        CONFIG.replace("\"never_split\": null", "\"never_split\": [\"foo\"]"),
        CONFIG.replace("\"tokenizer_class\": \"BertTokenizer\"",
            "\"tokenizer_class\": \"RobertaTokenizer\""),
        CONFIG.replace("}", ", \"add_prefix_space\": true}"))) {
      final IOException e = assertThrows(IOException.class,
          () -> WordPieceTokenizer.load(model(VOCAB, changed)), changed);
      assertTrue(e.getMessage().contains("tokenizer_config.json"), e.getMessage());
    }
    // strip_accents absent is null, accents stripped as the text is lowercased
    final WordPieceTokenizer absent = WordPieceTokenizer.load(model(VOCAB,
        CONFIG.replace("\"strip_accents\": null, ", "")));
    assertArrayEquals(new long[] {10}, absent.encode("CAFÉ"));
    final List<String> noMask = new ArrayList<>(VOCAB);
    noMask.set(4, "[MASKED]");
    assertThrows(IOException.class, () -> WordPieceTokenizer.load(model(noMask, CONFIG)));
  }
}
