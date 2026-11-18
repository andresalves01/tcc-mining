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
  @DisplayName("SPLIT_RULE: unset or blank is random, the default; random or temporal by name; "
      + "anything else refused")
  void setting() {
    assertEquals("SPLIT_RULE", SplitRule.SETTING);
    assertEquals(SplitRule.RANDOM, SplitRule.of(null));
    assertEquals(SplitRule.RANDOM, SplitRule.of(" "));
    assertEquals(SplitRule.RANDOM, SplitRule.of("random"));
    assertEquals(SplitRule.TEMPORAL, SplitRule.of("temporal"));
    assertEquals(SplitRule.TEMPORAL, SplitRule.of("TEMPORAL"));
    assertEquals(SplitRule.REVISED, SplitRule.of("revised"));
    final IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> SplitRule.of("weekly"));
    assertEquals("SPLIT_RULE is random, temporal or revised, not 'weekly'", e.getMessage());
  }

  @Test
  @DisplayName("the revised rule draws the temporal one's sides and marks the issues crossing "
      + "each cut, and names a rule of its own, never the "
      + "temporal rule's words")
  void revised() {
    final List<Candidate> in = new ArrayList<>();
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    for (int k = 0; k < 17; k++) {
      final Instant start = t.plusSeconds(1000L * (17 - k));
      in.add(new Candidate("i" + k, k % 3 == 0 ? "a" : "b", start,
          start.plusSeconds(k % 2 == 0 ? 1 : 5000)));
    }
    final ProjectSplit.Parts parts = SplitRule.REVISED.draw(in);
    final ProjectSplit.Parts temporal = SplitRule.TEMPORAL.draw(in);
    assertEquals(temporal.outer(), parts.outer());
    assertEquals(temporal.inner(), parts.inner());
    assertEquals(TemporalSplit.drawRevised(in), parts);
    assertFalse(parts.outerCrossing().isEmpty());
    assertFalse(parts.innerCrossing().isEmpty());
    assertTrue(temporal.outerCrossing().isEmpty(), "the temporal rule marks no crossing issue");
    assertTrue(SplitRule.REVISED.rule().startsWith("temporal, revised: "),
        SplitRule.REVISED.rule());
    assertTrue(SplitRule.REVISED.rule() != null
        && !SplitRule.REVISED.rule().equals(TemporalSplit.RULE)
        && !SplitRule.REVISED.rule().equals(ProjectSplit.RULE));
  }

  @Test
  @DisplayName("what each rule is: the temporal and the revised draw by the first commit, and "
      + "only the revised reads point-in-time histories and leaves crossing issues out")
  void predicates() {
    assertEquals(List.of(false, true, true),
        List.of(SplitRule.values()).stream().map(SplitRule::temporal).toList());
    assertEquals(List.of(false, false, true),
        List.of(SplitRule.values()).stream().map(SplitRule::pointInTime).toList());
    assertEquals(List.of(false, false, true),
        List.of(SplitRule.values()).stream().map(SplitRule::crossingOut).toList());
  }

  @Test
  @DisplayName("a candidate carries its first resolution; given three fields it has none")
  void candidate() {
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    assertNull(new Candidate("i", "p", t).firstResolution());
    assertEquals(t.plusSeconds(5), new Candidate("i", "p", t, t.plusSeconds(5)).firstResolution());
  }

  @Test
  @DisplayName("the In Progress study's split is the random one: a temporal rule is refused for "
      + "its matrix")
  void declared() {
    assertEquals(SplitRule.TEMPORAL, SplitRule.declared("temporal", Population.FIRST_COMMIT));
    assertEquals(SplitRule.RANDOM, SplitRule.declared(null, Population.IN_PROGRESS));
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> SplitRule.declared("temporal", Population.IN_PROGRESS));
    assertTrue(e.getMessage().contains("SPLIT_RULE"), e.getMessage());
    assertEquals(SplitRule.REVISED, SplitRule.declared("revised", Population.FIRST_COMMIT));
    assertThrows(IllegalArgumentException.class,
        () -> SplitRule.declared("revised", Population.IN_PROGRESS));
  }

  @Test
  @DisplayName("the random rule draws exactly as ProjectSplit, whatever the instants")
  void random() {
    final List<Candidate> in = new ArrayList<>();
    final Map<String, String> projects = new HashMap<>();
    final Instant t = Instant.parse("2016-01-01T00:00:00Z");
    for (int k = 0; k < 17; k++) {
      final String p = k % 3 == 0 ? "a" : "b";
      in.add(new Candidate("i" + k, p, t.plusSeconds(1000L * (17 - k))));
      projects.put("i" + k, p);
    }
    assertEquals(ProjectSplit.draw(projects), SplitRule.RANDOM.draw(in));
    assertEquals(TemporalSplit.draw(in), SplitRule.TEMPORAL.draw(in));
    assertEquals(ProjectSplit.RULE, SplitRule.RANDOM.rule());
    assertEquals(TemporalSplit.RULE, SplitRule.TEMPORAL.rule());
  }

  @Test
  @DisplayName("the runs of each design are recorded in collections of their own: the random "
      + "design's keep their names, the temporal design's take a suffix")
  void collections() {
    assertEquals("", SplitRule.RANDOM.collectionSuffix());
    assertEquals("_temporal", SplitRule.TEMPORAL.collectionSuffix());
    assertEquals("_revised", SplitRule.REVISED.collectionSuffix());
  }

  @Test
  @DisplayName("the design of a recorded run: its split_design, or else the temporal rule when its "
      + "split_rule is the temporal rule's words, or else random, as every run recorded before")
  void recordedBy() {
    assertEquals("split_design", SplitRule.DESIGN_FIELD);
    assertEquals(SplitRule.RANDOM, SplitRule.recordedBy(Map.of("stage", "x")));
    assertEquals(SplitRule.RANDOM, SplitRule.recordedBy(Map.of("split_rule", ProjectSplit.RULE)));
    assertEquals(SplitRule.TEMPORAL,
        SplitRule.recordedBy(Map.of("split_rule", TemporalSplit.RULE)));
    assertEquals(SplitRule.TEMPORAL, SplitRule.recordedBy(Map.of("split_design", "temporal")));
    assertEquals(SplitRule.RANDOM, SplitRule.recordedBy(Map.of("split_design", "random",
        "split_rule", ProjectSplit.RULE)));
    assertEquals(SplitRule.REVISED, SplitRule.recordedBy(Map.of("split_design", "revised")));
    assertEquals(SplitRule.REVISED,
        SplitRule.recordedBy(Map.of("split_rule", SplitRule.REVISED.rule())));
    assertThrows(IllegalArgumentException.class,
        () -> SplitRule.recordedBy(Map.of("split_design", "weekly")));
  }
}
