package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import app.cli.Context;
import app.issues.Corpus;
import app.issues.DurationEvidence;
import app.issues.FirstCommit;
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
    return source(0);
  }

  /**
   * The same ten issues, issue n's dates moved {@code weeks * (n - 1)} weeks later; with 0 every
   * first commit is on the same instant.
   */
  private static StudySource source(final int weeks) {
    final List<Corpus.Issue> issues = new ArrayList<>();
    final List<Corpus.Event> events = new ArrayList<>();
    final List<Corpus.Commit> commits = new ArrayList<>();
    final Map<String, FirstCommitFacts.Native> facts = new HashMap<>();
    for (int n = 1; n <= 10; n++) {
      final String id = String.format("%024x", n);
      final long monday = MONDAY + 7L * weeks * (n - 1) * DAY;
      issues.add(new Corpus.Issue(id, "P-" + n, "p", monday, "rep", "rep", null, "Bug", "Major",
          null, List.of(), List.of()));
      events.add(new Corpus.Event(id, monday + 20 * DAY, "status", "x", "Open", "Resolved",
          n + "%%0"));
      events.add(new Corpus.Event(id, monday + 20 * DAY, "resolution", "x", null, "Fixed",
          n + "%%1"));
      commits.add(new Corpus.Commit("a" + n, "p", "ha" + n, "dev", monday + DAY, monday + DAY, 1,
          List.of(id)));
      commits.add(new Corpus.Commit("b" + n, "p", "hb" + n, "dev", monday + 3 * DAY,
          monday + 3 * DAY, 1, List.of(id)));
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
    return context(source());
  }

  private static Context context(final StudySource source) {
    final ResultStore refuses = (run, rows) -> {
      throw new AssertionError("the audit records nothing in a database");
    };
    return new Context(null, null, null, null, null, null, null, null, null, null, null,
        SilentProgress.INSTANCE, source, refuses, null);
  }

  @Test
  @DisplayName("writes the summary and the per-issue audit to AUDIT_DIR and nothing to a database")
  void writesFilesOnly() throws Exception {
    final Path out = dir.resolve("audit");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", out.toString())::get).run(context());
    final String md = Files.readString(out.resolve("audit.md"));
    assertTrue(md.contains("| Fixed na primeira resolução | 10 | — |"), md);
    assertTrue(md.contains("| 10 | 1 | 7 | 3 | 5 | 2 |"), "drawn, projects and the sides: " + md);
    assertTrue(md.contains("| teste externo | — | — | — | — | 3 |"), "only the test's total: " + md);
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

  @Test
  @DisplayName("under SPLIT_RULE=temporal the summary gives the cuts, the sides, the crossings "
      + "and the censoring, and of the test only counts and dates")
  void temporal() throws Exception {
    final Path out = dir.resolve("temporal");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", out.toString(),
        "SPLIT_RULE", "temporal")::get).run(context(source(1)));
    final String md = Files.readString(out.resolve("audit.md"));
    assertTrue(md.contains("| 10 | 1 | 7 | 3 | 5 | 2 |"), md);
    assertTrue(md.contains("temporal: the candidates ordered by their first commit"), md);
    assertTrue(md.contains("2024-02-13T12:00:00Z"), "the outer cut, Tuesday of week 6: " + md);
    assertTrue(md.contains("2024-01-30T12:00:00Z"), "the inner cut, Tuesday of week 4: " + md);
    assertTrue(md.contains("| teste externo | 3 | 1 | — | — | — | — |"),
        "of the test, its size and its projects alone: " + md);
    assertTrue(md.contains("## Censura no fim dos dados"), md);
    assertTrue(md.contains("Issues de treino que cruzam o corte externo"), md);
    assertTrue(md.contains("divisão aleatória"), "the random rule's cap beside it: " + md);
    final List<String> lines = Files.readAllLines(out.resolve("per-issue.tsv"));
    assertEquals(1 + 7, lines.size(), "the header and the training side, never the test side");
  }

  @Test
  @DisplayName("the random rule, the default, writes none of the temporal sections")
  void randomHasNoTemporalSections() throws Exception {
    final Path out = dir.resolve("random");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", out.toString())::get)
        .run(context(source(1)));
    final String md = Files.readString(out.resolve("audit.md"));
    assertFalse(md.contains("Censura"), md);
    assertFalse(md.contains("Corte externo"), md);
  }

  @Test
  @DisplayName("the default wait fence leaves audit.md as it was: no line about the k")
  void defaultFenceLeavesTheSummaryAsItWas() throws Exception {
    final Path out = dir.resolve("default-fence");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", out.toString())::get)
        .run(context(source(1)));
    final String md = Files.readString(out.resolve("audit.md"));
    assertTrue(md.contains("Cerca (Q3 + 1,5 IQR)"), md);
    assertFalse(md.contains("Cerca das esperas"), md);
    assertTrue(md.contains("Regra: " + FirstCommit.RULE + "."), md);
  }

  @Test
  @DisplayName("WAIT_FENCE_K=3 and none are named in the header, the table and the rule, and an "
      + "infinite fence is written as a word")
  void waitFenceIsRecorded() throws Exception {
    final Path three = dir.resolve("k3");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", three.toString(),
        "WAIT_FENCE_K", "3")::get).run(context(source(1)));
    final String md3 = Files.readString(three.resolve("audit.md"));
    assertTrue(md3.contains("Cerca das esperas: Q3 + 3 IQR (sensibilidade; a oficial é 1,5)"), md3);
    assertTrue(md3.contains("Cerca (Q3 + 3 IQR)"), md3);
    assertTrue(md3.contains("Regra: " + FirstCommit.rule(3.0) + "."), md3);

    final Path none = dir.resolve("none");
    new IssuesFirstCommitAuditCommand(Map.of("AUDIT_DIR", none.toString(),
        "WAIT_FENCE_K", "none", "SPLIT_RULE", "temporal")::get).run(context(source(1)));
    final String mdNone = Files.readString(none.resolve("audit.md"));
    assertTrue(mdNone.contains("Cerca das esperas: sem teto (sensibilidade; a oficial é 1,5)"),
        mdNone);
    assertTrue(mdNone.contains("Regra: " + FirstCommit.rule(FirstCommit.NO_WAIT_CAP) + "."),
        mdNone);
    assertFalse(mdNone.contains("Infinity") || mdNone.contains("NaN"), mdNone);
    assertTrue(mdNone.contains("| sem cerca | nenhum |"), "no cap, not a number of days: " + mdNone);
    assertFalse(mdNone.contains("2.147.483.647"), mdNone);
  }

  @Test
  @DisplayName("refuses a WAIT_FENCE_K that is zero, negative, NaN or not a number, before reading")
  void invalidWaitFence() {
    for (final String bad : List.of("0", "-1", "NaN", "abc")) {
      assertThrows(CommandFailedException.class, () -> new IssuesFirstCommitAuditCommand(
          Map.of("AUDIT_DIR", dir.resolve("bad").toString(), "WAIT_FENCE_K", bad)::get)
          .run(context(null)), bad);
    }
  }

  @Test
  @DisplayName("refuses a SPLIT_RULE it does not know")
  void unknownRule() {
    assertThrows(CommandFailedException.class, () -> new IssuesFirstCommitAuditCommand(
        Map.of("AUDIT_DIR", dir.toString(), "SPLIT_RULE", "monthly")::get).run(context()));
  }
}
