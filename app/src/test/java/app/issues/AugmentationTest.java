package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.issues.Augmentation.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The similarity columns after extraction, built from rows and descriptions in memory. */
class AugmentationTest {

  private static final long H = 3_600_000L;

  private final List<Augmentation.Issue> issues = new ArrayList<>();

  /** An issue started at hour {@code start} that took {@code hours}. */
  private void issue(final String id, final String outer, final String inner, final long start,
      final long hours, final String description) {
    issues.add(new Augmentation.Issue(id, "p", "r", start * H, (start + hours) * H, outer, inner,
        description));
  }

  private void fit(final String id, final long start, final long hours, final String text) {
    issue(id, "TRAIN", "TRAIN", start, hours, text);
  }

  private void validation(final String id, final long start, final String text) {
    issue(id, "TRAIN", "TEST", start, 1, text);
  }

  private static Double jaccard(final Augmentation.Result r, final String id, final int n,
      final Variant v) {
    return r.columns().get(id).get(Augmentation.jaccard(n, v));
  }

  @Test
  @DisplayName("Jaccard: equal sets at distance 0, disjoint and empty ones never candidates; "
      + "tokens are lowercase runs of letters, digits and underscore")
  void jaccard() {
    fit("equal", 0, 1, "B a");
    fit("half", 0, 2, "b c");
    fit("disjoint", 0, 3, "c d");
    fit("empty", 0, 4, "!! --");
    fit("absent", 0, 5, null);
    fit("unicode", 0, 6, "Ação foo_bar(x2)");
    validation("q", 1_000, "a b");
    validation("u", 1_000, "AÇÃO -- foo_bar! x2");
    final Augmentation.Result r = Augmentation.run(issues);
    final Augmentation.Query q = query(r, "q", Variant.INNER);
    assertEquals(List.of(new Augmentation.Neighbour("equal", 0.0),
        new Augmentation.Neighbour("half", 1 - 1.0 / 3)), q.nearest());
    assertEquals(1.5, jaccard(r, "q", 5, Variant.INNER));
    assertEquals(List.of(new Augmentation.Neighbour("unicode", 0.0)),
        query(r, "u", Variant.INNER).nearest());
    assertEquals(3, query(r, "u", Variant.INNER).tokens());
  }

  @Test
  @DisplayName("ties in distance go by id, never by duration; fewer candidates than n uses all")
  void tiesById() {
    fit("g", 0, 7, "x");
    fit("a", 0, 100, "x y");
    fit("b", 0, 2, "x y");
    fit("c", 0, 3, "y x");
    fit("d", 0, 4, "x y");
    fit("e", 0, 5, "x y");
    fit("f", 0, 1, "x y");
    validation("q", 1_000, "X");
    final Augmentation.Result r = Augmentation.run(issues);
    // g is the closest (distance 0); a..d follow by id at distance 1/2, f is left out at n = 5
    assertEquals(4.0, jaccard(r, "q", 5, Variant.INNER));
    assertEquals(4.0, jaccard(r, "q", 10, Variant.INNER), "all seven: 1 2 3 4 5 7 100");
    assertEquals(4.0, jaccard(r, "q", 20, Variant.INNER));
    final Augmentation.Query q = r.queries().stream()
        .filter(x -> x.id().equals("q") && x.variant() == Variant.INNER).findFirst().orElseThrow();
    assertEquals(List.of("g", "a", "b", "c", "d", "e", "f"),
        q.nearest().stream().map(Augmentation.Neighbour::id).toList());
    assertEquals(0.0, q.nearest().get(0).distance());
    assertEquals(0.5, q.nearest().get(1).distance());
    assertEquals(7, q.candidates());
    assertEquals(1_000 * H, q.reference());
  }

  @Test
  @DisplayName("missing when the query has no tokens or no candidate shares one; the issue stays")
  void missing() {
    fit("a", 0, 10, "alpha beta");
    fit("b", 0, 20, "gamma");
    fit("c", 0, 30, null);
    validation("disjoint", 1_000, "delta");
    validation("empty", 1_000, "!! --");
    validation("absent", 1_000, null);
    validation("one", 1_000, "beta");
    final Augmentation.Result r = Augmentation.run(issues);
    for (final String id : List.of("disjoint", "empty", "absent")) {
      assertTrue(r.columns().containsKey(id), id);
      for (final int n : Augmentation.JACCARD_N) {
        assertNull(jaccard(r, id, n, Variant.INNER), id);
      }
    }
    assertEquals(10.0, jaccard(r, "one", 5, Variant.INNER), "fewer than n: the only one");
    assertTrue(r.columns().containsKey("c"));
  }

  private static Augmentation.Query query(final Augmentation.Result r, final String id,
      final Variant v) {
    return r.queries().stream().filter(x -> x.id().equals(id) && x.variant() == v).findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("five blocks, equal instants together, limits reported; the first block has no "
      + "history and stays missing")
  void blocks() {
    final long[] starts = {0, 0, 10, 10, 10, 20, 30, 40, 50, 60};
    for (int i = 0; i < starts.length; i++) {
      fit("f" + i, starts[i], 5, "x");
    }
    validation("v", 100, "x");
    final Augmentation.Result r = Augmentation.run(issues);
    final List<Augmentation.Block> inner =
        r.blocks().stream().filter(b -> b.variant() == Variant.INNER).toList();
    assertEquals(List.of(2, 3, 1, 2, 2), inner.stream().map(Augmentation.Block::rows).toList());
    assertEquals(List.of(0L, 10 * H, 20 * H, 30 * H, 50 * H),
        inner.stream().map(Augmentation.Block::begins).toList());
    assertEquals(List.of(0L, 10 * H, 20 * H, 40 * H, 60 * H),
        inner.stream().map(Augmentation.Block::last).toList());
    assertEquals(List.of(0, 2, 5, 6, 8), inner.stream().map(Augmentation.Block::history).toList());
    assertNull(jaccard(r, "f0", 5, Variant.INNER));
    assertNull(jaccard(r, "f1", 5, Variant.INNER));
    assertEquals(5.0, jaccard(r, "f2", 5, Variant.INNER));
    assertEquals("2", query(r, "f4", Variant.INNER).block());
    assertEquals(10 * H, query(r, "f4", Variant.INNER).reference());
    assertEquals(Augmentation.WHOLE, query(r, "v", Variant.INNER).block());
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.jaccard(5, Variant.INNER), "block",
            "inner_fitting 1", 2, 0)));
    assertTrue(r.coverage().contains(
        new Augmentation.Coverage(Augmentation.jaccard(5, Variant.INNER), "split",
            "inner_fitting", 10, 8)));
  }

  @Test
  @DisplayName("an issue crossing the inner cut enters an outer block's history only once "
      + "resolved, and never the inner history")
  void crossing() {
    for (int i = 0; i < 10; i++) {
      fit("f" + i, 10L * i, 5, "x");
      validation("v" + i, 100 + 10L * i, "x");
    }
    issue("c", "TRAIN", "CROSSES", 50, 95, "x");
    final Augmentation.Result r = Augmentation.run(issues);
    final List<Augmentation.Block> outer =
        r.blocks().stream().filter(b -> b.variant() == Variant.OUTER).toList();
    assertEquals(List.of(0L, 40 * H, 70 * H, 110 * H, 150 * H),
        outer.stream().map(Augmentation.Block::begins).toList());
    for (final Augmentation.Query q : r.queries()) {
      final boolean seen = q.nearest().stream().anyMatch(n -> n.id().equals("c"));
      final boolean resolved = q.variant() == Variant.OUTER && 145 * H < q.reference();
      assertEquals(resolved, seen, q.toString());
    }
    assertTrue(query(r, "v9", Variant.OUTER).nearest().stream().anyMatch(n -> n.id().equals("c")));
    assertNull(jaccard(r, "c", 5, Variant.INNER), "outside the inner sets");
    assertNotEquals(null, jaccard(r, "c", 5, Variant.OUTER));
  }

  /** An issue of {@code reporter} in {@code project} with no description. */
  private void by(final String id, final String project, final String reporter,
      final String outer, final String inner, final long start, final long hours) {
    issues.add(new Augmentation.Issue(id, project, reporter, start * H, (start + hours) * H, outer,
        inner, null));
  }

  private static Double reporter(final Augmentation.Result r, final String id, final Variant v) {
    return r.columns().get(id).get(Augmentation.reporter(v));
  }

  @Test
  @DisplayName("reporter median: only the same reporter and project, only the matching fitting set, "
      + "only issues resolved strictly before the start; validation and test outcomes never count")
  void reporterMedian() {
    by("a", "p", "r", "TRAIN", "TRAIN", 0, 10);
    by("b", "p", "r", "TRAIN", "TRAIN", 0, 30);
    by("other", "p", "s", "TRAIN", "TRAIN", 0, 3);
    by("elsewhere", "q", "r", "TRAIN", "TRAIN", 0, 3);
    by("late", "p", "r", "TRAIN", "TRAIN", 5, 35); // resolved at 40, the start of f
    by("c", "p", "r", "TRAIN", "CROSSES", 1, 23); // outer training, outside the inner sets
    by("f", "p", "r", "TRAIN", "TRAIN", 40, 2);
    by("v1", "p", "r", "TRAIN", "TEST", 100, 1);
    by("v2", "p", "r", "TRAIN", "TEST", 50, 4);
    by("t2", "p", "r", "TEST", "", 150, 1);
    by("t1", "p", "r", "TEST", "", 200, 1);
    final Augmentation.Result r = Augmentation.run(issues);
    assertEquals(20.0, reporter(r, "f", Variant.INNER), "a and b; late resolved at f's start");
    assertEquals(23.0, reporter(r, "f", Variant.OUTER), "a, b and the crossing c");
    assertEquals(20.0, reporter(r, "v1", Variant.INNER), "f, a, b, late; never v2");
    assertEquals(16.5, reporter(r, "v1", Variant.OUTER), "f, v2, a, c, b, late");
    assertEquals(10.0, reporter(r, "t1", Variant.OUTER), "the outer training; never t2");
    assertEquals(10.0, reporter(r, "t2", Variant.OUTER));
    assertNull(reporter(r, "t1", Variant.INNER), "outside the inner sets");
    assertNull(reporter(r, "c", Variant.INNER), "outside the inner sets");
    assertNull(reporter(r, "c", Variant.OUTER), "nothing resolved before it started");
    assertNull(reporter(r, "other", Variant.INNER), "the only issue of s");
  }

  @Test
  @DisplayName("reporter median: missing with no eligible issue and the row stays; the issue "
      + "itself never counts")
  void reporterMissing() {
    by("a", "p", "r", "TRAIN", "TRAIN", 0, 5);
    by("a", "p", "r", "TRAIN", "TEST", 100, 1);
    by("alone", "p", "s", "TRAIN", "TEST", 100, 1);
    final Augmentation.Result r = Augmentation.run(issues);
    assertNull(reporter(r, "a", Variant.INNER), "its own earlier row is not another issue");
    assertTrue(r.columns().containsKey("alone"));
    assertNull(reporter(r, "alone", Variant.INNER));
    assertNull(reporter(r, "alone", Variant.OUTER));
  }

  /** A random corpus over both splits: 60 fitting, 20 validation, 15 crossing, 30 test. */
  private void random(final long seed) {
    final Random rng = new Random(seed);
    final String[] words = {"npe", "build", "test", "docs", "leak", "race", "ui", "api"};
    final java.util.function.Supplier<String> text = () -> {
      if (rng.nextInt(10) == 0) {
        return null;
      }
      final StringBuilder b = new StringBuilder();
      for (int k = rng.nextInt(4); k >= 0; k--) {
        b.append(words[rng.nextInt(words.length)]).append(' ');
      }
      return b.toString();
    };
    for (int i = 0; i < 60; i++) {
      final long start = rng.nextInt(50) * 10L;
      fit("f" + i, start, 1 + rng.nextInt((int) (600 - start)), text.get());
    }
    for (int i = 0; i < 15; i++) {
      issue("c" + i, "TRAIN", "CROSSES", rng.nextInt(60) * 10L, 700 + rng.nextInt(300),
          text.get());
    }
    for (int i = 0; i < 20; i++) {
      final long start = 610 + rng.nextInt(300);
      issue("v" + i, "TRAIN", "TEST", start, 1 + rng.nextInt((int) (1990 - start)), text.get());
    }
    for (int i = 0; i < 30; i++) {
      issue("t" + i, "TEST", "", 2000 + rng.nextInt(500), 1 + rng.nextInt(900), text.get());
    }
  }

  @Test
  @DisplayName("no neighbour resolved at or after the reference instant, none from outside the "
      + "fitting set, and an issue never in its own history")
  void noLeak() {
    random(7);
    final Map<String, Augmentation.Issue> byId = new java.util.HashMap<>();
    issues.forEach(i -> byId.put(i.id(), i));
    final Augmentation.Result r = Augmentation.run(issues);
    int read = 0;
    for (final Augmentation.Query q : r.queries()) {
      assertTrue(q.reference() <= byId.get(q.id()).start(), q.toString());
      for (final Augmentation.Neighbour n : q.nearest()) {
        final Augmentation.Issue h = byId.get(n.id());
        assertTrue(h.resolved() < q.reference(), q + " " + n);
        assertTrue(h.start() < q.reference(), q + " " + n);
        assertNotEquals(q.id(), n.id());
        assertTrue(q.variant() == Variant.INNER ? h.inner().equals("TRAIN")
            : h.outer().equals("TRAIN"), q + " " + n);
        read++;
      }
    }
    assertTrue(read > 500, "the corpus exercised the search: " + read);
  }

  @Test
  @DisplayName("an issue in its own history set is still never its own neighbour")
  void neverItself() {
    fit("a", 0, 5, "x");
    fit("b", 0, 5, "x");
    validation("a", 100, "x");
    assertFalse(query(Augmentation.run(issues), "a", Variant.INNER).nearest().stream()
        .anyMatch(n -> n.id().equals("a")));
  }

  @Test
  @DisplayName("adding a future issue, or changing a later duration, leaves earlier rows unchanged")
  void future() {
    final long[] starts = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90};
    for (int i = 0; i < starts.length; i++) {
      fit("f" + i, starts[i], 5 + i, "x y");
    }
    issue("t", "TEST", "", 200, 1, "x");
    final Map<String, Map<String, Double>> before = Augmentation.run(issues).columns();
    // both fitting sets are the same ten rows; eleven split into blocks at the same places as ten: 2, 4, 6, 8
    fit("late", 95, 1, "x");
    final Map<String, Map<String, Double>> added = Augmentation.run(issues).columns();
    issues.set(9, new Augmentation.Issue("f9", "p", "r", 90 * H, 91 * H, "TRAIN", "TRAIN", "x y"));
    final Map<String, Map<String, Double>> changed = Augmentation.run(issues).columns();
    for (int i = 0; i < 10; i++) {
      assertEquals(before.get("f" + i), added.get("f" + i), "f" + i);
      assertEquals(before.get("f" + i), changed.get("f" + i), "f" + i);
    }
    assertNotEquals(before.get("t"), changed.get("t"), "the test row reads them all");
  }

  /** The issues with each scored row of {@code part} resolved {@code hours} later. */
  private List<Augmentation.Issue> later(final String part, final long hours) {
    return issues.stream().map(i -> (part.equals("TEST") ? i.outer() : i.inner()).equals("TEST")
        ? new Augmentation.Issue(i.id(), i.project(), i.reporter(), i.start(),
            i.resolved() + hours * H, i.outer(), i.inner(), i.description()) : i).toList();
  }

  @Test
  @DisplayName("changing a validation duration changes no inner column; changing a test duration "
      + "changes no column at all")
  void scoredOutcomes() {
    random(11);
    final Map<String, Map<String, Double>> before = Augmentation.run(issues).columns();
    final Map<String, Map<String, Double>> validation =
        Augmentation.run(later("VALIDATION", 50)).columns();
    boolean outerMoved = false;
    for (final String id : before.keySet()) {
      for (final String column : Augmentation.columns()) {
        final Double was = before.get(id).get(column);
        final Double is = validation.get(id).get(column);
        if (column.endsWith("_inner")) {
          assertEquals(was, is, id + " " + column);
        } else {
          outerMoved |= !java.util.Objects.equals(was, is);
        }
      }
    }
    assertTrue(outerMoved, "the validation rows are outer training: the outer columns may move");
    assertEquals(before, Augmentation.run(later("TEST", 5_000)).columns());
  }
}
