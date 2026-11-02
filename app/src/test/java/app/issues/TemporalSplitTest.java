package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.SplitRule.Candidate;
import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The first-commit study's temporal split: the cut at position ceil(0.7 n) of the candidates. */
class TemporalSplitTest {

  private static final Instant DAY0 = Instant.parse("2016-01-01T12:00:00Z");

  private static Instant day(final int d) {
    return DAY0.plusSeconds(86_400L * d);
  }

  private static Candidate c(final String id, final String project, final int d) {
    return new Candidate(id, project, day(d));
  }

  /** {@code n} candidates of one project, one a day from day 1, ids i01, i02, ... */
  private static List<Candidate> daily(final int n) {
    final List<Candidate> out = new ArrayList<>();
    for (int k = 1; k <= n; k++) {
      out.add(c(String.format("i%02d", k), "p", k));
    }
    return out;
  }

  private static int training(final ProjectSplit.Parts parts) {
    return (int) parts.outer().values().stream().filter(p -> p == Part.TRAIN).count();
  }

  @Test
  @DisplayName("the cut is the instant at position ceil(0.7 n), from 1; training is every "
      + "instant at or before it")
  void cut() {
    final ProjectSplit.Parts ten = TemporalSplit.draw(daily(10));
    assertEquals(7, training(ten), "ceil(7.0) = 7");
    for (int k = 1; k <= 10; k++) {
      assertEquals(k <= 7 ? Part.TRAIN : Part.TEST, ten.outer().get(String.format("i%02d", k)));
    }
    assertEquals(day(7), TemporalSplit.cuts(daily(10)).outer());
    assertEquals(9, training(TemporalSplit.draw(daily(12))), "ceil(8.4) = 9, not round(8.4)");
    assertEquals(21, training(TemporalSplit.draw(daily(30))), "ceil(21) = 21, no float's error");
    assertEquals(3, training(TemporalSplit.draw(daily(3))), "ceil(2.1) = 3: all training");
    assertEquals(1, training(TemporalSplit.draw(daily(1))));
  }

  @Test
  @DisplayName("every candidate tied at the cut goes to training, so training may pass 70%")
  void ties() {
    final List<Candidate> in = List.of(c("a", "p", 1), c("b", "p", 2), c("c", "p", 3),
        c("d", "p", 4), c("e", "p", 5), c("f", "p", 6), c("g", "p", 7), c("h", "p", 7),
        c("i", "p", 7), c("j", "p", 8));
    final ProjectSplit.Parts parts = TemporalSplit.draw(in);
    assertEquals(9, training(parts));
    assertEquals(Part.TRAIN, parts.outer().get("i"));
    assertEquals(Part.TEST, parts.outer().get("j"));
    assertEquals(day(7), TemporalSplit.cuts(in).outer());
  }

  @Test
  @DisplayName("ordered by instant with the id breaking ties: the sides do not depend on the "
      + "order the candidates are given in")
  void order() {
    final List<Candidate> in = new ArrayList<>(List.of(c("b", "p", 3), c("a", "p", 3),
        c("z", "q", 1), c("y", "q", 9), c("c", "q", 3), c("x", "p", 2), c("w", "p", 5),
        c("v", "q", 5), c("u", "q", 3), c("t", "p", 8)));
    final ProjectSplit.Parts parts = TemporalSplit.draw(in);
    for (int seed = 0; seed < 5; seed++) {
      final List<Candidate> shuffled = new ArrayList<>(in);
      Collections.shuffle(shuffled, new Random(seed));
      assertEquals(parts, TemporalSplit.draw(shuffled));
    }
    // positions: z1 x2 a3 b3 c3 u3 w5 v5 t8 y9; ceil(7) = 7 -> v5, and w5 ties with it
    assertEquals(day(5), TemporalSplit.cuts(in).outer());
    assertEquals(8, training(parts));
    assertEquals(Part.TEST, parts.outer().get("t"));
  }

  @Test
  @DisplayName("the inner split is the same rule on the training side alone")
  void inner() {
    final ProjectSplit.Parts parts = TemporalSplit.draw(daily(10));
    // training i01..i07; ceil(4.9) = 5 -> inner fitting i01..i05, inner validation i06, i07
    final Map<String, Part> expected = new HashMap<>();
    for (int k = 1; k <= 7; k++) {
      expected.put(String.format("i%02d", k), k <= 5 ? Part.TRAIN : Part.TEST);
    }
    assertEquals(expected, parts.inner());
    assertEquals(new TemporalSplit.Cuts(day(7), day(5)), TemporalSplit.cuts(daily(10)));
  }

  @Test
  @DisplayName("no candidate is left out: every one on an outer side, every training one on an "
      + "inner side, no stratification by project")
  void noneLeftOut() {
    final List<Candidate> in = new ArrayList<>();
    for (int k = 0; k < 20; k++) {
      in.add(c("a" + k, "early", k));
      in.add(c("b" + k, "late", 100 + k));
    }
    final ProjectSplit.Parts parts = TemporalSplit.draw(in);
    assertEquals(40, parts.outer().size());
    for (final Candidate x : in) {
      assertEquals(parts.outer().get(x.id()) == Part.TRAIN, parts.inner().containsKey(x.id()));
    }
    // ceil(28) = 28: all of early and 8 of late; no project is forced onto both sides
    assertTrue(in.stream().filter(x -> x.project().equals("early"))
        .allMatch(x -> parts.outer().get(x.id()) == Part.TRAIN));
    assertEquals(28, training(parts));
  }

  @Test
  @DisplayName("refuses a candidate without an instant and a repeated id; no candidate, no side")
  void refuses() {
    assertThrows(IllegalArgumentException.class,
        () -> TemporalSplit.draw(List.of(new Candidate("a", "p", null))));
    assertThrows(IllegalArgumentException.class,
        () -> TemporalSplit.draw(List.of(c("a", "p", 1), c("a", "q", 2))));
    assertEquals(new ProjectSplit.Parts(Map.of(), Map.of()), TemporalSplit.draw(List.of()));
  }

  @Test
  @DisplayName("a project with test issues and no training issue is new; one on both sides or "
      + "on training alone is not")
  void newProjects() {
    final List<Candidate> in = List.of(c("a1", "old", 1), c("a2", "old", 2), c("a3", "old", 3),
        c("b1", "both", 4), c("b2", "both", 5), c("b3", "both", 6), c("b4", "both", 7),
        c("b5", "both", 9), c("n1", "fresh", 8), c("n2", "fresh", 10));
    final Map<String, String> projects = new HashMap<>();
    in.forEach(x -> projects.put(x.id(), x.project()));
    final ProjectSplit.Parts parts = TemporalSplit.draw(in);
    // ceil(7) = 7 -> b4 at day 7; test: n1 (8), b5 (9), n2 (10)
    assertEquals(Set.of("fresh"), TemporalSplit.newProjects(projects, parts.outer()));
    // inner: training a1..b4, ceil(4.9) = 5 -> b2 at day 5; validation b3, b4: both seen
    assertEquals(Set.of(), TemporalSplit.newProjects(projects, parts.inner()));
  }

  // ------------------------------------------------------------------ the check of a matrix

  private static RowCache.Entry entry(final Candidate x, final String outer, final String inner) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.FIRST_COMMIT, new String[] {outer, inner});
    final Instant created = x.firstCommit() == null ? DAY0 : x.firstCommit().minusSeconds(3600);
    return new RowCache.Entry(new IssueUnit(x.id(), "K-" + x.id(), x.project(), "r", "d",
        created, created.plusSeconds(86_400 * 30), "Fixed", false, x.firstCommit()),
        new Row(x.id(), Map.of(), Set.of()), parts);
  }

  private static List<RowCache.Entry> rows(final List<Candidate> in, final ProjectSplit.Parts p) {
    final List<RowCache.Entry> out = new ArrayList<>();
    for (final Candidate x : in) {
      final Part inner = p.inner().get(x.id());
      out.add(entry(x, p.outer().get(x.id()).name(), inner == null ? "" : inner.name()));
    }
    return out;
  }

  /** Three projects of 8, 6 and 6 issues whose instants interleave. */
  private static List<Candidate> corpus() {
    final List<Candidate> out = new ArrayList<>();
    for (int k = 0; k < 8; k++) {
      out.add(c("a" + k, "alpha", 3 * k));
    }
    for (int k = 0; k < 6; k++) {
      out.add(c("b" + k, "beta", 3 * k + 1));
      out.add(c("g" + k, "gamma", 3 * k + 2));
    }
    return out;
  }

  @Test
  @DisplayName("the check passes a matrix at the temporal rule's sides and refuses one at the "
      + "random rule's, and the random check the other way round")
  void check() {
    final List<Candidate> in = corpus();
    final Map<String, String> projects = new HashMap<>();
    in.forEach(x -> projects.put(x.id(), x.project()));
    final List<RowCache.Entry> temporal = rows(in, TemporalSplit.draw(in));
    final List<RowCache.Entry> random = rows(in, ProjectSplit.draw(projects));
    final Population fc = Population.FIRST_COMMIT;

    assertEquals(List.of(), TemporalSplit.differences(temporal, fc));
    assertFalse(TemporalSplit.differences(random, fc).isEmpty());
    assertEquals(List.of(), ProjectSplit.differences(random, fc));
    assertFalse(ProjectSplit.differences(temporal, fc).isEmpty());
    assertEquals(List.of(), SplitRule.TEMPORAL.differences(temporal, fc));
    assertEquals(List.of(), SplitRule.RANDOM.differences(random, fc));
  }

  @Test
  @DisplayName("the check refuses a moved side and a row without its first-commit instant")
  void checkRefuses() {
    final List<Candidate> in = corpus();
    final List<RowCache.Entry> rows = rows(in, TemporalSplit.draw(in));
    final Population fc = Population.FIRST_COMMIT;

    final List<RowCache.Entry> moved = new ArrayList<>(rows);
    final Candidate last = in.get(in.size() - 1);
    moved.set(in.size() - 1, entry(last, "TRAIN", "TEST"));
    assertFalse(TemporalSplit.differences(moved, fc).isEmpty());

    final List<RowCache.Entry> undated = new ArrayList<>(rows);
    final RowCache.Entry first = undated.get(0);
    undated.set(0, entry(new Candidate(first.unit().issueId(), first.unit().project(), null),
        first.outer(fc), first.inner(fc)));
    final List<String> d = TemporalSplit.differences(undated, fc);
    assertEquals(1, d.size());
    assertTrue(d.get(0).contains("first-commit instant"), d.get(0));
  }
}
