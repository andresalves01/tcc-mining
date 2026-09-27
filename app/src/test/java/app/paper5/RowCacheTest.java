package app.paper5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working cache: what goes in comes back, including the difference between zero and absent.
 *
 * <p>This test exists because the first version of the cache wrote {@link Extractor#PRODUCED} --
 * the constructs -- and quietly left out the absence indicators beside them. They came back as
 * missing, the matrix filled them with zero, every one of them was then dropped for being constant,
 * and a whole grid of results was fitted with the absence information thrown away. Nothing failed;
 * the numbers were simply wrong. A round trip is cheap and would have caught it.
 */
class RowCacheTest {

  private static final Instant T0 = Instant.parse("2018-06-15T12:00:00Z");

  private static RowCache.Entry entry(final String id, final Map<String, Double> values,
      final Set<String> absent, final String outer, final String inner) {

    return new RowCache.Entry(
        new Unit(id, "p", "ana", T0, T0.plusSeconds(7200)),
        new Row(id, values, absent), outer, inner);
  }

  @Test
  @DisplayName("a value, a valid zero and an absence all survive the round trip as themselves")
  void theRoundTripKeepsZeroApartFromAbsent(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("matriz.tsv");
    final List<RowCache.Entry> written = List.of(
        entry("a", Map.of("owner_prior_prs", 7.0, "owner_merged_ratio", 0.0,
            "owner_merged_ratio_ausente", 0.0), Set.of(), "TRAIN", "TRAIN"),
        entry("b", Map.of("owner_prior_prs", 0.0, "owner_merged_ratio_ausente", 1.0),
            Set.of("owner_merged_ratio"), "TRAIN", "TEST"));

    RowCache.write(file, written, List.of(
        "owner_prior_prs", "owner_merged_ratio", "owner_merged_ratio_ausente"));
    final List<RowCache.Entry> read = RowCache.read(file);

    assertEquals(2, read.size());
    final Row first = read.get(0).row();
    assertEquals(7.0, first.values().get("owner_prior_prs"));
    assertEquals(0.0, first.values().get("owner_merged_ratio"), "a real zero stays a real zero");
    assertFalse(first.isAbsent("owner_merged_ratio"));

    final Row second = read.get(1).row();
    assertTrue(second.isAbsent("owner_merged_ratio"), "and an absence stays an absence");
    assertEquals(0.0, second.values().get("owner_prior_prs"),
        "while a genuine zero on the same row is untouched");
  }

  @Test
  @DisplayName("the indicator beside an absent value is stored, not inferred on the way back")
  void theIndicatorSurvives(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("matriz.tsv");
    RowCache.write(file, List.of(
        entry("a", Map.of("owner_merged_ratio_ausente", 1.0), Set.of("owner_merged_ratio"),
            "TRAIN", "TRAIN")),
        Extractor.STORED);

    final Row row = RowCache.read(file).get(0).row();

    assertEquals(1.0, row.values().get("owner_merged_ratio_ausente"),
        "the indicator is a value the matrix reads, and losing it silently zeroes a whole column");
    assertTrue(row.isAbsent("owner_merged_ratio"));
  }

  @Test
  @DisplayName("what is stored is every construct plus exactly the three declared indicators")
  void storedCoversTheIndicators() {
    assertEquals(Extractor.PRODUCED.size() + 3, Extractor.STORED.size(),
        "only the indicators of constructs the extractor actually produces -- the three inherited "
            + "history columns are computed at fit time and must not appear here");
    for (final String indicator : List.of("owner_merged_ratio_ausente",
        "social_connection_90d_ausente", "area_hotness_90d_ausente")) {

      assertTrue(Extractor.STORED.contains(indicator), indicator + " must be persisted");
      assertFalse(Extractor.PRODUCED.contains(indicator),
          indicator + " is not a construct and must not appear in a coverage table");
    }
  }

  @Test
  @DisplayName("both partitions come back, so the fit can refuse the reserved side by name")
  void bothPartitionsSurvive(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("matriz.tsv");
    RowCache.write(file, List.of(
        entry("a", Map.of(), Set.of(), "TRAIN", "TEST"),
        entry("b", Map.of(), Set.of(), "TEST", "")),
        List.of());

    final List<RowCache.Entry> read = RowCache.read(file);

    assertEquals("TRAIN", read.get(0).outer());
    assertEquals("TEST", read.get(0).inner());
    assertEquals("TEST", read.get(1).outer());
    assertEquals("", read.get(1).inner(), "a reserved row never reached the inner cut");
  }
}
