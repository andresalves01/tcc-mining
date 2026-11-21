package app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.commands.IssuesSimilarityTestCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudySettingsTest {

  private static final Path RUN = Path.of("/runs/revised").toAbsolutePath().normalize();

  /** The settings a command ends up with, from the given ones plus the study's. */
  private static Map<String, String> resolved(final Command command, final String... given) {
    final Map<String, String> settings = new HashMap<>();
    for (int i = 0; i < given.length; i += 2) {
      settings.put(given[i], given[i + 1]);
    }
    StudySettings.apply(command, settings::get, settings::put);
    return settings;
  }

  private static Map<String, String> inRun(final Command command, final String... given) {
    return resolved(command, Stream.concat(Stream.of(StudySettings.RUN_DIR, RUN.toString(),
        "ISSUES_DB", "smartshark_first_commit_issues_check"), Stream.of(given))
        .toArray(String[]::new));
  }

  @Test
  @DisplayName("a stage of the study runs the revised first-commit design")
  void runsTheRevisedDesign() {
    final Map<String, String> s = inRun(Command.ISSUES_IN_PROGRESS_EXTRACT);
    assertEquals("FIRST_COMMIT", s.get("POPULATION"));
    assertEquals("revised", s.get("SPLIT_RULE"));
    assertEquals("smartshark_first_commit", s.get("TARGET_DB"));
    assertEquals("smartshark_tcc", s.get("PREPARED_DB"));
    assertEquals(RUN.resolve("first-commit-matrix.tsv").toString(), s.get("CACHE"));
    assertFalse(s.containsKey("WAIT_FENCE_K"), "the wait fence stays the official 1.5");
  }

  @Test
  @DisplayName("a fixed setting is refused, for the study and for the preparation alike")
  void fixedIsRefused() {
    final IllegalArgumentException study = assertThrows(IllegalArgumentException.class,
        () -> inRun(Command.ISSUES_SIMILARITY_ELASTIC_NET, "SPLIT_RULE", "temporal",
            "MATRIX", "/elsewhere/matrix.tsv"));
    assertTrue(study.getMessage().startsWith("MATRIX, SPLIT_RULE are fixed"), study.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> resolved(Command.FUNNEL, "PILOT", "derby"));
    assertThrows(IllegalArgumentException.class,
        () -> resolved(Command.BUILD_LEDGER, "TARGET_DB", "elsewhere"));
  }

  @Test
  @DisplayName("the sensitivity's wait fence passes through untouched")
  void waitFenceIsOpen() {
    assertEquals("3", inRun(Command.ISSUES_IN_PROGRESS_EXTRACT, "WAIT_FENCE_K", "3")
        .get("WAIT_FENCE_K"));
  }

  @Test
  @DisplayName("a stage of the study needs RUN_DIR and ISSUES_DB, and fills nothing without them")
  void needsRunDirAndIssuesDb() {
    final Map<String, String> none = new HashMap<>();
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> StudySettings.apply(Command.ISSUES_FIRST_COMMIT_TEST, none::get, none::put))
        .getMessage().contains("RUN_DIR"));
    assertTrue(none.isEmpty());
    final Map<String, String> noDb = new HashMap<>(Map.of(StudySettings.RUN_DIR, RUN.toString()));
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> StudySettings.apply(Command.ISSUES_IN_PROGRESS_EXTRACT, noDb::get, noDb::put))
        .getMessage().contains("ISSUES_DB"));
    assertEquals(1, noDb.size());
  }

  @Test
  @DisplayName("the model download needs no run and keeps the model where the tests look")
  void downloadNeedsNoRun() {
    assertEquals(Path.of(System.getProperty("user.home"), ".cache", "tcc-minilm-java").toString(),
        resolved(Command.ISSUES_DOWNLOAD_MODEL).get("MODEL_DIR"));
    assertEquals("/models", resolved(Command.ISSUES_DOWNLOAD_MODEL, "MODEL_DIR", "/models")
        .get("MODEL_DIR"));
  }

  @Test
  @DisplayName("the preparation fills nothing in: its defaults are the fixed ones")
  void preparationFillsNothing() {
    for (final Command command : StudySettings.PREPARATION) {
      assertEquals(Map.of(), resolved(command), command.token());
    }
  }

  @Test
  @DisplayName("every command is either preparation or a stage of the study")
  void everyCommandIsAccountedFor() {
    assertEquals(Set.of(Command.values()), Set.copyOf(Help.listed()));
    assertEquals(Command.values().length, Help.listed().size(), "listed once");
  }

  @Test
  @DisplayName("every stage reads the file the stage before it wrote")
  void stagesChain() {
    assertEquals(inRun(Command.ISSUES_IN_PROGRESS_EXTRACT).get("CACHE"),
        inRun(Command.ISSUES_IN_PROGRESS_TEAM).get("CACHE"));
    final Map<String, String> similarity = inRun(Command.ISSUES_SIMILARITY);
    assertEquals(inRun(Command.ISSUES_IN_PROGRESS_EXTRACT).get("CACHE"), similarity.get("CACHE"));
    assertEquals(inRun(Command.ISSUES_IN_PROGRESS_TEAM).get("TEAM_COLUMNS"),
        similarity.get("TEAM_COLUMNS"));
    final Map<String, String> retrospective = inRun(Command.ISSUES_RETROSPECTIVE);
    assertEquals(similarity.get("MATRIX"), retrospective.get("MATRIX"));
    final String matrix = retrospective.get("RETROSPECTIVE_MATRIX");
    final String parameters =
        inRun(Command.ISSUES_SIMILARITY_ELASTIC_NET).get("ELASTIC_NET_PARAMETERS");
    for (final Command reader : List.of(Command.ISSUES_SIMILARITY_ELASTIC_NET,
        Command.ISSUES_SIMILARITY_INNER_REPORT, Command.ISSUES_FIRST_COMMIT_SHARE_ABLATION,
        Command.ISSUES_FIRST_COMMIT_TEST, Command.ISSUES_FIRST_COMMIT_SAME_MODEL_CALIBRATION)) {
      assertEquals(matrix, inRun(reader).get("MATRIX"), reader.token());
      assertEquals(parameters, inRun(reader).get("ELASTIC_NET_PARAMETERS"), reader.token());
    }
    final String testReport = inRun(Command.ISSUES_FIRST_COMMIT_TEST).get("REPORT_DIR");
    assertEquals(Path.of(testReport, IssuesSimilarityTestCommand.PREDICTIONS).toString(),
        inRun(Command.ISSUES_FIRST_COMMIT_SAME_MODEL_CALIBRATION).get("TEST_PREDICTIONS"));
    assertEquals(testReport, inRun(Command.ISSUES_FIRST_COMMIT_PREDICTED_BAND).get("REPORT_DIR"));
    assertFalse(inRun(Command.ISSUES_FIRST_COMMIT_SAME_MODEL_CALIBRATION).get("REPORT_DIR")
        .startsWith(testReport), "the calibration writes outside the test's folder");
  }

  @Test
  @DisplayName("every setting the code reads is either open or fixed")
  void everySettingIsClassified() throws IOException {
    final Pattern read = Pattern.compile(
        "(?:path|required|optional|apply|setting|value|getenv|getProperty)\\(\\s*\"([A-Z][A-Z0-9_]{2,})\"");
    final Pattern constant = Pattern.compile(
        "static final String [A-Z_]+ = \"([A-Z][A-Z0-9_]{2,})\"");
    final Set<String> unclassified = new TreeSet<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        final String source = Files.readString(file);
        for (final Pattern pattern : List.of(read, constant)) {
          final Matcher m = pattern.matcher(source);
          while (m.find()) {
            final String name = m.group(1);
            if (!StudySettings.OPEN.contains(name) && !StudySettings.FIXED.contains(name)
                && !name.equals("REQUIRE_MODEL")) {
              unclassified.add(name + " (" + file.getFileName() + ")");
            }
          }
        }
      }
    }
    assertTrue(unclassified.isEmpty(), "read but neither open nor fixed: " + unclassified);
  }
}
