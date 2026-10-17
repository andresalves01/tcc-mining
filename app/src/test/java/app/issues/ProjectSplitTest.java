package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Universe.Part;
import app.issues.Universe.Population;
import app.study.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The in-progress study's random split, stratified by project (ticket 21, item 44). */
class ProjectSplitTest {

  /** Projects of 1, 2, 3, 5, 10 and 23 issues, their ids given out of order. */
  private static Map<String, String> corpus() {
    final Map<String, String> out = new LinkedHashMap<>();
    final String[] projects = {"zeta", "alpha", "mid", "beta", "one", "two"};
    final int[] sizes = {23, 10, 5, 3, 1, 2};
    for (int p = 0; p < projects.length; p++) {
      for (int k = sizes[p]; k >= 1; k--) {
        out.put(String.format("%s-%03d", projects[p], k), projects[p]);
      }
    }
    return out;
  }

  @Test
  @DisplayName("seed 1738: per project in order of name, its issues by id shuffled with one "
      + "Random shared across projects, the first round(0.7 n) training, a half up; the inner "
      + "split the same rule on the training side with the same Random continuing")
  void theRule() {
    final Map<String, String> corpus = corpus();
    final ProjectSplit.Parts parts = ProjectSplit.draw(corpus);

    // the rule spelt out once more, step by step
    final Map<String, List<String>> by = new TreeMap<>();
    corpus.forEach((id, p) -> by.computeIfAbsent(p, k -> new ArrayList<>()).add(id));
    final Random random = new Random(1738);
    final Map<String, Part> outer = new HashMap<>();
    final Map<String, List<String>> training = new TreeMap<>();
    for (final Map.Entry<String, List<String>> e : by.entrySet()) {
      final List<String> ids = new ArrayList<>(e.getValue());
      Collections.sort(ids);
      Collections.shuffle(ids, random);
      final int k = new BigDecimal("0.7").multiply(BigDecimal.valueOf(ids.size()))
          .setScale(0, RoundingMode.HALF_UP).intValue();
      for (int i = 0; i < ids.size(); i++) {
        outer.put(ids.get(i), i < k ? Part.TRAIN : Part.TEST);
      }
      training.put(e.getKey(), ids.subList(0, k));
    }
    final Map<String, Part> inner = new HashMap<>();
    for (final List<String> side : training.values()) {
      final List<String> ids = new ArrayList<>(side);
      Collections.sort(ids);
      Collections.shuffle(ids, random);
      final int k = new BigDecimal("0.7").multiply(BigDecimal.valueOf(ids.size()))
          .setScale(0, RoundingMode.HALF_UP).intValue();
      for (int i = 0; i < ids.size(); i++) {
        inner.put(ids.get(i), i < k ? Part.TRAIN : Part.TEST);
      }
    }
    assertEquals(outer, parts.outer());
    assertEquals(inner, parts.inner());
    assertEquals(1738L, ProjectSplit.SEED);
  }

  @Test
  @DisplayName("reproducible, whatever the order the issues are given in")
  void reproducible() {
    final Map<String, String> corpus = corpus();
    final List<String> ids = new ArrayList<>(corpus.keySet());
    Collections.shuffle(ids, new Random(7));
    final Map<String, String> reordered = new LinkedHashMap<>();
    ids.forEach(id -> reordered.put(id, corpus.get(id)));
    assertEquals(ProjectSplit.draw(corpus), ProjectSplit.draw(reordered));
    assertEquals(ProjectSplit.draw(corpus), ProjectSplit.draw(corpus));
  }

  @Test
  @DisplayName("stratified: every project of two or more issues on both sides, within one issue "
      + "of 70%; a project of one issue wholly training; no issue crosses")
  void stratified() {
    final Map<String, String> corpus = corpus();
    final ProjectSplit.Parts parts = ProjectSplit.draw(corpus);
    final Map<String, int[]> count = new TreeMap<>();
    corpus.forEach((id, p) -> {
      final int[] c = count.computeIfAbsent(p, k -> new int[2]);
      c[parts.outer().get(id) == Part.TRAIN ? 0 : 1]++;
    });
    count.forEach((p, c) -> {
      final int n = c[0] + c[1];
      if (n >= 2) {
        assertTrue(c[0] >= 1 && c[1] >= 1, p + " " + c[0] + "/" + c[1]);
      }
      assertTrue(Math.abs(c[0] - 0.7 * n) <= 1, p + " " + c[0] + " of " + n);
    });
    assertEquals(4, count.get("mid")[0], "five issues: 3.5 rounded up, never by a float's error");
    assertEquals(Part.TRAIN, parts.outer().get("one-001"));
    assertEquals(Part.TRAIN, parts.inner().get("one-001"), "and wholly inner fitting");
    assertEquals(corpus.keySet(), parts.outer().keySet());
    assertFalse(parts.outer().containsValue(Part.CROSSES));
    assertFalse(parts.inner().containsValue(Part.CROSSES));
    // the inner verdicts are exactly the outer training's
    for (final String id : corpus.keySet()) {
      assertEquals(parts.outer().get(id) == Part.TRAIN, parts.inner().containsKey(id), id);
    }
  }

  private static RowCache.Entry entry(final String id, final String project, final String outer,
      final String inner) {
    final Map<Population, String[]> parts = new EnumMap<>(Population.class);
    parts.put(Population.IN_PROGRESS, new String[] {outer, inner});
    final Instant t = Instant.parse("2016-03-01T10:00:00Z");
    return new RowCache.Entry(new IssueUnit(id, "K-" + id, project, "r", "d", t,
        t.plusSeconds(86_400 * 3), "Fixed", false, t.plusSeconds(3600)),
        new Row(id, Map.of(), Set.of()), parts);
  }

  /** The matrix rows of the corpus, each at the verdicts of the draw. */
  private static List<RowCache.Entry> drawn() {
    final Map<String, String> corpus = corpus();
    final ProjectSplit.Parts parts = ProjectSplit.draw(corpus);
    final List<RowCache.Entry> out = new ArrayList<>();
    corpus.forEach((id, p) -> {
      final Part inner = parts.inner().get(id);
      out.add(entry(id, p, parts.outer().get(id).name(), inner == null ? "" : inner.name()));
    });
    return out;
  }

  @Test
  @DisplayName("a matrix at the rule's verdicts passes; one verdict moved, a crossing row or a "
      + "missing issue does not")
  void differences() {
    final List<RowCache.Entry> rows = drawn();
    assertEquals(List.of(), ProjectSplit.differences(rows));

    final List<RowCache.Entry> moved = new ArrayList<>(rows);
    final RowCache.Entry first = moved.get(0);
    moved.set(0, entry(first.unit().issueId(), first.unit().project(),
        "TRAIN".equals(first.outer(Population.IN_PROGRESS)) ? "TEST" : "TRAIN", ""));
    assertFalse(ProjectSplit.differences(moved).isEmpty());

    final List<RowCache.Entry> crossing = new ArrayList<>(rows);
    crossing.set(0, entry(first.unit().issueId(), first.unit().project(), "CROSSES", ""));
    assertFalse(ProjectSplit.differences(crossing).isEmpty());

    final List<RowCache.Entry> missing = new ArrayList<>(rows);
    missing.removeIf(e -> e.unit().project().equals("zeta")
        && e.unit().issueId().endsWith("-001"));
    assertFalse(ProjectSplit.differences(missing).isEmpty(), "a draw over other issues");
  }
}
