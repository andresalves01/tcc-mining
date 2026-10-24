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

}
