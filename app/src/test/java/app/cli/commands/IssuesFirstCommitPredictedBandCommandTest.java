package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The predicted-band command reads a test report and writes two files, nothing else. */
class IssuesFirstCommitPredictedBandCommandTest {

  private static final String HEADER = "id\tkey\tproject\tobserved_business_days\tglobal_median"
      + "\town_with_similarity_before_pressure\talzubaidi_start\tkikas_start\town_retrospective";

  private static Path report(final Path dir, final List<String> rows) throws IOException {
    final Path report = Files.createDirectories(dir.resolve("report"));
    Files.write(report.resolve("similarity-test-predictions.tsv"),
        Stream.concat(Stream.of(HEADER), rows.stream()).toList());
    Files.write(dir.resolve("run.log"), List.of(
        "  readings: usual flow (main reading): the scored issues at or below the k = 3 fence, "
            + "3 issues (fence 30.000 bd on the outer training set); all issues (complementary "
            + "reading): every scored issue, 4 issues",
        "  error bands: bands of the observed business days; here 2, 5 and 8 business days, of "
            + "the outer training rows fitted"));
    return report;
  }

  @Test
  @DisplayName("writes predicted-band.md and .tsv: observed 1 with a high prediction is in the "
      + "high band, for both readings and the three models")
  void writesTheBands(@TempDir final Path dir) throws Exception {
    final Path report = report(dir, List.of(
        "a\tK-1\tp\t1.0\t5\t20.0\t20.0\t20.0\t1",
        "b\tK-2\tp\t3.0\t5\t3.0\t3.0\t3.0\t1",
        "c\tK-3\tp\t30.0\t5\t9.0\t9.0\t9.0\t1",
        "d\tK-4\tp\t31.0\t5\t9.0\t9.0\t9.0\t1"));
    final Path out = dir.resolve("out");
    final Map<String, String> settings = new HashMap<>();
    settings.put("REPORT_DIR", report.toString());
    settings.put("OUT_DIR", out.toString());
    new IssuesFirstCommitPredictedBandCommand(settings::get).run(null);

    final List<String> tsv = Files.readAllLines(out.resolve(
        IssuesFirstCommitPredictedBandCommand.TABLE));
    assertEquals("reading\tmodel\tview\tband\tmetric\tvalue", tsv.get(0));
    final String text = String.join("\n", tsv);
    // full population: the high band by prediction holds a, c, d
    assertTrue(tsv.contains("all\tkikas_start\tby_prediction\t>8\tn\t3"), text);
    // usual flow drops d (31 > 30): the high band by prediction holds a and c
    assertTrue(tsv.contains("usual_flow\tkikas_start\tby_prediction\t>8\tn\t2"), text);
    // the risk band: the predictions 20, 3, 9, 9 have Q3 11.75, so only a (observed 1) is marked;
    // observed long (above 8): c and d
    assertTrue(tsv.contains("all\tkikas_start\trisk\ttop_quarter\tthreshold_bd\t11.750"), text);
    assertTrue(tsv.contains("all\tkikas_start\trisk\ttop_quarter\tmarked_observed_long\t0"),
        text);
    assertTrue(tsv.contains("all\tkikas_start\trisk\ttop_quarter\tmarked_observed_not\t1"),
        text);
    assertTrue(tsv.contains("all\tkikas_start\trisk\ttop_quarter\tbase_rate\t0.500"), text);
    assertFalse(text.contains("own_retrospective"), "only the three Elastic Net start models");
    assertFalse(text.contains("global_median"));
    final String md = Files.readString(out.resolve(IssuesFirstCommitPredictedBandCommand.REPORT));
    assertTrue(md.contains("<=2") && md.contains(">8"));
    assertTrue(md.contains("Risk band (the quarter of the highest predictions"), md);
    assertTrue(md.indexOf("## Reading: full population") >= 0
        && md.indexOf("## Reading: full population") < md.indexOf("## Reading: usual flow"));
    try (Stream<Path> written = Files.list(out)) {
      assertEquals(2, written.count());
    }
  }

  @Test
  @DisplayName("a missing model column refuses the run and writes nothing")
  void missingColumnRefuses(@TempDir final Path dir) throws Exception {
    final Path report = report(dir, List.of());
    Files.write(report.resolve("similarity-test-predictions.tsv"),
        List.of("id\tobserved_business_days\tkikas_start", "a\t1.0\t2.0"));
    final Map<String, String> settings = new HashMap<>();
    settings.put("REPORT_DIR", report.toString());
    settings.put("OUT_DIR", dir.resolve("out").toString());
    assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitPredictedBandCommand(settings::get).run(null));
    assertFalse(Files.exists(dir.resolve("out")));
  }

  @Test
  @DisplayName("BAND_TOPS and USUAL_FENCE override the log's rounded figures")
  void overrides(@TempDir final Path dir) throws Exception {
    final Path report = report(dir, List.of("a\tK-1\tp\t1.0\t5\t2.5\t2.5\t2.5\t1"));
    final Map<String, String> settings = new HashMap<>();
    settings.put("REPORT_DIR", report.toString());
    settings.put("OUT_DIR", dir.resolve("out").toString());
    settings.put("BAND_TOPS", "2.5,5,8");
    settings.put("USUAL_FENCE", "30");
    new IssuesFirstCommitPredictedBandCommand(settings::get).run(null);
    final List<String> tsv = Files.readAllLines(dir.resolve("out")
        .resolve(IssuesFirstCommitPredictedBandCommand.TABLE));
    assertTrue(tsv.contains("all\tkikas_start\tby_prediction\t<=2.5\tn\t1"), tsv.toString());
  }

  @Test
  @DisplayName("the bands follow BAND_TOPS, not the test TSV's own quartiles")
  void bandsFollowBandTops(@TempDir final Path dir) throws Exception {
    // observed 1, 3, 30: quartiles of these would not be 10,20,25
    final Path report = report(dir, List.of(
        "a\tK-1\tp\t1.0\t5\t12.0\t12.0\t12.0\t1",
        "b\tK-2\tp\t3.0\t5\t3.0\t3.0\t3.0\t1",
        "c\tK-3\tp\t30.0\t5\t26.0\t26.0\t26.0\t1"));
    final Map<String, String> settings = new HashMap<>();
    settings.put("REPORT_DIR", report.toString());
    settings.put("OUT_DIR", dir.resolve("out").toString());
    settings.put("BAND_TOPS", "10,20,25");
    new IssuesFirstCommitPredictedBandCommand(settings::get).run(null);
    final List<String> tsv = Files.readAllLines(dir.resolve("out")
        .resolve(IssuesFirstCommitPredictedBandCommand.TABLE));
    assertTrue(tsv.contains("all\tkikas_start\tby_prediction\t<=10\tn\t1"), tsv.toString());
    assertTrue(tsv.contains("all\tkikas_start\tby_prediction\t(10, 20]\tn\t1"), tsv.toString());
    assertTrue(tsv.contains("all\tkikas_start\tby_prediction\t>25\tn\t1"), tsv.toString());
  }

  @Test
  @DisplayName("invalid BAND_TOPS or USUAL_FENCE refuse the run and write nothing")
  void invalidOverridesRefuse(@TempDir final Path dir) throws Exception {
    final Path report = report(dir, List.of("a\tK-1\tp\t1.0\t5\t2.5\t2.5\t2.5\t1"));
    for (final String[] bad : new String[][] {{"5,2,8", "30"}, {"2,5", "30"},
        {"2,5,NaN", "30"}, {"2,5,8", "0"}, {"2,5,8", "Infinity"}}) {
      final Map<String, String> settings = new HashMap<>();
      settings.put("REPORT_DIR", report.toString());
      settings.put("OUT_DIR", dir.resolve("out").toString());
      settings.put("BAND_TOPS", bad[0]);
      settings.put("USUAL_FENCE", bad[1]);
      assertThrows(CommandFailedException.class,
          () -> new IssuesFirstCommitPredictedBandCommand(settings::get).run(null));
    }
    assertFalse(Files.exists(dir.resolve("out")));
  }
}
