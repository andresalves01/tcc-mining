package app.issues.model;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.Scenario;
import java.util.List;

/**
 * One attribute set a model reads: a family read under a scenario, with its columns.
 *
 * <p>The literature families' sets are declared here ({@link #literature()}): Al-Zubaidi and Kikas
 * at the start, and Kikas's retrospective set, Kikas at the start plus its counts observed up to
 * the resolution. Al-Zubaidi has nothing after the start, so only its start set. The own sets are
 * built by {@link app.issues.ElasticNetSelection}.
 */
public record Cell(String label, Family family, Scenario scenario, List<Attribute> attributes) {

  public Cell {
    attributes = List.copyOf(attributes);
  }

  /** Al-Zubaidi at the start, Kikas at the start and Kikas's retrospective set, in that order. */
  public static List<Cell> literature() {
    return List.of(
        of(Family.ALZUBAIDI, Scenario.C1),
        of(Family.KIKAS, Scenario.C1),
        of(Family.KIKAS, Scenario.C2));
  }

  private static Cell of(final Family family, final Scenario scenario) {
    return new Cell(family + " / " + scenario, family, scenario,
        Attributes.ofInProgress(family, scenario));
  }

  /** One of the {@link #literature()} sets by its label, {@code "KIKAS / C2"} for instance. */
  public static Cell byLabel(final String label) {
    return literature().stream().filter(c -> c.label().equals(label)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such cell: " + label));
  }
}
