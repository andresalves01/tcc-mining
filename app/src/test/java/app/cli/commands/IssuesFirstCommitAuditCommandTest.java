package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommitFacts;
import app.issues.RowCache;
import app.issues.World;
import app.issues.port.ResultStore;
import app.issues.port.StudySource;
import app.pipeline.port.SilentProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssuesFirstCommitAuditCommandTest {

  @TempDir Path dir;

  private static final long DAY = World.DAY;
  private static final long MONDAY = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli();

  /** Ten issues in the population, each with two commits two business days apart. */
  private static StudySource source() {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Commit> commits = new ArrayList<>();
    final Map<String, FirstCommitFacts.Native> facts = new HashMap<>();
    for (int n = 1; n <= 10; n++) {
      final String id = String.format("%024x", n);
      issues.add(new Corpus.Issue(id, "P-" + n, "p", MONDAY, "rep", "rep", null, "Bug", "Major",
          null, List.of(), List.of()));
      events.add(new Corpus.Event(id, MONDAY + 20 * DAY, "status", "x", "Open", "Resolved",
          n + "%%0"));
      events.add(new Corpus.Event(id, MONDAY + 20 * DAY, "resolution", "x", null, "Fixed",
          n + "%%1"));
      commits.add(new Corpus.Commit("a" + n, "p", "ha" + n, "dev", MONDAY + DAY, MONDAY + DAY, 1,
          List.of(id)));
      commits.add(new Corpus.Commit("b" + n, "p", "hb" + n, "dev", MONDAY + 3 * DAY,
          MONDAY + 3 * DAY, 1, List.of(id)));
      facts.put("a" + n, new FirstCommitFacts.Native(10L, "start " + n));
      facts.put("b" + n, new FirstCommitFacts.Native(30L, "finish " + n));
    }
    final Corpus corpus = new Corpus(issues, events, List.of(), List.of(), commits,
        Map.of("p", Set.of("P")), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        List.of());
    return new StudySource() {
      @Override
      public Corpus load() {
        return corpus;
      }

      @Override
      public Map<String, Corpus.Text> texts(final Collection<String> ids) {
        throw new AssertionError("no text is read");
      }

      @Override
      public Map<String, DurationEvidence> durationEvidence(final List<RowCache.Entry> rows,
          final Set<String> bots) {
        final Map<String, DurationEvidence> out = new HashMap<>();
        rows.forEach(r -> out.put(r.unit().issueId(), new DurationEvidence(List.of(), List.of(),
            List.of(), "native", List.of(), List.of())));
        return out;
      }

      @Override
      public FirstCommitFacts firstCommitFacts(final Collection<String> commitIds,
          final Collection<String> shas, final Collection<String> attachments) {
        return new FirstCommitFacts(facts, Map.of(), Map.of());
      }

      @Override
      public String describe() {
        return "in memory";
      }
    };
  }

  /** A context whose only data port is the corpus reader: any database write fails. */
  private static Context context() {
    final ResultStore refuses = (run, rows) -> {
      throw new AssertionError("the audit records nothing in a database");
    };
    return new Context(null, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, source(), refuses, null);
  }

  @Test
  @DisplayName("writes the summary and the per-issue audit to AUDIT_DIR and nothing to a database")
  void writesFilesOnly() throws Exception {
    final Path out = dir.resolve("audit");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", out.toString())::get).run(context());
    final String md = Files.readString(out.resolve("audit.md"));
    assertTrue(md.contains("| Fixed na primeira resolução | 10 | — |"), md);
    assertTrue(md.contains("| 10 | 1 | 7 | 3 | 5 | 2 |"), "drawn, projects and the sides: " + md);
    final List<String> lines = Files.readAllLines(out.resolve("per-issue.tsv"));
    assertEquals(1 + 7, lines.size(), "the header and the training side, never the test side");
    for (final String line : lines.subList(1, lines.size())) {
      final String[] f = line.split("\t", -1);
      assertEquals("population", f[3]);
      assertEquals("2", f[f.length - 1], "Tuesday to Thursday");
    }
    try (var files = Files.list(out)) {
      assertEquals(Set.of("audit.md", "per-issue.tsv"),
          Set.copyOf(files.map(p -> p.getFileName().toString()).toList()));
    }
  }

  @Test
  @DisplayName("refuses to run without AUDIT_DIR")
  void needsDirectory() {
    assertThrows(CommandFailedException.class,
        () -> new IssuesFirstCommitAuditCommand(Map.<String, String>of()::get).run(context()));
  }
}
