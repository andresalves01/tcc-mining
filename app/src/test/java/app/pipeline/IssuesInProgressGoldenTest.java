package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.port.SilentProgress;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The In Progress extraction pinned to what it gave before the first-commit population was added
 * beside it: the golden file was written by this same test at commit a21926e, the base of the
 * first-commit study's extraction, and must not change.
 */
class IssuesInProgressGoldenTest {

  static final String GOLDEN = "in-progress-extract-golden.txt";

  @TempDir Path dir;

  /** The cache the extraction writes, then its split, executors and gate, as text. */
  static String extraction(final IssuesInProgressExtractTask.Report r, final Path cache)
      throws IOException {
    RowCache.write(cache, r.entries(), r.columns(), List.of(Population.IN_PROGRESS));
    return Files.readString(cache) + "split " + r.split() + "\nexecutors " + r.executors()
        + "\ntravel " + r.travel() + "\nteam " + new java.util.TreeMap<>(r.team()) + "\n";
  }

  static String golden() throws IOException {
    try (InputStream in = IssuesInProgressGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertNotNull(in, "the golden file " + GOLDEN);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("the In Progress extraction writes, byte for byte, what it wrote before the "
      + "first-commit population")
  void unchanged() throws IOException {
    final IssuesInProgressExtractTask.Report r = new IssuesInProgressExtractTask(
        new ExtractionFixture().source(), SilentProgress.INSTANCE).run();
    final String actual = extraction(r, dir.resolve("cache.tsv"));
    final Path out = Path.of("build", "golden", GOLDEN);
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);
    assertEquals(golden(), actual);
  }
}
