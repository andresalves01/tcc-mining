package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.Augmentation;
import app.issues.BusinessDays;
import app.issues.IssueUnit;
import app.issues.Retrospective;
import app.issues.RowCache;
import app.issues.Universe.Population;
import app.pipeline.port.Progress;
import app.study.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The retrospective stage's refusals, before the corpus is read: the tests hand their own settings
 * and a context with no corpus, so no real matrix or database can ever be read or written.
 */
class IssuesRetrospectiveCommandTest {

  private final Map<String, String> settings = new HashMap<>();

  @TempDir
  Path dir;

  private IssuesRetrospectiveCommand command() {
    return new IssuesRetrospectiveCommand(settings::get);
  }

  /** A context with nothing to read and a result store that fails the test if anything is recorded. */
  private static Context context() {
    return new Context(null, null, null, null, null, null, null, null, null, null, null,
        Progress.silent(), null, (run, rows) -> {
          throw new AssertionError("recorded " + run);
        }, null);
  }

  /** A matrix of one row with the given columns. */
  private Path matrix(final String name, final List<String> columns) throws IOException {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {"TRAIN", "TRAIN"});
    final Map<String, Double> values = new HashMap<>();
    columns.forEach(c -> values.put(c, 1.0));
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    final Path path = dir.resolve(name);
    RowCache.write(path, List.of(new RowCache.Entry(new IssueUnit("a", "P-1", "p", "r", "d", t,
        t.plusSeconds(86_400 * 5), "Fixed", false, t.plusSeconds(3600)),
        new Row("a", values, Set.of()), parts)), columns, List.of(Population.IN_PROGRESS));
    return path;
  }

  /**
   * The similarity stage's columns: the extracted one, the medians, the start columns of ticket 19,
   * the day boundary and the active business days.
   */
  private static List<String> similarity() {
    final List<String> out = new ArrayList<>(List.of("A01_desc_length"));
    out.addAll(app.pipeline.IssuesSimilarityTask.columns());
    out.addAll(BusinessDays.COLUMNS);
    return out;
  }

  private String refusal() {
    final CommandFailedException e = assertThrows(CommandFailedException.class,
        () -> command().run(context()));
    return String.join("\n", e.lines());
  }

  @Test
  @DisplayName("MATRIX and RETROSPECTIVE_MATRIX are both required, and must be two files")
  void twoFiles() throws IOException {
    assertTrue(refusal().contains("required"));
    final Path in = matrix("in.tsv", similarity());
    settings.put("MATRIX", in.toString());
    assertTrue(refusal().contains("RETROSPECTIVE_MATRIX"));
    settings.put("RETROSPECTIVE_MATRIX", in.toString());
    final String before = Files.readString(in);
    assertTrue(refusal().contains("two different files"));
    assertEquals(before, Files.readString(in));
  }

  @Test
  @DisplayName("a matrix without the similarity stage's columns or the day boundary, or one that "
      + "already has the retrospective columns, refuses the run")
  void refusesAnotherMatrix() throws IOException {
    settings.put("RETROSPECTIVE_MATRIX", dir.resolve("out.tsv").toString());
    settings.put("MATRIX", matrix("cache.tsv", List.of("A01_desc_length")).toString());
    assertTrue(refusal().contains("issues-in-progress-similarity"));
    final List<String> twice = new ArrayList<>(similarity());
    twice.addAll(Retrospective.COLUMNS);
    settings.put("MATRIX", matrix("twice.tsv", twice).toString());
    assertTrue(refusal().contains("already has"));
    assertFalse(Files.exists(dir.resolve("out.tsv")));
  }

  @Test
  @DisplayName("an existing output that is not a retrospective matrix is never overwritten")
  void neverOverwritesAnotherFile() throws IOException {
    settings.put("MATRIX", matrix("in.tsv", similarity()).toString());
    final Path other = Files.writeString(dir.resolve("parameters.tsv"), "something else\n");
    settings.put("RETROSPECTIVE_MATRIX", other.toString());
    assertTrue(refusal().contains("is not a retrospective matrix"));
    assertEquals("something else\n", Files.readString(other));
  }

  @Test
  @DisplayName("the run record names the scenario retrospective and says its counts grow with the "
      + "duration itself")
  void statements() {
    final String scenario = IssuesRetrospectiveCommand.statements().get("scenario").toString();
    assertTrue(scenario.startsWith("retrospective"), scenario);
    assertTrue(scenario.contains("grow with the duration itself"), scenario);
  }
}
