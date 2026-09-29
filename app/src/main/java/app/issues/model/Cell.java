package app.issues.model;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.Scenario;
import java.util.List;

/**
 * One cell of the grid: a family read under a scenario.
 *
 * <p>Eleven, declared before anything ran. Al-Zubaidi has nothing after creation, so only its C1
 * cell. Kikas's C2 additions are all exposure, so its C2 cell already is C1 plus exposure. The own
 * family and the union are read in all four scenarios, which is what separates the content block
 * from the exposure block (decision 14).
 */
public record Cell(String label, Family family, Scenario scenario, List<Attribute> attributes) {

  public Cell {
    attributes = List.copyOf(attributes);
  }

  public static Cell of(final Family family, final Scenario scenario) {
    return new Cell(family + " / " + scenario, family, scenario, Attributes.of(family, scenario));
  }

  public static List<Cell> grid() {
    return List.of(
        of(Family.ALZUBAIDI, Scenario.C1),
        of(Family.KIKAS, Scenario.C1),
        of(Family.KIKAS, Scenario.C2),
        of(Family.PROPRIA, Scenario.C1),
        of(Family.PROPRIA, Scenario.C1_CONTEUDO),
        of(Family.PROPRIA, Scenario.C1_EXPOSICAO),
        of(Family.PROPRIA, Scenario.C2),
        of(Family.UNIAO, Scenario.C1),
        of(Family.UNIAO, Scenario.C1_CONTEUDO),
        of(Family.UNIAO, Scenario.C1_EXPOSICAO),
        of(Family.UNIAO, Scenario.C2));
  }

  public static Cell byLabel(final String label) {
    return grid().stream().filter(c -> c.label().equals(label)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such cell: " + label));
  }
}
