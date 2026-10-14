package app.issues;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text measures, written to agree with the Python specification character for character.
 *
 * <p>The Python column script counts words with {@code \S+} and splits with {@code str.split()}, both of
 * which treat every Unicode space as a separator, and measures lengths in code points. Java's
 * defaults do neither, so both are spelled out here rather than left to a regex flag whose notion of
 * space is close but not the same.
 */
final class TextMeasures {

  private static final Pattern CODE =
      Pattern.compile("\\{(code|noformat)[^}]*\\}.*?\\{\\1\\}", Pattern.DOTALL);
  private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");
  private static final Pattern VOWELS = Pattern.compile("[aeiouy]+");

  private TextMeasures() {}

  /** Python's {@code str.isspace}: whitespace, a space separator, or the next-line control. */
  static boolean space(final int c) {
    return Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR
        || c == 0x85;
  }

  /** {@code len(s.split())}: runs of non-space. */
  static int words(final String s) {
    if (s == null) {
      return 0;
    }
    int n = 0;
    boolean in = false;
    for (int i = 0; i < s.length(); ) {
      final int c = s.codePointAt(i);
      if (space(c)) {
        in = false;
      } else if (!in) {
        in = true;
        n++;
      }
      i += Character.charCount(c);
    }
    return n;
  }

  /** Length in code points, as Python's {@code len}. */
  static int length(final String s) {
    return s == null ? 0 : s.codePointCount(0, s.length());
  }

  /** Without {code}/{noformat} blocks and URLs: Kikas's cleaning, adapted to Jira markup. */
  static String cleaned(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return urlFree(CODE.matcher(text).replaceAll(" "));
  }

  private static String urlFree(final String text) {
    // \S in the URL pattern must stop at every Unicode space, as Python's does
    final StringBuilder out = new StringBuilder(text.length());
    final Matcher m = Pattern.compile("https?://").matcher(text);
    int from = 0;
    while (m.find(from)) {
      out.append(text, from, m.start()).append(' ');
      int end = m.end();
      while (end < text.length()) {
        final int c = text.codePointAt(end);
        if (space(c)) {
          break;
        }
        end += Character.charCount(c);
      }
      if (end == m.end()) {
        // "http://" followed by a space: \S+ needs one character, so no match here
        out.setLength(out.length() - 1);
        out.append(text, m.start(), m.end());
      }
      from = end;
    }
    out.append(text, from, text.length());
    return out.toString();
  }

  static int syllables(final String word) {
    final String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    if (w.isEmpty()) {
      return 0;
    }
    int n = 0;
    final Matcher m = VOWELS.matcher(w);
    while (m.find()) {
      n++;
    }
    if (w.endsWith("e") && n > 1 && !w.endsWith("le") && !w.endsWith("ee")) {
      n--;
    }
    return Math.max(n, 1);
  }

  /** Sentences: runs of [.!?] followed by a space or the end of the text. At least one. */
  private static int sentences(final String t) {
    int n = 0;
    int i = 0;
    while (i < t.length()) {
      final char c = t.charAt(i);
      if (c == '.' || c == '!' || c == '?') {
        int j = i;
        while (j < t.length() && (t.charAt(j) == '.' || t.charAt(j) == '!' || t.charAt(j) == '?')) {
          j++;
        }
        final boolean end = j == t.length() || (j == t.length() - 1 && t.charAt(j) == '\n');
        if (end || space(t.codePointAt(j))) {
          n++;
        }
        i = j;
      } else {
        i++;
      }
    }
    return Math.max(n, 1);
  }

  /**
   * Gunning fog = 0.4 × (words/sentences + 100 × complex words/words), a complex word having three
   * or more syllables by the vowel-group heuristic. Absent when the text has no word.
   */
  static Double fog(final String text) {
    final String t = cleaned(text);
    final Matcher m = WORD.matcher(t);
    int words = 0;
    int complex = 0;
    while (m.find()) {
      words++;
      if (syllables(m.group()) >= 3) {
        complex++;
      }
    }
    if (words == 0) {
      return null;
    }
    return 0.4 * ((double) words / sentences(t) + 100.0 * complex / words);
  }
}
