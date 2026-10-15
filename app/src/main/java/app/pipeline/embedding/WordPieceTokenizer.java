package app.pipeline.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tokenizer of all-MiniLM-L6-v2 in plain Java: BERT's uncased WordPiece tokenizer as the
 * Hugging Face tokenizers library runs it for this model, which gives the token ids
 * sentence-transformers feeds the network.
 *
 * <p><b>Special tokens in the text.</b> The five special tokens ({@code [UNK]}, {@code [SEP]},
 * {@code [PAD]}, {@code [CLS]}, {@code [MASK]}) written literally in the text are matched first,
 * exactly and before any normalisation, and each gives its own id; the text between them goes
 * through the steps below.
 *
 * <p><b>Normalisation.</b> In order: characters that are NUL, U+FFFD or in a Unicode "other"
 * category other than unassigned (control, format, surrogate, private use) are dropped, tab, line
 * feed and carriage return excepted, and every whitespace character becomes a space; each CJK ideograph is
 * set apart by spaces; the text is decomposed (NFD) and its non-spacing marks dropped, which
 * strips accents; every character is lowercased on its own, with its full Unicode mapping.
 *
 * <p><b>Words.</b> The text is cut at whitespace, and every punctuation character is a word of its
 * own: ASCII 33-47, 58-64, 91-96 and 123-126, and every Unicode punctuation category.
 *
 * <p><b>WordPiece.</b> A word of more than {@value #MAX_WORD_CHARS} characters is {@code [UNK]}.
 * Otherwise it is cut from the left into the longest pieces the vocabulary holds, every piece
 * after the first looked up with the {@code ##} prefix; a word with a remainder no piece matches is
 * a single {@code [UNK]}.
 *
 * <p><b>Unicode version.</b> The Hugging Face library decides which characters are control
 * characters, marks and punctuation with older Unicode tables than Java 21's (Unicode 15.0): to
 * them, a character added to Unicode after version 8.0 is unassigned, and so an ordinary character
 * of a word. Run on every code point, alone and between two letters, the two classifications
 * differ on 568; {@link #ORDINARY} and three constants give the library's for those, so the
 * tokens are the library's on all of them.
 *
 * <p>The settings are read from {@code tokenizer_config.json} and {@code special_tokens_map.json}
 * and any this class does not implement are refused; the vocabulary is {@code vocab.txt}, a token
 * per line, its id the line's index. Immutable, so one instance may serve many threads.
 */
public final class WordPieceTokenizer {

  /** The longest word, in characters, WordPiece cuts rather than calls unknown. */
  static final int MAX_WORD_CHARS = 100;

  /** The settings files and the vocabulary, relative to the model directory. */
  static final String CONFIG = "tokenizer_config.json";
  static final String SPECIAL = "special_tokens_map.json";
  static final String VOCAB = "vocab.txt";

  private static final String PREFIX = "##";

  /** The special tokens, by their key in both settings files. */
  private static final List<String> SPECIAL_KEYS =
      List.of("unk_token", "sep_token", "pad_token", "cls_token", "mask_token");

  /** Every setting of {@code tokenizer_config.json} this class knows, read or not. */
  private static final Set<String> KNOWN = Set.of("do_lower_case", "unk_token", "sep_token",
      "pad_token", "cls_token", "mask_token", "tokenize_chinese_chars", "strip_accents",
      "name_or_path", "do_basic_tokenize", "never_split", "tokenizer_class", "model_max_length");

  /**
   * The code points, in inclusive ranges, that Java 21 calls a control or format character, a
   * non-spacing mark or punctuation and the Hugging Face library an ordinary character: characters
   * Unicode added after version 8.0. Found by tokenizing every code point with both.
   */
  private static final int[] ORDINARY = {
      0x061D, 0x061D, 0x07FD, 0x07FD, 0x0890, 0x0891, 0x0898, 0x089F, 0x08CA, 0x08E2,
      0x09FD, 0x09FE, 0x0A76, 0x0A76, 0x0AFA, 0x0AFF, 0x0B55, 0x0B55, 0x0C04, 0x0C04,
      0x0C3C, 0x0C3C, 0x0C77, 0x0C77, 0x0C84, 0x0C84, 0x0D00, 0x0D00, 0x0D3B, 0x0D3C,
      0x0D81, 0x0D81, 0x0EBA, 0x0EBA, 0x0ECE, 0x0ECE, 0x180F, 0x180F, 0x1885, 0x1886,
      0x1ABF, 0x1ACE, 0x1B7D, 0x1B7E, 0x1DF6, 0x1DFB, 0x2E43, 0x2E4F, 0x2E52, 0x2E5D,
      0xA82C, 0xA82C, 0xA8C5, 0xA8C5, 0xA8FF, 0xA8FF, 0xA9BD, 0xA9BD, 0x10D24, 0x10D27,
      0x10EAB, 0x10EAD, 0x10EFD, 0x10EFF, 0x10F46, 0x10F50, 0x10F55, 0x10F59, 0x10F82, 0x10F89,
      0x11070, 0x11070, 0x11073, 0x11074, 0x110C2, 0x110C2, 0x110CD, 0x110CD, 0x111CF, 0x111CF,
      0x1123E, 0x1123E, 0x11241, 0x11241, 0x1133B, 0x1133B, 0x11438, 0x1143F, 0x11442, 0x11444,
      0x11446, 0x11446, 0x1144B, 0x1144F, 0x1145A, 0x1145B, 0x1145D, 0x1145E, 0x11660, 0x1166C,
      0x116B9, 0x116B9, 0x1182F, 0x11837, 0x11839, 0x1183B, 0x1193B, 0x1193C, 0x1193E, 0x1193E,
      0x11943, 0x11946, 0x119D4, 0x119D7, 0x119DA, 0x119DB, 0x119E0, 0x119E0, 0x119E2, 0x119E2,
      0x11A01, 0x11A0A, 0x11A33, 0x11A38, 0x11A3B, 0x11A47, 0x11A51, 0x11A56, 0x11A59, 0x11A5B,
      0x11A8A, 0x11A96, 0x11A98, 0x11A9C, 0x11A9E, 0x11AA2, 0x11B00, 0x11B09, 0x11C30, 0x11C36,
      0x11C38, 0x11C3D, 0x11C3F, 0x11C3F, 0x11C41, 0x11C45, 0x11C70, 0x11C71, 0x11C92, 0x11CA7,
      0x11CAA, 0x11CB0, 0x11CB2, 0x11CB3, 0x11CB5, 0x11CB6, 0x11D31, 0x11D36, 0x11D3A, 0x11D3A,
      0x11D3C, 0x11D3D, 0x11D3F, 0x11D45, 0x11D47, 0x11D47, 0x11D90, 0x11D91, 0x11D95, 0x11D95,
      0x11D97, 0x11D97, 0x11EF3, 0x11EF4, 0x11EF7, 0x11EF8, 0x11F00, 0x11F01, 0x11F36, 0x11F3A,
      0x11F40, 0x11F40, 0x11F42, 0x11F4F, 0x11FFF, 0x11FFF, 0x12FF1, 0x12FF2, 0x13430, 0x13440,
      0x13447, 0x13455, 0x16E97, 0x16E9A, 0x16F4F, 0x16F4F, 0x16FE2, 0x16FE2, 0x16FE4, 0x16FE4,
      0x1CF00, 0x1CF2D, 0x1CF30, 0x1CF46, 0x1E000, 0x1E006, 0x1E008, 0x1E018, 0x1E01B, 0x1E021,
      0x1E023, 0x1E024, 0x1E026, 0x1E02A, 0x1E08F, 0x1E08F, 0x1E130, 0x1E136, 0x1E2AE, 0x1E2AE,
      0x1E2EC, 0x1E2EF, 0x1E4EC, 0x1E4EF, 0x1E944, 0x1E94A, 0x1E95E, 0x1E95F
  };

  /**
   * The three code points whose category changed between the library's tables and Java 21's:
   * U+166D and U+111C9 are punctuation to the library (an other symbol and a mark to Java),
   * U+1734 a non-spacing mark (a spacing one to Java).
   */
  private static final int OLD_PUNCTUATION_1 = 0x166D;
  private static final int OLD_PUNCTUATION_2 = 0x111C9;
  private static final int OLD_MARK = 0x1734;

  private final Map<String, Integer> vocab;
  /** The special tokens as written in a text, longest first. */
  private final List<String> specials;
  private final int unk;
  private final int cls;
  private final int sep;

  private WordPieceTokenizer(final Map<String, Integer> vocab, final List<String> specials,
      final int unk, final int cls, final int sep) {
    this.vocab = vocab;
    this.specials = specials;
    this.unk = unk;
    this.cls = cls;
    this.sep = sep;
  }

  /** The tokenizer of the files in {@code dir}, refused when a setting is one it does not apply. */
  public static WordPieceTokenizer load(final Path dir) throws IOException {
    final ObjectMapper json = new ObjectMapper();
    final JsonNode config = json.readTree(dir.resolve(CONFIG).toFile());
    final JsonNode special = json.readTree(dir.resolve(SPECIAL).toFile());
    if (config == null || !config.isObject() || special == null || !special.isObject()) {
      throw new IOException(dir.resolve(CONFIG) + " and " + SPECIAL + " must be JSON objects");
    }
    final List<String> problems = new ArrayList<>();
    for (final Iterator<String> it = config.fieldNames(); it.hasNext();) {
      final String key = it.next();
      if (!KNOWN.contains(key)) {
        problems.add(key + " is a setting this tokenizer does not implement");
      }
    }
    expect(config, "do_lower_case", "true", problems);
    expect(config, "tokenize_chinese_chars", "true", problems);
    expect(config, "do_basic_tokenize", "true", problems);
    // absent or null, accents are stripped when the text is lowercased, as here
    if (config.has("strip_accents") && !config.get("strip_accents").isNull()
        && !config.get("strip_accents").asText().equals("true")) {
      problems.add("strip_accents is " + config.get("strip_accents")
          + "; only null or true, accents stripped, is implemented");
    }
    if (config.has("never_split") && !config.get("never_split").isNull()) {
      problems.add("never_split is " + config.get("never_split") + "; only null is implemented");
    }
    final String cls = config.path("tokenizer_class").asText("");
    if (!cls.equals("BertTokenizer") && !cls.equals("BertTokenizerFast")) {
      problems.add("tokenizer_class is \"" + cls + "\", not BertTokenizer");
    }
    final Map<String, String> tokens = new HashMap<>();
    for (final String key : SPECIAL_KEYS) {
      final JsonNode a = config.path(key);
      final JsonNode b = special.path(key);
      if (!a.isTextual() || !b.isTextual() || !a.asText().equals(b.asText())) {
        problems.add(key + " is " + a + " in " + CONFIG + " and " + b + " in " + SPECIAL);
      } else {
        tokens.put(key, a.asText());
      }
    }
    if (!problems.isEmpty()) {
      throw new IOException(dir.resolve(CONFIG) + " refused: " + String.join("; ", problems));
    }

    final List<String> lines = Files.readAllLines(dir.resolve(VOCAB), StandardCharsets.UTF_8);
    final Map<String, Integer> vocab = new HashMap<>();
    for (int id = 0; id < lines.size(); id++) {
      if (vocab.putIfAbsent(lines.get(id), id) != null) {
        throw new IOException(dir.resolve(VOCAB) + " refused: \"" + lines.get(id)
            + "\" is on two lines");
      }
    }
    for (final Map.Entry<String, String> t : tokens.entrySet()) {
      if (!vocab.containsKey(t.getValue())) {
        throw new IOException(dir.resolve(VOCAB) + " refused: it lacks the " + t.getKey() + " "
            + t.getValue());
      }
    }
    final List<String> specials = new ArrayList<>(tokens.values());
    specials.sort((x, y) -> Integer.compare(y.length(), x.length()));
    return new WordPieceTokenizer(Map.copyOf(vocab), List.copyOf(specials),
        vocab.get(tokens.get("unk_token")), vocab.get(tokens.get("cls_token")),
        vocab.get(tokens.get("sep_token")));
  }

  private static void expect(final JsonNode config, final String key, final String value,
      final List<String> problems) {
    if (!config.path(key).isBoolean() || !config.get(key).asText().equals(value)) {
      problems.add(key + " is " + config.get(key) + "; only " + value + " is implemented");
    }
  }

  /** The number of tokens in the vocabulary, the ids running from zero. */
  int vocabularySize() {
    return vocab.size();
  }

  /** The id of the classifier token every chunk opens with. */
  public long cls() {
    return cls;
  }

  /** The id of the separator token every chunk closes with. */
  public long sep() {
    return sep;
  }

  /** The token ids of {@code text}, with no special token added and nothing truncated. */
  public long[] encode(final String text) {
    final List<Integer> ids = new ArrayList<>();
    int from = 0;
    int at = 0;
    while (at < text.length()) {
      final String special = specialAt(text, at);
      if (special == null) {
        at++;
        continue;
      }
      words(text.substring(from, at), ids);
      ids.add(vocab.get(special));
      at += special.length();
      from = at;
    }
    words(text.substring(from), ids);
    final long[] out = new long[ids.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ids.get(i);
    }
    return out;
  }

  private String specialAt(final String text, final int at) {
    for (final String s : specials) {
      if (text.startsWith(s, at)) {
        return s;
      }
    }
    return null;
  }

  /** Normalises {@code text}, cuts it into words and appends each word's pieces to {@code ids}. */
  private void words(final String text, final List<Integer> ids) {
    if (text.isEmpty()) {
      return;
    }
    final String normal = normalise(text);
    final StringBuilder word = new StringBuilder();
    for (int i = 0; i < normal.length();) {
      final int c = normal.codePointAt(i);
      i += Character.charCount(c);
      if (whitespace(c)) {
        pieces(word, ids);
      } else if (punctuation(c)) {
        pieces(word, ids);
        word.appendCodePoint(c);
        pieces(word, ids);
      } else {
        word.appendCodePoint(c);
      }
    }
    pieces(word, ids);
  }

  /** The normalisation steps, in the order the class comment gives. */
  static String normalise(final String text) {
    final StringBuilder clean = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length();) {
      final int c = text.codePointAt(i);
      i += Character.charCount(c);
      if (c == 0 || c == 0xFFFD || control(c)) {
        continue;
      }
      if (whitespace(c)) {
        clean.append(' ');
      } else if (cjk(c)) {
        clean.append(' ').appendCodePoint(c).append(' ');
      } else {
        clean.appendCodePoint(c);
      }
    }
    final String decomposed = Normalizer.normalize(clean, Normalizer.Form.NFD);
    final StringBuilder out = new StringBuilder(decomposed.length());
    for (int i = 0; i < decomposed.length();) {
      final int c = decomposed.codePointAt(i);
      i += Character.charCount(c);
      if (mark(c)) {
        continue;
      }
      if (c < 0x80) {
        out.append((char) (c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c));
      } else {
        // a character's own full lowercase mapping, never one that depends on its neighbours
        out.append(Character.toString(c).toLowerCase(Locale.ROOT));
      }
    }
    return out.toString();
  }

  /** Appends the WordPiece ids of {@code word} to {@code ids} and empties it. */
  private void pieces(final StringBuilder word, final List<Integer> ids) {
    if (word.isEmpty()) {
      return;
    }
    final String w = word.toString();
    word.setLength(0);
    if (w.codePointCount(0, w.length()) > MAX_WORD_CHARS) {
      ids.add(unk);
      return;
    }
    final List<Integer> found = new ArrayList<>();
    int start = 0;
    while (start < w.length()) {
      Integer id = null;
      int end = w.length();
      while (end > start) {
        final String piece = start == 0 ? w.substring(0, end)
            : PREFIX + w.substring(start, end);
        id = vocab.get(piece);
        if (id != null) {
          break;
        }
        end = w.offsetByCodePoints(end, -1);
      }
      if (id == null) {
        ids.add(unk);
        return;
      }
      found.add(id);
      start = end;
    }
    ids.addAll(found);
  }

  /** Whether the library's tables call {@code c} an ordinary character, unlike Java's. */
  private static boolean ordinary(final int c) {
    int lo = 0;
    int hi = ORDINARY.length / 2 - 1;
    while (lo <= hi) {
      final int mid = (lo + hi) >>> 1;
      if (c < ORDINARY[2 * mid]) {
        hi = mid - 1;
      } else if (c > ORDINARY[2 * mid + 1]) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /** A non-spacing mark, the accents NFD separates from their letters. */
  private static boolean mark(final int c) {
    return c == OLD_MARK || (Character.getType(c) == Character.NON_SPACING_MARK
        && c != OLD_PUNCTUATION_2 && !ordinary(c));
  }

  /** A Unicode "other" character, tab, line feed and carriage return excepted. */
  private static boolean control(final int c) {
    if (c == '\t' || c == '\n' || c == '\r' || ordinary(c)) {
      return false;
    }
    final int type = Character.getType(c);
    return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
        || type == Character.PRIVATE_USE;
  }

  /** The Unicode White_Space property, which Java's {@code isWhitespace} does not follow. */
  private static boolean whitespace(final int c) {
    return c == ' ' || (c >= 0x09 && c <= 0x0D) || c == 0x85 || c == 0xA0 || c == 0x1680
        || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
        || c == 0x205F || c == 0x3000;
  }

  /** The CJK ideograph blocks BERT sets apart. */
  private static boolean cjk(final int c) {
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0x20000 && c <= 0x2A6DF) || (c >= 0x2A700 && c <= 0x2B73F)
        || (c >= 0x2B740 && c <= 0x2B81F) || (c >= 0x2B920 && c <= 0x2CEAF)
        || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x2F800 && c <= 0x2FA1F);
  }

  /** ASCII punctuation and symbols, and the Unicode punctuation categories. */
  private static boolean punctuation(final int c) {
    if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96)
        || (c >= 123 && c <= 126)) {
      return true;
    }
    if (c == OLD_PUNCTUATION_1 || c == OLD_PUNCTUATION_2) {
      return true;
    }
    if (ordinary(c)) {
      return false;
    }
    final int type = Character.getType(c);
    return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
        || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
        || type == Character.INITIAL_QUOTE_PUNCTUATION
        || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
  }
}
