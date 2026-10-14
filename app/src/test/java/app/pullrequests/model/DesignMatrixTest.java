package app.pullrequests.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.pullrequests.Attribute;
import app.pullrequests.Attributes;
import app.pullrequests.Row;
import app.pullrequests.Unit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The matrix: everything learned on one side, applied unchanged to the other. */
class DesignMatrixTest {

  private static final Instant T0 = Instant.parse("2018-06-15T12:00:00Z");

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
    final List<Attribute> attributes = List.of(Attributes.byName("owner_prior_prs"));
    final List<Row> fit = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      fit.add(row("f" + i, Map.of("owner_prior_prs", (double) i)));
    }
    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);
    final double[] before = matrix.scaling().get("owner_prior_prs");

    matrix.apply(row("wild", Map.of("owner_prior_prs", 1_000_000.0)));

    assertEquals(before[0], matrix.scaling().get("owner_prior_prs")[0], 0.0,
        "applying a row must not update anything; the transform is fixed when it is fitted");
    assertEquals(before[1], matrix.scaling().get("owner_prior_prs")[1], 0.0);
  }

  @Test
  @DisplayName("a category seen only in the scored set is accepted, as all zeros")
  void unseenCategoriesAreAccepted() {
    // The specification requires it: a project appearing only after the cut can legitimately open
    // its first pull request at an hour no training row used. Refusing the row would drop it.
    final List<Attribute> attributes = List.of(Attributes.byName("created_weekday_utc"));
    final List<Row> fit = List.of(
        row("a", Map.of("created_weekday_utc", 1.0)),
        row("b", Map.of("created_weekday_utc", 2.0)),
        row("c", Map.of("created_weekday_utc", 1.0)));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);
    final double[] scored = matrix.apply(row("d", Map.of("created_weekday_utc", 6.0)));

    assertEquals(1, matrix.columns().size(),
        "two levels seen, the lowest is the reference, so one indicator");
    assertEquals("created_weekday_utc=2", matrix.columns().get(0));
    // Standardised, so "not that level" is not literally zero -- it is whatever zero maps to.
    final double[] alsoNotLevelTwo = matrix.apply(row("e", Map.of("created_weekday_utc", 1.0)));
    assertEquals(alsoNotLevelTwo[0], scored[0], 1e-12,
        "an unseen level lands where the reference level lands, which is the honest default");
  }

  @Test
  @DisplayName("a column that never varies on the fit side is dropped by name")
  void constantColumnsAreDropped() {
    final List<Attribute> attributes =
        List.of(Attributes.byName("owner_prior_prs"), Attributes.byName("person_mention"));
    final List<Row> fit = List.of(
        row("a", Map.of("owner_prior_prs", 1.0, "person_mention", 0.0)),
        row("b", Map.of("owner_prior_prs", 5.0, "person_mention", 0.0)));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);

    assertTrue(matrix.dropped().contains("person_mention"),
        "standardising a constant divides by zero, and keeping it feeds the estimator nothing");
    assertFalse(matrix.columns().contains("person_mention"));
    assertTrue(matrix.columns().contains("owner_prior_prs"));
  }

  @Test
  @DisplayName("an absent value is filled with the fit median and the indicator rides beside it")
  void absenceIsImputedAndFlagged() {
    final List<Attribute> attributes = List.of(Attributes.byName("owner_merged_ratio"));
    final List<Row> fit = List.of(
        row("a", Map.of("owner_merged_ratio", 0.2, "owner_merged_ratio_ausente", 0.0)),
        row("b", Map.of("owner_merged_ratio", 0.8, "owner_merged_ratio_ausente", 0.0)),
        absent("c", Map.of("owner_merged_ratio_ausente", 1.0), "owner_merged_ratio"));

    final DesignMatrix matrix = DesignMatrix.fittedOn(fit, attributes);

    assertEquals(List.of("owner_merged_ratio", "owner_merged_ratio_ausente"), matrix.columns(),
        "the value and the flag that explains it, never one instead of the other");
    // Median of the two present values is 0.5; the absent row is filled with it and standardised
    // to the same place a genuine 0.5 would land.
    final double[] filled = matrix.apply(
        absent("d", Map.of("owner_merged_ratio_ausente", 1.0), "owner_merged_ratio"));
    final double[] genuine = matrix.apply(
        row("e", Map.of("owner_merged_ratio", 0.5, "owner_merged_ratio_ausente", 0.0)));
    assertEquals(genuine[0], filled[0], 1e-12);
    assertTrue(filled[1] != genuine[1], "and the flag is what keeps the two rows distinguishable");
  }

  @Test
  @DisplayName("log1p is applied where the attribute declares it and nowhere else")
  void transformsFollowTheDeclaration() {
    assertEquals(Attribute.Transform.LOG1P, Attributes.byName("owner_prior_prs").transform());
    assertEquals(Attribute.Transform.NONE, Attributes.byName("owner_merged_ratio").transform(),
        "a ratio bounded in [0,1] is not a skewed count");

    final List<Row> fit = List.of(
        row("a", Map.of("owner_prior_prs", 0.0)),
        row("b", Map.of("owner_prior_prs", 999.0)));
    final DesignMatrix matrix =
        DesignMatrix.fittedOn(fit, List.of(Attributes.byName("owner_prior_prs")));

    final double[] scaling = matrix.scaling().get("owner_prior_prs");
    final double expectedMean = (Math.log1p(0) + Math.log1p(999)) / 2;
    assertEquals(expectedMean, scaling[0], 1e-12,
        "the mean is of the transformed column, not of the raw one");
  }

  @Test
  @DisplayName("the target is log1p of hours, and comes back through expm1 without going negative")
  void theTargetRoundTrips() {
    final Unit unit = new Unit("a", "p", "ana", T0, T0.plusSeconds(36 * 3600));

    assertEquals(36.0, unit.durationHours(), 1e-9);
    assertEquals(Math.log1p(36.0), DesignMatrix.target(unit), 1e-12);
    assertEquals(36.0, DesignMatrix.hours(DesignMatrix.target(unit)), 1e-9);
    assertEquals(0.0, DesignMatrix.hours(-0.4),
        "a slightly negative prediction is an artefact of the link, not a negative duration");
  }
}
