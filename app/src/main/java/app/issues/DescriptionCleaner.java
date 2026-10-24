package app.issues;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one cleaning every description-similarity method reads: Jira markup removed, what it marked
 * kept.
 *
 * <p>Tags go and their contents stay: {@code {code}} and {@code {noformat}} (code and logs are
 * content too), {@code {quote}}, {@code {panel}}, {@code {color}}, with or without parameters, and
 * their closers; heading prefixes ({@code h1.} to {@code h6.} at the start of a line); emphasis
 * markers around a word ({@code *}, {@code _}, {@code -}, {@code +}, {@code ^}, {@code ~},
 * {@code ??}, {@code {{ }}}), never inside a URL; table pipes. A link {@code [text|url]} becomes its
 * text and URL; a bare {@code [url]}, {@code [~user]}, {@code [^attachment]} or {@code [KEY-123]}
 * what it names; an embedded image {@code !file.png!} is removed. A code or noformat block left
 * unclosed runs to the end of the text. Then Unicode NFC and
 * every run of whitespace, Unicode spaces included, becomes one space.
 *
 * <p>Inside a code or noformat block nothing but the delimiting tags is touched, as Jira renders
 * it: {@code a_b_c} or {@code *p} there is code, not emphasis. Outside one, emphasis needs a
 * marker that does not sit inside a word, so {@code snake_case} and {@code 2*3} survive.
 *
 * <p>Frozen: a change to any rule is a change of the experiment, and must change {@link #VERSION},
 * which enters the run record and the embedding cache key.
 */
public final class DescriptionCleaner {

  /** Changes whenever any rule below does. */
  public static final String VERSION = "description-cleaner-1";

  // A tag is never one brace of a {{monospace}} pair: "{{code}}" names the macro, it opens nothing.
  // An unclosed block runs to the end of the text, as a pasted log whose closer was forgotten.
  private static final Pattern BLOCK = Pattern.compile(
      "(?<!\\{)\\{(code|noformat)(?::[^}]*)?\\}(?!\\})(.*?)(?:(?<!\\{)\\{\\1\\}(?!\\})|\\z)",
      Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern IMAGE = Pattern.compile(
      "!(?:[^!\\s|][^!\\n|]*?\\.(?:png|jpe?g|gif|bmp|svg|webp|tiff?)"
          + "|(?:https?|ftp)://[^!\\s|]+)(?:\\|[^!\\n]*)?!", Pattern.CASE_INSENSITIVE);
  private static final Pattern ALIAS_LINK =
      Pattern.compile("\\[([^\\[\\]|\\n]*)\\|([^\\[\\]|\\s]+)\\]");
  // [url], [~user], [^attachment] and [KEY-123]; a bracket around anything else is left alone
  private static final Pattern BARE_LINK = Pattern.compile(
      "\\[(?:((?:https?|ftp|file|mailto):[^\\[\\]|\\s]+)|[~^]([^\\[\\]|\\s]+)"
          + "|([A-Z][A-Z0-9_]*-\\d+))\\]");
  private static final Pattern MONOSPACE = Pattern.compile("\\{\\{(.*?)\\}\\}");
  private static final Pattern TAG = Pattern.compile(
      "\\{(?:code|noformat|quote|panel|color)(?::[^}]*)?\\}", Pattern.CASE_INSENSITIVE);
  private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*h[1-6]\\.[ \\t]*");
  private static final Pattern TABLE_ROW = Pattern.compile("(?m)^[ \\t]*\\|.*$");
  private static final Pattern URL = Pattern.compile("(?:https?|ftp|file)://\\S+");
  private static final List<Pattern> EMPHASIS =
      List.of("*", "_", "-", "+", "^", "~", "??").stream().map(DescriptionCleaner::emphasis)
          .toList();

  private DescriptionCleaner() {}

  /** The cleaned description, or null when it is absent or nothing is left of it. */
  public static String clean(final String raw) {
    if (raw == null) {
      return null;
    }
    final StringBuilder out = new StringBuilder(raw.length());
    final Matcher m = BLOCK.matcher(raw);
    int from = 0;
    while (m.find()) {
      out.append(prose(raw.substring(from, m.start()))).append(' ').append(m.group(2)).append(' ');
      from = m.end();
    }
    out.append(prose(raw.substring(from)));
    return collapse(Normalizer.normalize(out, Normalizer.Form.NFC));
  }

  /** The rules outside code and noformat blocks. */
  private static String prose(final String text) {
    String t = IMAGE.matcher(text).replaceAll(" ");
    t = ALIAS_LINK.matcher(t).replaceAll(" $1 $2 ");
    t = BARE_LINK.matcher(t).replaceAll(" $1$2$3 ");
    t = MONOSPACE.matcher(t).replaceAll("$1");
    t = TAG.matcher(t).replaceAll(" ");
    t = HEADING.matcher(t).replaceAll("");
    t = TABLE_ROW.matcher(t).replaceAll(r -> Matcher.quoteReplacement(r.group().replace('|', ' ')));
    // emphasis between URLs only: an underscore or a dash in a path is not markup
    final StringBuilder out = new StringBuilder(t.length());
    final Matcher url = URL.matcher(t);
    int from = 0;
    while (url.find()) {
      out.append(unemphasised(t.substring(from, url.start()))).append(url.group());
      from = url.end();
    }
    return out.append(unemphasised(t.substring(from))).toString();
  }

  private static String unemphasised(final String text) {
    String t = text;
    for (final Pattern p : EMPHASIS) {
      t = p.matcher(t).replaceAll("$1");
    }
    return t;
  }

  /**
   * A marker pair around text on one line: the opener not preceded by a letter, digit, underscore
   * or the marker and followed by a non-space; the closer the mirror of it. The text between holds
   * no marker character, which keeps a long line of unpaired markers linear rather than quadratic.
   */
  private static Pattern emphasis(final String marker) {
    final String q = Pattern.quote(marker);
    final String c = "\\" + marker.charAt(0); // every marker is punctuation, safe to escape
    return Pattern.compile("(?<![\\p{L}\\p{N}_" + c + "])" + q + "(?=[^\\s" + c + "])"
        + "([^\\n" + c + "]+?)"
        + "(?<=[^\\s" + c + "])" + q + "(?![\\p{L}\\p{N}_" + c + "])");
  }

  /** Every run of whitespace, as {@link TextMeasures#space} reads it, to one space; blank to null. */
  private static String collapse(final String text) {
    final StringBuilder out = new StringBuilder(text.length());
    boolean gap = false;
    for (int i = 0; i < text.length(); ) {
      final int c = text.codePointAt(i);
      if (TextMeasures.space(c)) {
        gap = out.length() > 0;
      } else {
        if (gap) {
          out.append(' ');
          gap = false;
        }
        out.appendCodePoint(c);
      }
      i += Character.charCount(c);
    }
    return out.length() == 0 ? null : out.toString();
  }
}
