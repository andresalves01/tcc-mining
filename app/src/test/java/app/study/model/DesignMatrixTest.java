package app.study.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.study.Construct;
import app.study.Outcome;
import app.study.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The matrix: everything learned on one side, applied unchanged to the other. */
class DesignMatrixTest {

  private record Column(String name, Construct.Kind kind, Construct.Transform transform,
      boolean absenceIndicator) implements Construct {

    @Override
    public String absenceName() {
      return name + "_ausente";
    }
  }

  private record Done(String project, double durationHours) implements Outcome {

    @Override
    public double duration() {
      return durationHours;
    }
  }

  private static final Column COUNT =
      new Column("count", Construct.Kind.NUMERIC, Construct.Transform.LOG1P, false);
  private static final Column WEEKDAY =
      new Column("weekday", Construct.Kind.CATEGORICAL, Construct.Transform.NONE, false);
  private static final Column MENTION =
      new Column("mention", Construct.Kind.BINARY, Construct.Transform.NONE, false);
  private static final Column RATIO =
      new Column("ratio", Construct.Kind.NUMERIC, Construct.Transform.NONE, true);

  private static Row row(final String id, final Map<String, Double> values) {
    return new Row(id, values, Set.of());
  }

  private static Row absent(final String id, final Map<String, Double> values,
      final String missing) {

    return new Row(id, values, Set.of(missing));
  }

  @Test
  @DisplayName("scaling comes from the fit side, so a wild scored row cannot move it")
  void scalingIsLearnedOnTheFitSideOnly() {
    final List<Column> attributes = List.of(COUNT);
    final List<Row> fit = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      fit.add(row("f" + i, Map.of("count", (double) i)));
    }
    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);
    final double[] before = matrix.apply(fit.get(4));

    matrix.apply(row("wild", Map.of("count", 1_000_000.0)));

    assertArrayEquals(before, matrix.apply(fit.get(4)), 0.0,
        "applying a row must not update anything; the transform is fixed when it is fitted");
  }

  @Test
  @DisplayName("a category seen only in the scored set is accepted, as all zeros")
  void unseenCategoriesAreAccepted() {
    // A project appearing only after the cut can legitimately show a level no training row used.
    // Refusing the row would drop it.
    final List<Column> attributes = List.of(WEEKDAY);
    final List<Row> fit = List.of(
        row("a", Map.of("weekday", 1.0)),
        row("b", Map.of("weekday", 2.0)),
        row("c", Map.of("weekday", 1.0)));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);
    final double[] scored = matrix.apply(row("d", Map.of("weekday", 6.0)));

    assertEquals(1, matrix.columns().size(),
        "two levels seen, the lowest is the reference, so one indicator");
    assertEquals("weekday=2", matrix.columns().get(0));
    // Standardised, so "not that level" is not literally zero -- it is whatever zero maps to.
    final double[] alsoNotLevelTwo = matrix.apply(row("e", Map.of("weekday", 1.0)));
    assertEquals(alsoNotLevelTwo[0], scored[0], 1e-12,
        "an unseen level lands where the reference level lands, which is the honest default");
  }

  @Test
  @DisplayName("a column that never varies on the fit side is dropped by name")
  void constantColumnsAreDropped() {
    final List<Column> attributes =
        List.of(COUNT, MENTION);
    final List<Row> fit = List.of(
        row("a", Map.of("count", 1.0, "mention", 0.0)),
        row("b", Map.of("count", 5.0, "mention", 0.0)));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);

    assertTrue(matrix.dropped().contains("mention"),
        "standardising a constant divides by zero, and keeping it feeds the estimator nothing");
    assertFalse(matrix.columns().contains("mention"));
    assertTrue(matrix.columns().contains("count"));
  }

  @Test
  @DisplayName("an absent value is filled with the fit median and the indicator rides beside it")
  void absenceIsImputedAndFlagged() {
    final List<Column> attributes = List.of(RATIO);
    final List<Row> fit = List.of(
        row("a", Map.of("ratio", 0.2, "ratio_ausente", 0.0)),
        row("b", Map.of("ratio", 0.8, "ratio_ausente", 0.0)),
        absent("c", Map.of("ratio_ausente", 1.0), "ratio"));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);

    assertEquals(List.of("ratio", "ratio_ausente"), matrix.columns(),
        "the value and the flag that explains it, never one instead of the other");
    // Median of the two present values is 0.5; the absent row is filled with it and standardised
    // to the same place a genuine 0.5 would land.
    final double[] filled = matrix.apply(
        absent("d", Map.of("ratio_ausente", 1.0), "ratio"));
    final double[] genuine = matrix.apply(
        row("e", Map.of("ratio", 0.5, "ratio_ausente", 0.0)));
    assertEquals(genuine[0], filled[0], 1e-12);
    assertTrue(filled[1] != genuine[1], "and the flag is what keeps the two rows distinguishable");
  }

  @Test
  @DisplayName("log1p is applied where the attribute declares it and nowhere else")
  void transformsFollowTheDeclaration() {
    final List<Row> fit = List.of(
        row("a", Map.of("count", 0.0)),
        row("b", Map.of("count", 999.0)));
    final DesignMatrix matrix =
        DesignMatrix.fittedOn(fit, List.of(COUNT));

    // a row at the mean of the transformed column standardises to 0
    final double expectedMean = (Math.log1p(0) + Math.log1p(999)) / 2;
    assertEquals(0.0, matrix.apply(row("m", Map.of("count", Math.expm1(expectedMean))))[0], 1e-9,
        "the mean is of the transformed column, not of the raw one");
  }

  @Test
  @DisplayName("the target is log1p of hours, and comes back through expm1 without going negative")
  void theTargetRoundTrips() {
    final Done unit = new Done("p", 36.0);

    assertEquals(Math.log1p(36.0), DesignMatrix.target(unit), 1e-12);
    assertEquals(36.0, DesignMatrix.hours(DesignMatrix.target(unit)), 1e-9);
    assertEquals(0.0, DesignMatrix.hours(-0.4),
        "a slightly negative prediction is an artefact of the link, not a negative duration");
  }
}
