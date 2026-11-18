package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.Command;
import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.port.ResultStore;
import app.pipeline.IssuesFirstCommitSupportingNumbersTaskTest;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The supporting numbers as files: counts and worked examples, read only. */
class IssuesFirstCommitSupportingNumbersCommandTest {

  @TempDir Path dir;

  private static Context context() {
    final ResultStore refuses = (run, rows) -> {
      throw new AssertionError("the supporting numbers record nothing in a database");
    };
    return new Context(null, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, IssuesFirstCommitSupportingNumbersTaskTest.source(), refuses,
        null);
  }

  private static Map<String, String> settings(final Path out, final String... more) {
    final Map<String, String> s = new java.util.HashMap<>();
    s.put("SUPPORTING_DIR", out.toString());
    s.put("SPLIT_RULE", "temporal");
    for (int i = 0; i < more.length; i += 2) {
      s.put(more[i], more[i + 1]);
    }
    return s;
  }

  @Test
  @DisplayName("writes the counts, the worked examples in prose and in a table, and nothing else")
  void writesThreeFiles() throws Exception {
    final Path out = dir.resolve("out");
    new IssuesFirstCommitSupportingNumbersCommand(settings(out)::get).run(context());
    try (var files = Files.list(out)) {
      assertEquals(Set.of("filter-counts.md", "worked-example.md", "worked-example.tsv"),
          Set.copyOf(files.map(p -> p.getFileName().toString()).toList()));
    }
    final String counts = Files.readString(out.resolve("filter-counts.md"));
    assertTrue(counts.contains("Criada depois do primeiro commit (commit antes da criação)"), counts);
    assertTrue(counts.contains("| Fixed na primeira resolução, com algum commit que conta | 10 |"),
        counts);
    assertTrue(counts.contains("| Primeiro commit antes do In Progress | 6 | 6 |"), counts);
    assertTrue(counts.contains("só entre as issues que tiveram In Progress"), counts);
    assertTrue(counts.contains("antes da remoção das duplicatas"), counts);

    final String md = Files.readString(out.resolve("worked-example.md"));
    assertTrue(md.contains("treino externo"), md);
    assertTrue(md.contains("calendar_minus_study") && md.contains("capped") && md.contains("tail"),
        md);
    assertTrue(md.contains("Marcas") && md.contains("Esperas") && md.contains("Cauda"), md);
    assertFalse(md.contains("P-10 "), "the outer test's issue is not shown: " + md);

    final List<String> rows = Files.readAllLines(out.resolve("worked-example.tsv"));
    assertEquals(1 + 3, rows.size(), "the header and the three examples");
    assertTrue(rows.getFirst().startsWith("criterion\tvalue\tnote\tid\tkey"), rows.getFirst());
  }

  @Test
  @DisplayName("refuses to run without SUPPORTING_DIR, and refuses an invalid WAIT_FENCE_K or "
      + "SPLIT_RULE before reading")
  void refusals() {
    assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitSupportingNumbersCommand(Map.<String, String>of()::get)
            .run(context()));
    assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitSupportingNumbersCommand(
            settings(dir.resolve("a"), "WAIT_FENCE_K", "0")::get).run(context()));
    assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitSupportingNumbersCommand(
            settings(dir.resolve("b"), "SPLIT_RULE", "monthly")::get).run(context()));
  }

  @Test
  @DisplayName("the wait fence's k of the run is named in the examples, and none cuts no wait")
  void namesTheFence() throws Exception {
    final Path out = dir.resolve("none");
    new IssuesFirstCommitSupportingNumbersCommand(
        settings(out, "WAIT_FENCE_K", "none")::get).run(context());
    final String md = Files.readString(out.resolve("worked-example.md"));
    assertTrue(md.contains("sem teto"), md);
    assertFalse(md.contains("Infinity"), md);
  }

  @Test
  @DisplayName("WAIT_FENCE_K=3 is written as in the audit: Q3 + 3 IQR, never 3,0; the counts split "
      + "the creation in after and at the first commit")
  void fenceThreeAndSplitCreation() throws Exception {
    final Path out = dir.resolve("k3");
    new IssuesFirstCommitSupportingNumbersCommand(settings(out, "WAIT_FENCE_K", "3")::get)
        .run(context());
    final String md = Files.readString(out.resolve("worked-example.md"));
    assertTrue(md.contains("Cerca das esperas: Q3 + 3 IQR."), md);
    assertFalse(md.contains("3,0"), md);
    final String counts = Files.readString(out.resolve("filter-counts.md"));
    assertTrue(counts.contains("| Criada no mesmo instante do primeiro commit |"), counts);
  }

  @Test
  @DisplayName("is a registered command")
  void registered() {
    assertEquals(Command.ISSUES_FIRST_COMMIT_SUPPORTING_NUMBERS,
        Command.parse("issues-first-commit-supporting-numbers").orElseThrow());
  }
}
