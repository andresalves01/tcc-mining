package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.Strictness;
import app.issues.Universe.Population;
import app.issues.port.ResultStore;
import app.pipeline.port.SilentProgress;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The In Progress stages after the retrospective one -- the selection, the inner report and the
 * test -- pinned to what they gave before the first-commit population reached them: on the
 * synthetic matrix of {@link SelectionFixture}, the parameters file in full, every file the two
 * reports write by its SHA-256, and the records of the two stages that record, the fields of the
 * machine and the clock left out. The golden file was written by this same test at commit
 * bf421be, the base of ticket 04 of the first-commit study, and must not change.
 */
class IssuesSelectionStagesGoldenTest {

  static final String GOLDEN = "in-progress-selection-golden.txt";

  /** The fields of a record that depend on the machine or the clock, not on the inputs. */
  private static final List<String> MACHINE = List.of("seconds", "scoring_seconds", "java",
      "java_vendor", "os", "processors", "max_memory_bytes");

  @TempDir Path dir;

  private static PipelineConfig defaults() {
    return new PipelineConfig("mongodb://127.0.0.1:27017", "smartshark_2_2", "smartshark_tcc",
        "smartshark_issues", "smartshark_tcc", Path.of("build/work"), Path.of("build/cache"),
        Duration.ofMillis(250), Strictness.EMAIL_AND_FULL_NAME, Optional.empty());
  }

  private static Context context(final PipelineConfig config, final ResultStore store) {
    return new Context(config, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, null, store, null);
  }

  private static String sha(final String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  /** A record without the machine's fields, and the SHA-256 of its rows, as text. */
  private static String record(final String stage, final Map<String, Object> run,
      final List<Map<String, Object>> rows) throws Exception {
    final Map<String, Object> kept = new TreeMap<>(run);
    MACHINE.forEach(kept::remove);
    return "== " + stage + " record " + kept + "\n== " + stage + " rows " + rows.size() + " "
        + sha(rows.toString()) + "\n";
  }

  /** Every file of a report folder by name and SHA-256, the folder's path written as {@code <dir>}. */
  private static String files(final Path folder, final Path dir) throws Exception {
    final StringBuilder out = new StringBuilder();
    try (var list = Files.list(folder)) {
      for (final Path f : list.sorted().toList()) {
        out.append("== ").append(folder.getFileName()).append('/').append(f.getFileName())
            .append(' ').append(sha(Files.readString(f).replace(dir.toString(), "<dir>")))
            .append('\n');
      }
    }
    return out.toString();
  }

  /** The three stages on the fixture's matrix of {@code population}, their outputs as text. */
  static String stages(final Path dir, final Population population, final PipelineConfig config)
      throws Exception {
    final Path matrix = SelectionFixture.write(dir.resolve("matrix.tsv"), population);
    final Path parameters = dir.resolve("elastic-net.tsv");
    final Map<String, String> settings = new LinkedHashMap<>();
    settings.put("MATRIX", matrix.toString());
    settings.put("ELASTIC_NET_PARAMETERS", parameters.toString());
    final Function<String, String> get = settings::get;
    final List<Map<String, Object>> runs = new ArrayList<>();
    final List<List<Map<String, Object>>> rows = new ArrayList<>();
    final ResultStore store = (run, r) -> {
      runs.add(run);
      rows.add(r);
      return "run";
    };
    final StringBuilder out = new StringBuilder();

    new IssuesSimilarityElasticNetCommand(get).run(context(config, store));
    out.append("== parameters\n").append(Files.readString(parameters));
    out.append(record("elastic-net", runs.get(0), rows.get(0)));

    settings.put("REPORT_DIR", dir.resolve("inner").toString());
    new IssuesSimilarityInnerReportCommand(get).run(context(config, (run, r) -> {
      throw new AssertionError("the inner report recorded " + run);
    }));
    out.append(files(dir.resolve("inner"), dir));

    if (population == Population.IN_PROGRESS) {
      settings.put("REPORT_DIR", dir.resolve("test").toString());
      new IssuesSimilarityTestCommand(get).run(context(config, store));
      out.append(files(dir.resolve("test"), dir));
      out.append(record("test", runs.get(1), rows.get(1)));
    }
    return out.toString();
  }

  static String golden() throws Exception {
    try (InputStream in = IssuesSelectionStagesGoldenTest.class.getResourceAsStream(GOLDEN)) {
      assertNotNull(in, "the golden file " + GOLDEN);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("the In Progress selection, inner report and test write and record, byte for "
      + "byte, what they did before the first-commit population reached them")
  void unchanged() throws Exception {
    final String actual = stages(dir, Population.IN_PROGRESS, defaults());
    final Path out = Path.of("build", "golden", GOLDEN);
    Files.createDirectories(out.getParent());
    Files.writeString(out, actual);
    assertEquals(golden(), actual);
  }
}
