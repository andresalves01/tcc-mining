package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each Jira construct the cleaner removes or keeps, one case apiece. */
class DescriptionCleanerTest {

  private static String clean(final String raw) {
    return DescriptionCleaner.clean(raw);
  }

  @Test
  @DisplayName("an absent or blank description stays absent")
  void absent() {
    assertNull(clean(null));
    assertNull(clean(""));
    assertNull(clean(" \n\t  "));
    assertNull(clean("{code}{code}"));
    assertNull(clean("!shot.png!"));
  }

  @Test
  @DisplayName("code and noformat tags go, their contents stay untouched")
  void codeBlocks() {
    assertEquals("before int a_b = *p; after",
        clean("before {code:java}int a_b = *p;{code} after"));
    assertEquals("Caused by: java.lang.NPE at Foo.bar(Foo.java:12)",
        clean("{noformat}\nCaused by: java.lang.NPE\n  at Foo.bar(Foo.java:12)\n{noformat}"));
    assertEquals("x *not bold* [a|b] y", clean("x {code}*not bold* [a|b]{code} y"));
    assertEquals("upper case", clean("{CODE}upper{Code} case"));
  }

  @Test
  @DisplayName("quote, panel and color tags go with their parameters, contents stay")
  void blockTags() {
    assertEquals("he said so", clean("{quote}he said so{quote}"));
    assertEquals("inside", clean("{panel:title=Note|borderStyle=dashed}inside{panel}"));
    assertEquals("red text", clean("{color:#ff0000}red{color} text"));
  }

  @Test
  @DisplayName("heading prefixes go at the start of a line only")
  void headings() {
    assertEquals("Steps do this", clean("h1. Steps\nh3.do this"));
    assertEquals("see h2. here", clean("see h2. here"));
  }

  @Test
  @DisplayName("emphasis markers go around words, not inside identifiers or arithmetic")
  void emphasis() {
    assertEquals("bold italic struck under sup sub cite mono",
        clean("*bold* _italic_ -struck- +under+ ^sup^ ~sub~ ??cite?? {{mono}}"));
    assertEquals("a very bold claim", clean("a *very bold* claim"));
    assertEquals("some_snake_case 2*3*4 2019-01-01", clean("some_snake_case 2*3*4 2019-01-01"));
    assertEquals("* a list item", clean("* a list item"));
  }

  @Test
  @DisplayName("links keep their text and URL; a bare link keeps its URL")
  void links() {
    assertEquals("the docs http://x.org/a and http://y.org",
        clean("[the docs|http://x.org/a] and [http://y.org]"));
  }

  @Test
  @DisplayName("embedded images go, exclamation marks in prose stay")
  void images() {
    assertEquals("see here", clean("see !screenshot-1.png|thumbnail! here"));
    assertEquals("see here", clean("see !http://x.org/a.gif! here"));
    assertEquals("Fix it! Now!", clean("Fix it! Now!"));
  }

  @Test
  @DisplayName("table pipes go, cell contents stay")
  void tables() {
    assertEquals("a b 1 2", clean("||a||b||\n|1|2|"));
  }

  @Test
  @DisplayName("text is NFC and whitespace collapses, Unicode spaces included")
  void normalisation() {
    assertEquals("café", clean("café"));
    assertEquals("a b c", clean("  a\n\n\tb  c  "));
  }

  @Test
  @DisplayName("for detection, code and noformat blocks go with their contents")
  void detection() {
    assertEquals("Fails here: see above",
        DescriptionCleaner.forDetection("Fails here:\n{code}x = 1{code}\n{noformat}trace{noformat}"
            + " see above"));
    assertNull(DescriptionCleaner.forDetection("{code}only code{code}"));
    assertNull(DescriptionCleaner.forDetection(null));
  }

  @Test
  @DisplayName("an unclosed code or noformat block runs to the end of the text")
  void unclosed() {
    assertEquals("see x_y_z *p_", clean("see {code}x_y_z *p_"));
    assertEquals("see", DescriptionCleaner.forDetection("see {noformat}trace *a*"));
  }

  @Test
  @DisplayName("a macro name in monospace is text, not a tag")
  void monospaceMacro() {
    assertEquals("code macro is used", clean("{{code}} macro is used"));
    assertEquals("code a noformat", clean("{{code}} a {{noformat}}"));
    assertEquals("code a code", DescriptionCleaner.forDetection("{{code}} a {{code}}"));
  }

  @Test
  @DisplayName("URLs keep their markers: no emphasis inside a link")
  void urls() {
    assertEquals("path http://x.org/_foo_/bar ok", clean("path http://x.org/_foo_/bar ok"));
    assertEquals("see http://x.org/a-b-c/ and fix", clean("see http://x.org/a-b-c/ and *fix*"));
  }

  @Test
  @DisplayName("user, issue and attachment links keep their name, not their brackets")
  void jiraLinks() {
    assertEquals("john.doe said PROJ-123 fails, see log.txt and a[0]",
        clean("[~john.doe] said [PROJ-123] fails, see [^log.txt] and a[0]"));
  }

  @Test
  @DisplayName("a long line of unmatched markers cleans in linear time")
  void longLine() {
    final String line = " -x *y _z".repeat(20_000);
    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> clean(line));
  }

  @Test
  @DisplayName("the cleaner states a version")
  void version() {
    assertFalse(DescriptionCleaner.VERSION.isBlank());
  }
}
