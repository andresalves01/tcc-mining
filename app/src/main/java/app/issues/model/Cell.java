package app.issues.model;

import app.issues.Attribute;
import app.issues.Attributes;
import app.issues.Family;
import app.issues.Scenario;
import app.issues.Universe;
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
        of(Family.OWN, Scenario.C1),
        of(Family.OWN, Scenario.C1_CONTENT),
        of(Family.OWN, Scenario.C1_EXPOSURE),
        of(Family.OWN, Scenario.C2),
        of(Family.UNION, Scenario.C1),
        of(Family.UNION, Scenario.C1_CONTENT),
        of(Family.UNION, Scenario.C1_EXPOSURE),
        of(Family.UNION, Scenario.C2));
  }

  public static Cell byLabel(final String label) {
    final String name = current(label);
    return grid().stream().filter(c -> c.label().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such cell: " + label));
  }

  /**
   * The eleven cells of the in-progress study (P6 and P19), the main
   * study's shape read at the first In Progress: Kikas has no content column, so its C2 is C1 plus
   * exposure.
   */
  public static List<Cell> gridInProgress() {
    return List.of(
        inProgress(Family.ALZUBAIDI, Scenario.C1),
        inProgress(Family.KIKAS, Scenario.C1),
        inProgress(Family.KIKAS, Scenario.C2),
        inProgress(Family.OWN, Scenario.C1),
        inProgress(Family.OWN, Scenario.C1_CONTENT),
        inProgress(Family.OWN, Scenario.C1_EXPOSURE),
        inProgress(Family.OWN, Scenario.C2),
        inProgress(Family.UNION, Scenario.C1),
        inProgress(Family.UNION, Scenario.C1_CONTENT),
        inProgress(Family.UNION, Scenario.C1_EXPOSURE),
        inProgress(Family.UNION, Scenario.C2));
  }

  private static Cell inProgress(final Family family, final Scenario scenario) {
    return new Cell(family + " / " + scenario, family, scenario,
        Attributes.ofInProgress(family, scenario));
  }

  /**
   * A label as the cells are named now. Parameter files written before the code was translated
   * name the own family PROPRIA, the union UNIAO and the two C2 blocks CONTEUDO and EXPOSICAO.
   */
  static String current(final String label) {
    return label.replace("PROPRIA", "OWN").replace("UNIAO", "UNION")
        .replace("CONTEUDO", "CONTENT").replace("EXPOSICAO", "EXPOSURE");
  }

  /** The grid a population is read with. */
  public static List<Cell> grid(final Universe.Population population) {
    return population == Universe.Population.IN_PROGRESS ? gridInProgress() : grid();
  }

  public static Cell byLabel(final String label, final Universe.Population population) {
    final String name = current(label);
    return grid(population).stream().filter(c -> c.label().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such cell: " + label));
  }
}
