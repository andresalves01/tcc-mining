package app.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.issues.model.Fitting;
import app.study.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fence of the tail cut (ticket 23), its quantiles and what it leaves out. */
class TailCutTest {

  private static final Instant T = Instant.parse("2016-01-04T10:00:00Z");

  private static IssueUnit unit(final String id, final int days) {
    return new IssueUnit(id, "K-" + id, "p", "r", "e", T, T.plusSeconds(86_400L * days), "Fixed",
        false, T, days);
  }

  private static List<IssueUnit> units(final int... days) {
    final List<IssueUnit> out = new ArrayList<>();
    for (int i = 0; i < days.length; i++) {
      out.add(unit("u" + i, days[i]));
    }
    return out;
  }

  private static Fitting.Sides sides(final List<IssueUnit> fit, final List<IssueUnit> scored) {
    final double[] observed = scored.stream().mapToDouble(IssueUnit::duration).toArray();
    return new Fitting.Sides(fit, fit.stream().map(TailCutTest::row).toList(), scored,
        scored.stream().map(TailCutTest::row).toList(), observed);
  }

  private static Row row(final IssueUnit u) {
    return new Row(u.issueId(), Map.of("x", u.duration()), Set.of());
  }

  @Test
  @DisplayName("quantiles interpolate linearly between order statistics at (n - 1) p, the "
      + "definition R and numpy use by default")
  void quantiles() {
    final double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    assertEquals(3.25, TailCut.quantile(sorted, 0.25), 1e-12);
    assertEquals(7.75, TailCut.quantile(sorted, 0.75), 1e-12);
    assertEquals(5.5, TailCut.quantile(sorted, 0.5), 1e-12);
    assertEquals(1, TailCut.quantile(sorted, 0), 1e-12);
    assertEquals(10, TailCut.quantile(sorted, 1), 1e-12);
    assertEquals(4, TailCut.quantile(new double[] {4}, 0.75), 1e-12);
  }

  @Test
  @DisplayName("the fence is Q3 + k IQR of the given issues' business days; no cut is infinite")
  void fence() {
    // Q1 3.25, Q3 7.75, IQR 4.5
    final List<IssueUnit> fit = units(10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
    assertEquals(7.75 + 1.5 * 4.5, TailCut.fence(fit, 1.5), 1e-12);
    assertEquals(7.75 + 3 * 4.5, TailCut.fence(fit, TailCut.USUAL_FLOW_K), 1e-12);
    assertEquals(Double.POSITIVE_INFINITY, TailCut.fence(fit, TailCut.NO_CUT));
    assertThrows(IllegalArgumentException.class, () -> TailCut.fence(List.of(), 3));
  }

  @Test
  @DisplayName("the grid is 1.5, 3, 5, 10 and no cut, the usual flow's k is 3, and k reads back "
      + "from the parameters file as written")
  void grid() {
    assertEquals(List.of(1.5, 3.0, 5.0, 10.0, Double.POSITIVE_INFINITY), TailCut.GRID);
    assertEquals(3.0, TailCut.USUAL_FLOW_K);
    for (final double k : TailCut.GRID) {
      assertEquals(k, TailCut.parse(TailCut.label(k)));
    }
    assertEquals("none", TailCut.label(TailCut.NO_CUT));
    assertEquals("1.5", TailCut.label(1.5));
    assertThrows(IllegalArgumentException.class, () -> TailCut.parse("2.0"));
    assertThrows(IllegalArgumentException.class, () -> TailCut.parse("Infinity"));
  }

  @Test
  @DisplayName("the cut leaves out exactly the fitting rows above the fence, at it kept, and "
      + "never a scored row; no cut is the same rows, the very lists")
  void cut() {
    final List<IssueUnit> fit = units(1, 2, 3, 4, 50, 5, 6, 7);
    final List<IssueUnit> scored = units(1, 100, 3);
    final Fitting.Sides sides = sides(fit, scored);
    final Fitting.Sides cut = TailCut.cut(sides, 6);
    assertEquals(List.of("u0", "u1", "u2", "u3", "u5", "u6"),
        cut.fitUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(List.of("u0", "u1", "u2", "u3", "u5", "u6"),
        cut.fitRows().stream().map(Row::unitId).toList());
    assertSame(sides.scoreUnits(), cut.scoreUnits());
    assertSame(sides.scoreRows(), cut.scoreRows());
    assertSame(sides.observed(), cut.observed());
    final Fitting.Sides none = TailCut.cut(sides, Double.POSITIVE_INFINITY);
    assertSame(sides, none);
  }

  @Test
  @DisplayName("the usual flow keeps exactly the scored rows at or below the fence, the fitting "
      + "rows untouched")
  void usualFlow() {
    final List<IssueUnit> fit = units(1, 2, 300);
    final List<IssueUnit> scored = units(1, 100, 3, 6, 7);
    final Fitting.Sides sides = sides(fit, scored);
    final Fitting.Sides usual = TailCut.usualFlow(sides, 6);
    assertEquals(List.of("u0", "u2", "u3"),
        usual.scoreUnits().stream().map(IssueUnit::issueId).toList());
    assertEquals(List.of("u0", "u2", "u3"),
        usual.scoreRows().stream().map(Row::unitId).toList());
    assertEquals(List.of(1.0, 3.0, 6.0),
        java.util.Arrays.stream(usual.observed()).boxed().toList());
    assertSame(sides.fitUnits(), usual.fitUnits());
    assertSame(sides.fitRows(), usual.fitRows());
    assertEquals(List.of(true, false, true, true, false),
        boxed(TailCut.within(sides.observed(), 6)));
  }

  @Test
  @DisplayName("the lowest error wins; of errors exactly equal, the larger k, the smaller cut")
  void chosen() {
    final List<TailCut.Trial> distinct = List.of(new TailCut.Trial(1.5, 10, 90, 3.0),
        new TailCut.Trial(3, 20, 95, 2.0), new TailCut.Trial(5, 30, 97, 2.5),
        new TailCut.Trial(10, 50, 99, 2.7), new TailCut.Trial(TailCut.NO_CUT, TailCut.NO_CUT,
            100, 2.9));
    assertEquals(1, TailCut.chosen(distinct));
    final List<TailCut.Trial> tied = List.of(new TailCut.Trial(1.5, 10, 90, 2.0),
        new TailCut.Trial(3, 20, 95, 2.0), new TailCut.Trial(5, 30, 97, 2.0),
        new TailCut.Trial(10, 50, 99, 2.5), new TailCut.Trial(TailCut.NO_CUT, TailCut.NO_CUT,
            100, 2.5));
    assertEquals(2, TailCut.chosen(tied));
    final List<TailCut.Trial> all = List.of(new TailCut.Trial(1.5, 10, 100, 2.0),
        new TailCut.Trial(TailCut.NO_CUT, TailCut.NO_CUT, 100, 2.0));
    assertEquals(1, TailCut.chosen(all));
    // a hair lower is lower: no tolerance
    final List<TailCut.Trial> hair = List.of(new TailCut.Trial(1.5, 10, 90, Math.nextDown(2.0)),
        new TailCut.Trial(TailCut.NO_CUT, TailCut.NO_CUT, 100, 2.0));
    assertEquals(0, TailCut.chosen(hair));
  }

  @Test
  @DisplayName("the error on the usual flow averages only the issues at or below the fence")
  void usualFlowMae() {
    final double[] observed = {1, 100, 3};
    final double[] predicted = {2, 2, 2};
    assertEquals(1.0, TailCut.mae(predicted, observed, 6), 1e-12);
  }

  private static List<Boolean> boxed(final boolean[] values) {
    final List<Boolean> out = new ArrayList<>();
    for (final boolean v : values) {
      out.add(v);
    }
    return out;
  }

  @Test
  @DisplayName("the main reading is all issues, first, and the usual flow the complementary one; "
      + "each reading's words say so")
  void mainReadingByRule() {
    assertSame(TailCut.Reading.ALL, TailCut.Reading.main(SplitRule.REVISED));
    assertEquals(List.of(TailCut.Reading.ALL, TailCut.Reading.USUAL_FLOW),
        TailCut.Reading.ordered(SplitRule.REVISED));
    assertEquals("usual flow (complementary reading): the scored issues at or below the k = 3 "
        + "fence", TailCut.Reading.USUAL_FLOW.description(SplitRule.REVISED));
    assertEquals("all issues (main reading): every scored issue, those above the k = 3 fence "
        + "included", TailCut.Reading.ALL.description(SplitRule.REVISED));
  }
}
