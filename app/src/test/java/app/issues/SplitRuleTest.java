package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.SplitRule.Candidate;
import app.issues.Universe.Population;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The split's rule as a run setting: random by default, temporal for the first-commit study. */
class SplitRuleTest {


  @Test
  @DisplayName("the revised rule draws the temporal sides and marks the issues crossing each "
      + "cut, and names a rule of its own")
  void revised() {
    final List<Candidate> in = new ArrayList<>();
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    for (int k = 0; k < 17; k++) {
      final Instant start = t.plusSeconds(1000L * (17 - k));
      in.add(new Candidate("i" + k, k % 3 == 0 ? "a" : "b", start,
          start.plusSeconds(k % 2 == 0 ? 1 : 5000)));
    }
    final ProjectSplit.Parts parts = SplitRule.REVISED.draw(in);
    final ProjectSplit.Parts temporal = TemporalSplit.draw(in);
    assertEquals(temporal.outer(), parts.outer());
    assertEquals(temporal.inner(), parts.inner());
    assertEquals(TemporalSplit.drawRevised(in), parts);
    assertFalse(parts.outerCrossing().isEmpty());
    assertFalse(parts.innerCrossing().isEmpty());
    assertTrue(SplitRule.REVISED.rule().startsWith("temporal, revised: "),
        SplitRule.REVISED.rule());
  }


  @Test
  @DisplayName("a candidate carries its first resolution; given three fields it has none")
  void candidate() {
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    assertNull(new Candidate("i", "p", t).firstResolution());
    assertEquals(t.plusSeconds(5), new Candidate("i", "p", t, t.plusSeconds(5)).firstResolution());
  }


  @Test
  @DisplayName("the runs are recorded in the revised design's collections, as they always were")
  void collections() {
    assertEquals("_revised", SplitRule.REVISED.collectionSuffix());
  }

  @Test
  @DisplayName("a recorded run is the revised design's when its split_design names it, or, "
      + "without one, when its split_rule gives the revised rule's words")
  void recordedBy() {
    assertEquals("split_design", SplitRule.DESIGN_FIELD);
    assertTrue(SplitRule.recordedBy(Map.of("split_design", "revised")));
    assertTrue(SplitRule.recordedBy(Map.of("split_rule", SplitRule.REVISED.rule())));
    assertFalse(SplitRule.recordedBy(Map.of("stage", "x")));
    assertFalse(SplitRule.recordedBy(Map.of("split_design", "temporal")));
    assertFalse(SplitRule.recordedBy(Map.of("split_design", "random")));
  }
}
