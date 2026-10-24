package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.contribution.Contribution;
import app.contribution.ContributionScan;
import app.contribution.port.ContributionLedger;
import app.contribution.port.ContributionSource;
import app.identity.Identity;
import app.identity.PersonRecord;
import app.identity.RefusedBridge;
import app.identity.port.PersonRegistry;
import app.pipeline.port.Progress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import app.pipeline.port.SilentProgress;

/** Exercises the ledger use cases against in-memory doubles, with no MongoDB and no corpus. */
class LedgerPipelineTest {

  private static final Instant T0 = Instant.parse("2016-06-01T00:00:00Z");

  /** The tasks report progress through a port, so a test run stays quiet. */
  private static final Progress SILENT = SilentProgress.INSTANCE;

  private static final class InMemoryLedger implements ContributionLedger {

    private final Map<String, Contribution> rows = new LinkedHashMap<>();

    @Override
    public long save(final Collection<Contribution> contributions) {
      contributions.forEach(c -> rows.put(c.commitId(), c));
      return contributions.size();
    }

    @Override
    public List<Contribution> readAll() {
      return new ArrayList<>(rows.values());
    }

    public long count() {
      return rows.size();
    }

    @Override
    public String describe() {
      return "in-memory";
    }

  }

  /** Only the two reads the ledger task needs; the rest would be noise here. */
  private static final class StubRegistry implements PersonRegistry {

    private final Map<String, String> canonical;
    private final Set<String> bots;

    StubRegistry(final Map<String, String> canonical, final Set<String> bots) {
      this.canonical = canonical;
      this.bots = bots;
    }

    @Override
    public Map<String, String> readCanonical() {
      return canonical;
    }

    @Override
    public Set<String> readBots() {
      return bots;
    }

    @Override
    public Map<String, PersonRecord> readAll() {
      return Map.of();
    }

    @Override
    public Map<String, Integer> readAuthoredCommits() {
      return Map.of();
    }

    @Override
    public long saveRefusedBridges(final Collection<RefusedBridge> refusals) {
      return 0;
    }

    @Override
    public long saveIdentities(final Collection<PersonRecord> records) {
      return 0;
    }

    @Override
    public long saveCanonical(
        final Map<String, String> canonical,
        final Map<String, PersonRecord> records,
        final Map<String, Integer> authoredCommits) {
      return 0;
    }

    @Override
    public String describe() {
      return "stub";
    }

    @Override
    public String describeIdentities() {
      return describe() + ".people";
    }

    @Override
    public String describeCanonical() {
      return describe() + ".person_canonical";
    }
  }

  private static Contribution contribution(
      final String commitId, final String developer, final boolean bot, final int lines) {
    return new Contribution(commitId, "hash-" + commitId, "proj", "proj", "vcs",
        developer, developer, bot, T0, lines, 0, lines, 0, 1, 1);
  }

  @Test
  @DisplayName("the scan is handed the identity map and its result reaches the ledger")
  void buildsAndPersists() {
    final Map<String, String> canonical = Map.of("alias1", "person1", "alias2", "person1");
    final AtomicReference<Map<String, String>> seen = new AtomicReference<>();

    final ContributionSource source = (identities, bots, pilot) -> {
      seen.set(identities);
      return new ContributionScan(
          List.of(contribution("c1", "person1", false, 40)), 3, 1, 1, 9, 0, 0);
    };
    final InMemoryLedger ledger = new InMemoryLedger();

    final BuildLedgerTask.Report report = run(() ->
        new BuildLedgerTask(source, ledger, new StubRegistry(canonical, Set.of()), SILENT)
            .run());

    assertEquals(canonical, seen.get(), "the scan must resolve authors with the stored merge");
    assertEquals(1, report.written());
    assertEquals(1, ledger.count());
    assertEquals(40, report.scan().linesAdded());
  }

  @Test
  @DisplayName("refuses to scan 84 GB when there are no identities to resolve authors with")
  void refusesToRunAgainstAnEmptyRegistry() {
    // Without the merge relation every author is unresolvable, so the run would read the whole
    // corpus and write an empty ledger -- a result that looks like a corpus defect rather than a
    // stage run out of order.
    final ContributionSource neverCalled = (identities, bots, pilot) -> {
      throw new AssertionError("the corpus must not be read without identities");
    };

    final BuildLedgerTask task = new BuildLedgerTask(
        neverCalled, new InMemoryLedger(), new StubRegistry(Map.of(), Set.of()), SILENT);

    assertThrows(BuildLedgerTask.RegistryEmptyException.class, task::run);
  }

  @Test
  @DisplayName("re-running repairs rows instead of doubling the ledger")
  void isIdempotent() {
    final ContributionSource source = (identities, bots, pilot) -> new ContributionScan(
        List.of(contribution("c1", "person1", false, 40), contribution("c2", "person1", false, 2)),
        2, 0, 0, 4, 0, 0);
    final InMemoryLedger ledger = new InMemoryLedger();
    final StubRegistry registry = new StubRegistry(Map.of("alias1", "person1"), Set.of());

    run(() -> new BuildLedgerTask(source, ledger, registry, SILENT).run());
    run(() -> new BuildLedgerTask(source, ledger, registry, SILENT).run());

    assertEquals(2, ledger.count(), "the commit id is the key, so a second pass overwrites");
  }

  @Test
  @DisplayName("bots are kept in the ledger, flagged as bots")
  void botsAreFlaggedNotDropped() {
    // Keeping them makes "how much volume is automated" answerable later.
    final ContributionSource source = (identities, bots, pilot) -> new ContributionScan(
        List.of(
            contribution("c1", "human-a", false, 10),
            contribution("c2", "human-b", false, 20),
            contribution("c3", "robot", true, 5_000_000)),
        3, 0, 0, 3, 0, 1);

    final InMemoryLedger ledger = new InMemoryLedger();
    run(() -> new BuildLedgerTask(
        source, ledger, new StubRegistry(Map.of("alias", "human-a"), Set.of("robot")), SILENT)
        .run());

    assertEquals(3, ledger.count(), "the bot's row stays on disk");

    assertEquals(Set.of("robot"), ledger.readAll().stream().filter(Contribution::bot)
        .map(Contribution::canonicalId).collect(java.util.stream.Collectors.toSet()));
  }

  private static <T> T run(final ThrowingSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (final RuntimeException e) {
      throw e;
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }
}
