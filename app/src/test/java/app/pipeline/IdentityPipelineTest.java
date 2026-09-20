package app.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.identity.GitReconstruction;
import app.identity.Identity;
import app.identity.JiraResolution;
import app.identity.PersonRecord;
import app.identity.RefusedBridge;
import app.identity.Strictness;
import app.identity.port.GitIdentitySource;
import app.identity.port.JiraIdentitySource;
import app.identity.port.PersonRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the use cases against in-memory doubles.
 *
 * <p>These tests are the reason the ports exist. The behaviour worth protecting — aborting on a
 * broken bijection, refusing to harvest into an empty registry, linking the two id spaces end to
 * end — used to be reachable only by running the real thing against 686 GB of MongoDB and a live
 * issue tracker. Here it runs in milliseconds with neither.
 */
class IdentityPipelineTest {

  /** Stands in for MongoDB. */
  private static final class InMemoryRegistry implements PersonRegistry {

    private final Map<String, PersonRecord> people = new LinkedHashMap<>();
    private Map<String, String> canonical = Map.of();
    private Map<String, Integer> authored = Map.of();
    private List<RefusedBridge> refused = List.of();

    @Override
    public Map<String, PersonRecord> readAll() {
      return new LinkedHashMap<>(people);
    }

    @Override
    public Map<String, Integer> readAuthoredCommits() {
      return Map.copyOf(authored);
    }

    @Override
    public Map<String, String> readCanonical() {
      return Map.copyOf(canonical);
    }

    @Override
    public Set<String> readBots() {
      final Set<String> bots = new LinkedHashSet<>();
      people.values().stream().filter(record -> record.identity().isBot()).forEach(record ->
          bots.add(canonical.getOrDefault(record.personId(), record.personId())));
      return bots;
    }

    @Override
    public long saveIdentities(final Collection<PersonRecord> records) {
      records.forEach(record -> people.put(record.personId(), record));
      return records.size();
    }

    @Override
    public long saveRefusedBridges(final Collection<RefusedBridge> refusals) {
      refused = List.copyOf(refusals);
      return refusals.size();
    }

    @Override
    public long saveCanonical(
        final Map<String, String> canonical,
        final Map<String, PersonRecord> records,
        final Map<String, Integer> authoredCommits) {
      this.canonical = Map.copyOf(canonical);
      this.authored = Map.copyOf(authoredCommits);
      return canonical.size();
    }

    @Override
    public String describe() {
      return "in-memory";
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

  private static GitIdentitySource gitSource(final GitReconstruction reconstruction) {
    return () -> reconstruction;
  }

  @Test
  @DisplayName("a successful reconstruction populates the registry and canonicalises")
  void reconstructsAndCanonicalises() {
    final Map<String, Identity> identities = new LinkedHashMap<>();
    identities.put("aaaaaaaaaaaaaaaaaaaaaaa1", new Identity("Gary Gregory", "ggregory@apache.org"));
    identities.put("aaaaaaaaaaaaaaaaaaaaaaa2", new Identity("Gary D. Gregory", "ggregory@apache.org"));
    identities.put("aaaaaaaaaaaaaaaaaaaaaaa3", new Identity("Andy Seaborne", "andy@apache.org"));

    final GitReconstruction reconstruction = new GitReconstruction(
        identities, Map.of("aaaaaaaaaaaaaaaaaaaaaaa1", 5_040), 452_286, 59, List.of());

    final InMemoryRegistry registry = new InMemoryRegistry();
    final ReconstructPeopleTask.Report report = run(() -> new ReconstructPeopleTask(
        gitSource(reconstruction), registry, Strictness.EMAIL_AND_FULL_NAME).run());

    assertEquals(3, report.identitiesWritten());
    assertEquals(3, report.canonical().identities());
    assertEquals(2, report.canonical().canonicalPeople()); // the two Garys collapse
    assertEquals(1, report.canonical().merged());
    assertEquals(5_040, registry.authored.get("aaaaaaaaaaaaaaaaaaaaaaa1"));
  }

  @Test
  @DisplayName("a broken bijection aborts instead of silently picking a winner")
  void abortsWhenTheJoinIsNotBijective() {
    // pycoshark's name-and-email primary key guarantees this cannot happen. If it ever does, the
    // guarantee is false for this corpus and every merge below it would be unsound -- so the task
    // must refuse rather than choose.
    final GitReconstruction reconstruction = new GitReconstruction(
        Map.of("bbbbbbbbbbbbbbbbbbbbbbb1", new Identity("Someone", "someone@example.org")),
        Map.of(), 10, 0,
        List.of("bbbbbbbbbbbbbbbbbbbbbbb1 -> [Someone <a@x>, Someone Else <b@y>]"));

    final InMemoryRegistry registry = new InMemoryRegistry();
    final ReconstructPeopleTask task =
        new ReconstructPeopleTask(gitSource(reconstruction), registry, Strictness.EMAIL_ONLY);

    final ReconstructPeopleTask.NotBijectiveException thrown =
        assertThrows(ReconstructPeopleTask.NotBijectiveException.class, task::run);

    assertEquals(1, thrown.violations().size());
    assertTrue(registry.readAll().isEmpty(), "nothing may be written when the join is unsound");
  }

  @Test
  @DisplayName("harvesting refuses to run against an empty registry")
  void refusesToHarvestWithoutGitIdentities() {
    // Without git identities there is nothing for a Jira username or display name to match, so the
    // harvest would produce an isolated island of people rather than a bridge between the spaces.
    final JiraIdentitySource neverCalled = known -> {
      throw new AssertionError("the issue tracker must not be contacted when the registry is empty");
    };

    final HarvestJiraTask task =
        new HarvestJiraTask(neverCalled, new InMemoryRegistry(), Strictness.EMAIL_ONLY);

    assertThrows(HarvestJiraTask.RegistryEmptyException.class, task::run);
  }

  @Test
  @DisplayName("harvesting bridges the Jira and git id spaces")
  void harvestLinksTheTwoIdSpaces() {
    final InMemoryRegistry registry = new InMemoryRegistry();
    registry.saveIdentities(List.of(PersonRecord.git(
        "ccccccccccccccccccccccc1", new Identity("Stian Soiland-Reyes", "stain@apache.org"))));

    // The verified case: Jira assignee `stain` is the same human as stain@apache.org, even though
    // the two ObjectIds share nothing.
    final JiraIdentitySource source = known -> {
      assertTrue(known.contains("ccccccccccccccccccccccc1"),
          "known ids must be passed so they are not refetched");
      return new JiraResolution(
          List.of(PersonRecord.jira("ddddddddddddddddddddddd1", "Stian Soiland-Reyes", "stain")),
          1, 0, 0);
    };

    final HarvestJiraTask.Report report =
        run(() -> new HarvestJiraTask(source, registry, Strictness.EMAIL_ONLY).run());

    assertEquals(2, report.canonical().identities());
    assertEquals(1, report.canonical().canonicalPeople(),
        "the git and Jira ids must resolve to one person");
    assertEquals(
        registry.canonical.get("ccccccccccccccccccccccc1"),
        registry.canonical.get("ddddddddddddddddddddddd1"));
  }

  @Test
  @DisplayName("the Jira source is asked only for ids the registry lacks")
  void skipsAlreadyResolvedIds() {
    final InMemoryRegistry registry = new InMemoryRegistry();
    registry.saveIdentities(List.of(PersonRecord.git(
        "eeeeeeeeeeeeeeeeeeeeeee1", new Identity("Someone", "someone@example.org"))));

    final AtomicReference<Set<String>> captured = new AtomicReference<>();
    final JiraIdentitySource source = known -> {
      captured.set(known);
      return new JiraResolution(List.of(), 0, 0, 0);
    };

    run(() -> new HarvestJiraTask(source, registry, Strictness.EMAIL_ONLY).run());

    assertEquals(Set.of("eeeeeeeeeeeeeeeeeeeeeee1"), captured.get());
  }

  @Test
  @DisplayName("harvesting preserves commit counts recorded by the reconstruction")
  void harvestDoesNotWipeAuthoredCommits() {
    // Regression. The first live harvest reset n_authored to 0 for all 7,199 git people, because
    // the task passed an empty map to canonicalisation and saveCanonical rewrites every row.
    final InMemoryRegistry registry = new InMemoryRegistry();
    registry.saveIdentities(List.of(PersonRecord.git(
        "fffffffffffffffffffffff1", new Identity("Gary Gregory", "ggregory@apache.org"))));
    registry.saveCanonical(
        Map.of("fffffffffffffffffffffff1", "fffffffffffffffffffffff1"),
        registry.readAll(),
        Map.of("fffffffffffffffffffffff1", 5_040));

    final JiraIdentitySource source = known -> new JiraResolution(
        List.of(PersonRecord.jira("fffffffffffffffffffffff2", "Someone New", "newbie")), 1, 0, 0);

    run(() -> new HarvestJiraTask(source, registry, Strictness.EMAIL_ONLY).run());

    assertEquals(5_040, registry.authored.get("fffffffffffffffffffffff1"),
        "the git reconstruction's commit count must survive a later harvest");
  }

  @Test
  @DisplayName("a Jira record never overwrites an existing git identity")
  void harvestDoesNotOverwriteGitIdentities() {
    // Regression. The first live harvest replaced 34 git identities with Jira ones, discarding
    // their email addresses -- the strongest merge key available. A source that returns an
    // already-known id must not be able to clobber it.
    final InMemoryRegistry registry = new InMemoryRegistry();
    final String known = "fffffffffffffffffffffff3";
    registry.saveIdentities(List.of(PersonRecord.git(
        known, new Identity("Stian Soiland-Reyes", "stain@apache.org"))));

    final JiraIdentitySource misbehaving = ignored -> new JiraResolution(
        List.of(PersonRecord.jira(known, "Stian Soiland-Reyes", "stain")), 1, 0, 0);

    run(() -> new HarvestJiraTask(misbehaving, registry, Strictness.EMAIL_ONLY).run());

    final PersonRecord after = registry.readAll().get(known);
    assertEquals("stain@apache.org", after.identity().email(),
        "the git address must not be replaced by a blank Jira one");
  }

  /** Tasks declare {@code throws Exception} because their adapters do; unwrap for readability. */
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
