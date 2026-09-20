package app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.pipeline.port.Progress;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the shape of a progress line, now decided in one place rather than in four adapters. */
class ConsoleProgressTest {

  private static List<String> linesFrom(final Consumer<Progress> work) {
    final PrintStream original = System.out;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      work.accept(new ConsoleProgress());
    } finally {
      System.setOut(original);
    }
    return captured.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @Test
  @DisplayName("phases, details, items and counts each keep their own indentation")
  void rendersEachKindOfLine() {
    final List<String> lines = linesFrom(progress -> {
      progress.phase(2, 3, "joining on commit.revision_hash");
      progress.detail("%,d commits across the bundled history", 452_286);
      progress.item(12, 98, "commons-io", "%,7d commits", 45_231);
      progress.count(45_000, 61_150, "issues, %,d labels", 42_118);
    });

    assertEquals("  [2/3] joining on commit.revision_hash", lines.get(0));
    assertEquals("        452,286 commits across the bundled history", lines.get(1));
    assertEquals("        [12/98] commons-io                        45,231 commits", lines.get(2));
    assertEquals("        45,000 of 61,150 issues, 42,118 labels", lines.get(3));
  }

  @Test
  @DisplayName("names longer than the column push the counts right instead of being cut")
  void doesNotTruncateLongNames() {
    final String name = "a-repository-name-longer-than-the-column.tar.gz";
    final List<String> lines =
        linesFrom(progress -> progress.item(1, 2, name, "no .git, skipped"));

    assertTrue(lines.get(0).contains(name), "the name identifies the unit of work; keep it whole");
    assertTrue(lines.get(0).endsWith("no .git, skipped"));
  }

  @Test
  @DisplayName("a message with no arguments is text, so a literal percent survives")
  void doesNotFormatArgumentlessMessages() {
    // Without the guard this throws UnknownFormatConversionException, and it would do so an hour
    // into a stage rather than here.
    final List<String> lines = linesFrom(progress -> progress.detail("100% of refs walked"));

    assertEquals("        100% of refs walked", lines.get(0));
  }

  @Test
  @DisplayName("the silent sink prints nothing at all")
  void silentSinkIsQuiet() {
    final PrintStream original = System.out;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      final Progress silent = Progress.silent();
      silent.phase(1, 1, "nothing");
      silent.detail("nothing");
      silent.item(1, 1, "nothing", "nothing");
      silent.count(1, 1, "nothing");
    } finally {
      System.setOut(original);
    }

    assertEquals(0, captured.size());
  }
}
