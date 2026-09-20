package app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The merge rules are the part of this framework most likely to be wrong, and a wrong merge is not
 * recoverable downstream — it fabricates a developer and pools several people's work into them.
 *
 * <p>These tests run in milliseconds with no SparkSession and no MongoDB, which is the point of
 * keeping the rules as pure functions. Every case below is drawn from the actual SmartSHARK 2.2
 * corpus rather than invented.
 */
class CanonicalResolverTest {

  private static PersonRecord git(final String id, final String name, final String email) {
    return new PersonRecord(id, new Identity(name, email), null, Source.GIT);
  }

  private static PersonRecord jira(final String id, final String displayName, final String username) {
    return new PersonRecord(id, new Identity(displayName, ""), username, Source.JIRA);
  }

  private static CanonicalResolver resolverOf(
      final Strictness strictness, final PersonRecord... records) {
    final CanonicalResolver resolver = new CanonicalResolver(strictness);
    for (final PersonRecord record : records) {
      resolver.add(record);
    }
    return resolver;
  }

  private static boolean samePerson(
      final Map<String, String> canonical, final String left, final String right) {
    return canonical.get(left).equals(canonical.get(right));
  }

  @Nested
  @DisplayName("merges that must happen")
  class Merges {

    @Test
    @DisplayName("name variants on one real address are one person")
    void mergesNameVariantsSharingAnEmail() {
      // Gary Gregory, ggregory@apache.org -- three spellings, 5,040 commits.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_ONLY,
          git("a1", "Gary D. Gregory", "ggregory@apache.org"),
          git("a2", "Gary Gregory", "ggregory@apache.org"),
          git("a3", "ggregory", "ggregory@apache.org"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(1, resolver.canonicalCount());
      assertTrue(samePerson(canonical, "a1", "a3"));
    }

    @Test
    @DisplayName("one name across employers is one person")
    void mergesAcrossEmployersByFullName() {
      // Jordan Zimmerman, as he moves between companies.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("b1", "Jordan Zimmerman", "jordan@jordanzimmerman.com"),
          git("b2", "Jordan Zimmerman", "jzimmerman@netflix.com"),
          git("b3", "Jordan Zimmerman", "jordan.zimmerman@riotgames.com"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(1, resolver.canonicalCount());
      assertTrue(samePerson(canonical, "b1", "b3"));
    }

    @Test
    @DisplayName("accented and unaccented spellings normalise together")
    void mergesAccentVariants() {
      // Sergio Fernández appears both ways in the corpus. NFKD strips the combining acute, so both
      // normalise to "sergio fernandez".
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("c1", "Sergio Fernández", "sergio@wikier.org"),
          git("c2", "Sergio Fernandez", "sefernan@amazon.com"));
      final Map<String, String> canonical = resolver.resolve();

      assertTrue(samePerson(canonical, "c1", "c2"));
    }

    @Test
    @DisplayName("umlaut transliteration is NOT handled -- documented limitation")
    void doesNotTransliterateUmlauts() {
      // "Jörn" normalises to "jorn", not "joern": stripping a combining diaeresis is not the same
      // operation as German oe-transliteration. In the corpus this costs nothing, because Jörn and
      // Joern Kottmann share joern@apache.org and merge on the address instead. Recorded so the
      // behaviour is a known choice rather than a surprise if a future case does not.
      final CanonicalResolver byName = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("c3", "Jörn Kottmann", "joern@apache.org"),
          git("c4", "Joern Kottmann", "elsewhere@example.org"));
      byName.resolve();

      assertEquals(2, byName.canonicalCount());

      final CanonicalResolver byEmail = resolverOf(Strictness.EMAIL_ONLY,
          git("c5", "Jörn Kottmann", "joern@apache.org"),
          git("c6", "Joern Kottmann", "joern@apache.org"));
      byEmail.resolve();

      assertEquals(1, byEmail.canonicalCount());
    }

    @Test
    @DisplayName("a Jira username links to the matching git address local part")
    void linksJiraByUsername() {
      // The verified case: assignee `stain` is Stian Soiland-Reyes <stain@apache.org>.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_ONLY,
          git("g1", "Stian Soiland-Reyes", "stain@apache.org"),
          jira("j1", "Someone Else Entirely", "stain"));
      final Map<String, String> canonical = resolver.resolve();

      assertTrue(samePerson(canonical, "g1", "j1"));
    }

    @Test
    @DisplayName("a Jira display name links to the matching git author name")
    void linksJiraByDisplayName() {
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_ONLY,
          git("g2", "Stian Soiland-Reyes", "soiland-reyes@cs.manchester.ac.uk"),
          jira("j2", "Stian Soiland-Reyes", "unrelated-handle"));
      final Map<String, String> canonical = resolver.resolve();

      assertTrue(samePerson(canonical, "g2", "j2"));
    }
  }

  @Nested
  @DisplayName("merges that must NOT happen")
  class NonMerges {

    @Test
    @DisplayName("an ambiguous Jira handle does not bridge to anyone")
    void refusesBridgeOnAmbiguousHandle() {
      // The fabricated developer this guard exists for. The Jira username `jason` matched the local
      // part of four unrelated addresses and chained Jason van Zyl, Jason Gustafson, Jason Jackson
      // and Jason Dillon into one canonical person holding 4,659 commits across Maven, Kafka and
      // Storm. A handle that names more than one person is not an identifier.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("v1", "Jason van Zyl", "jason@tesla.io"),
          git("v2", "Jason Gustafson", "jason@confluent.io"),
          jira("v3", "Jason van Zyl", "jason"));
      final Map<String, String> canonical = resolver.resolve();

      assertFalse(samePerson(canonical, "v1", "v2"), "two people fused through one handle");
      assertEquals(1, resolver.refusedBridges().size());
      assertEquals("jason", resolver.refusedBridges().get(0).handle());
      assertEquals(2, resolver.refusedBridges().get(0).names().size(), "the evidence is recorded");
    }

    @Test
    @DisplayName("an unambiguous handle still bridges, and the display name still reaches its owner")
    void keepsUnambiguousBridge() {
      // The guard must not cost the legitimate case: `randgalt` is Jordan Zimmerman, and the Jira
      // record is what connects the opaque handle to the identity carrying his full name.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("w1", "Jordan Zimmerman", "randgalt@apache.org"),
          git("w2", "Jordan Zimmerman", "jordan@jordanzimmerman.com"),
          jira("w3", "Jordan Zimmerman", "randgalt"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(1, resolver.canonicalCount());
      assertTrue(samePerson(canonical, "w1", "w3"));
      assertTrue(resolver.refusedBridges().isEmpty());
    }

    @Test
    @DisplayName("placeholder names do not fuse every anonymous commit into one person")
    void doesNotMergeOnPlaceholderName() {
      // "No Author" and "(no author)" both normalise to two tokens, so without an exclusion they
      // satisfy the full-name rule. Measured before the exclusion existed: 484 commits under one
      // fabricated identity.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("p1", "No Author", "no_author@no_author"),
          git("p2", "(no author)", "(no author)@unknown"),
          git("p3", "No Author", "nobody@apache.org"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(3, resolver.canonicalCount());
      assertFalse(samePerson(canonical, "p1", "p3"), "a placeholder address is not a mailbox");
    }

    @Test
    @DisplayName("a shared email local part does not fuse different people")
    void doesNotMergeOnEmailLocalPart() {
      // The regression this class exists for. Cascading on local part chained Stefan Seelmann,
      // Michael Stevens, Dennis Kieselhorst and Sriharsha Chintalapani into one developer, because
      // mail@stefan-seelmann.de and mail@michael-stevens.de share the local part "mail".
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("d1", "Stefan Seelmann", "mail@stefan-seelmann.de"),
          git("d2", "Michael Stevens", "mail@michael-stevens.de"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(2, resolver.canonicalCount());
      assertFalse(samePerson(canonical, "d1", "d2"));
    }

    @Test
    @DisplayName("a shared first name does not fuse different people")
    void doesNotMergeOnFirstNameAlone() {
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("e1", "Michael Allman", "michael@videoamp.com"),
          git("e2", "Michael Schonfeld", "michael@schonfeld.org"),
          git("e3", "Michael G. Noll", "michael@confluent.io"));
      resolver.resolve();

      assertEquals(3, resolver.canonicalCount());
    }

    @Test
    @DisplayName("a placeholder address does not fuse everyone who used it")
    void doesNotMergeOnPlaceholderEmail() {
      // dev-null@apache.org carries 17 distinct names in the corpus.
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_ONLY,
          git("f1", "mm", "dev-null@apache.org"),
          git("f2", "mb", "dev-null@apache.org"),
          git("f3", "No Author", "unknown@apache.org"));
      resolver.resolve();

      assertEquals(3, resolver.canonicalCount());
    }

    @Test
    @DisplayName("single-token handles do not merge on name")
    void doesNotMergeSingleTokenNames() {
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
          git("h1", "builds", "builds@one.example"),
          git("h2", "builds", "builds@two.example"));
      resolver.resolve();

      assertEquals(2, resolver.canonicalCount());
    }

    @Test
    @DisplayName("EMAIL_ONLY leaves cross-address aliases split")
    void emailOnlyIsStricter() {
      final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_ONLY,
          git("i1", "Andy Seaborne", "andy@apache.org"),
          git("i2", "Andy Seaborne", "andy@seaborne.org"));
      final Map<String, String> canonical = resolver.resolve();

      assertEquals(2, resolver.canonicalCount());
      assertNotEquals(canonical.get("i1"), canonical.get("i2"));
    }
  }

  @Nested
  @DisplayName("bot detection")
  class Bots {

    @Test
    @DisplayName("recognises real bots")
    void flagsBots() {
      assertTrue(new Identity("GitHub", "noreply@github.com").isBot());
      assertTrue(new Identity(
          "dependabot[bot]", "49699333+dependabot[bot]@users.noreply.github.com").isBot());
      assertTrue(new Identity("Renovate Bot", "bot@renovateapp.com").isBot());
      assertTrue(new Identity("jenkins", "builds@apache.org").isBot());
    }

    @Test
    @DisplayName("does not flag humans whose surname collides with a bot keyword")
    void doesNotFlagHumansWithBotLikeNames() {
      // All of these are real developers in the corpus. Flagging one deletes a human from H_j.
      assertFalse(new Identity("Gus Jenkins", "jenkins.gus6@gmail.com").isBot());
      assertFalse(new Identity("John Jenkins", "jjenkins@kcg.com").isBot());
      assertFalse(new Identity("Joshua Hudson", "jhudson@cedaron.com").isBot());
      assertFalse(new Identity("Vincent Botta", "vince.bot@gmail.com").isBot());
      // github.com noreply addresses are also used by humans.
      assertFalse(new Identity("Petri Tuomola", "ptuomola@users.noreply.github.com").isBot());
    }
  }

  @Test
  @DisplayName("the canonical id is a real member id, so it joins back to the corpus")
  void canonicalIdIsOneOfTheMembers() {
    final CanonicalResolver resolver = resolverOf(Strictness.EMAIL_AND_FULL_NAME,
        git("k1", "Olivier Lamy", "olamy@apache.org"),
        git("k2", "olivier lamy", "olamy@apache.org"));
    final Map<String, String> canonical = resolver.resolve();

    final String root = canonical.get("k1");
    assertTrue("k1".equals(root) || "k2".equals(root));
    assertEquals(root, canonical.get("k2"));
  }
}
