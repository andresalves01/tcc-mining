package app.paper5.model;

import app.paper5.Attribute;
import app.paper5.Attributes;
import app.paper5.Family;
import app.paper5.Profile;
import java.util.List;
import java.util.Set;

/**
 * One cell of the results grid: a family of constructs, read under one profile of temporal
 * evidence, with an optional named exclusion.
 *
 * <p>The exclusion exists for one measured reason. {@code area_hotness_90d} is absent on 71,4% of
 * the reserved side, because the corpus stopped mining commits around mid-2018 while the population
 * runs to 2021 -- so on those rows the construct measures nothing, and its absence indicator is very
 * nearly a marker for "opened after the mining stopped". Reporting the literature profile without
 * it is the reading that can be interpreted; reporting it with it is the sensitivity that shows what
 * the difference costs.
 *
 * <p>The construct is <b>not</b> removed from the specification. Removing it after measuring its
 * coverage would be choosing an attribute by the data it happened to have, which is the same error
 * as choosing one by the score it produces.
 */
public record Cell(Family family, Profile profile, Set<String> excluded) {

  /** The construct the literature profile is reported both with and without. */
  public static final String HOTNESS = "area_hotness_90d";

  public Cell {
    excluded = Set.copyOf(excluded);
  }

  public static Cell of(final Family family, final Profile profile) {
    return new Cell(family, profile, Set.of());
  }

  public static Cell without(final Family family, final Profile profile, final String column) {
    return new Cell(family, profile, Set.of(column));
  }

  /** The constructs this cell fits on, in the registry's stable order. */
  public List<Attribute> attributes() {
    return Attributes.of(family, profile).stream()
        .filter(attribute -> !excluded.contains(attribute.name()))
        .toList();
  }

  /** What the report prints in the leftmost column. */
  public String label() {
    return family + " / " + profile + (excluded.isEmpty() ? "" : " sem " + String.join(",", excluded));
  }
}
