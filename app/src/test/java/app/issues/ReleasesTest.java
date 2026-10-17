package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tags as releases (ticket 19): the version-tag matching and the release cycle at a start. */
class ReleasesTest {

  private static final long D = World.DAY;

  private static Corpus corpus(final List<Corpus.Tag> tags, final List<Corpus.Commit> commits) {
    return new Corpus(List.of(), List.of(), List.of(), List.of(), commits, Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), tags);
  }

  private static Corpus.Tag tag(final String name, final long at) {
    return new Corpus.Tag("p", name, at, null);
  }

  @Test
  @DisplayName("a version name and a tag name match once lower-cased, without a textual prefix, a "
      + "leading v, the incubating/ga/final/release suffixes, underscores read as dots")
  void normalisedNames() {
    assertEquals("1.9.0", Releases.normalised("release-1.9.0"));
    assertEquals("1.9.0", Releases.normalised("v1.9.0"));
    assertEquals("1.9.0", Releases.normalised("1.9.0"));
    assertEquals("2.11.2", Releases.normalised("Jena 2.11.2"));
    assertEquals("2.3.16", Releases.normalised("STRUTS_2_3_16"));
    assertEquals("1.4.0", Releases.normalised("calcite-1.4.0-incubating"));
    assertEquals("3.0", Releases.normalised("3.0-final"));
    assertEquals("0.4.0", Releases.normalised("v0.4.0-release"));
    assertEquals("1.9.0-rc1", Releases.normalised("release-1.9.0-rc1"));
    assertEquals("wishlist", Releases.normalised("WISHLIST"));
    assertNull(Releases.normalised(null));
  }

  @Test
  @DisplayName("a release is a tag whose name holds a version number of at least two parts and no "
      + "pre-release marker")
  void releaseTags() {
    assertTrue(Releases.release("release-1.9.0"));
    assertTrue(Releases.release("v10.5.1.1"));
    assertTrue(Releases.release("1.9"));
    assertFalse(Releases.release("1.9.0-rc1"));
    assertFalse(Releases.release("2.0-alpha-1"));
    assertFalse(Releases.release("2.0.0-beta3"));
    assertFalse(Releases.release("3.0M1"));
    assertFalse(Releases.release("1.0-SNAPSHOT"));
    assertFalse(Releases.release("trunk"));
    assertFalse(Releases.release("7"));
  }

  @Test
  @DisplayName("a version's release date is its matching tag's own date, else the tagged commit's; "
      + "the earliest of several; none without a match or in another project")
  void releaseDate() {
    final List<Corpus.Tag> tags = new ArrayList<>();
    tags.add(tag("release-1.0", 100 * D));
    tags.add(tag("1.0", 90 * D));
    tags.add(new Corpus.Tag("p", "v1.1", null, "c1"));
    tags.add(new Corpus.Tag("q", "2.0", 5 * D, null));
    final Corpus.Commit c1 = new Corpus.Commit("c1", "p", "h1", "a", 150 * D, 160 * D, 1,
        List.of());
    final Releases r = Releases.of(corpus(tags, List.of(c1)));
    assertEquals(90 * D, r.date("p", "1.0"));
    assertEquals(160 * D, r.date("p", "1.1"));
    assertNull(r.date("p", "2.0"));
    assertNull(r.date("p", "1.2"));
    assertNull(r.date("p", null));
  }

  @Test
  @DisplayName("release cycle: the median gap of the releases dated strictly before the start "
      + "minus the days since the last of them, signed; missing with fewer than three")
  void cycle() {
    final List<Corpus.Tag> tags = new ArrayList<>();
    tags.add(tag("1.0", 0));
    tags.add(tag("1.1", 10 * D));
    tags.add(tag("1.1.1", 40 * D));
    tags.add(tag("1.2-rc1", 45 * D));
    tags.add(tag("1.2", 80 * D));
    final Releases r = Releases.of(corpus(tags, List.of()));
    // before 40D only two releases
    assertNull(r.cycle("p", 40 * D));
    // releases 0, 10, 40: gaps 10, 30, median 20; 5 days since the last -> 15 left
    assertEquals(15.0, r.cycle("p", 45 * D), 1e-9);
    // overdue: 30 days since the last -> -10
    assertEquals(-10.0, r.cycle("p", 70 * D), 1e-9);
    // releases 0, 10, 40, 80: gaps 10, 30, 40, median 30; 2 days since -> 28
    assertEquals(28.0, r.cycle("p", 82 * D), 1e-9);
    assertNull(r.cycle("q", 52 * D));
  }

  @Test
  @DisplayName("one release per version, at its earliest tag, and one per instant: tags of several "
      + "versions on one instant are one release")
  void oneReleasePerVersionAndInstant() {
    final List<Corpus.Tag> tags = new ArrayList<>();
    tags.add(tag("1.0", 0));
    tags.add(tag("release-1.0", 5 * D));
    tags.add(tag("1.1", 10 * D));
    tags.add(tag("1.2", 10 * D));
    tags.add(tag("1.3", 30 * D));
    final Releases r = Releases.of(corpus(tags, List.of()));
    // instants 0, 10, 30: gaps 10 and 20, median 15; 10 days since -> 5
    assertEquals(5.0, r.cycle("p", 40 * D), 1e-9);
    assertEquals(Set.of(0L, 10 * D, 30 * D), Set.copyOf(r.releases("p")));
  }
}
