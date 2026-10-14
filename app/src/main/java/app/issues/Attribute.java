package app.issues;

import app.pullrequests.Construct;
import java.util.Set;

/**
 * One column of the study, with the family it belongs to and what a model may know of it.
 *
 * @param id the identifier of {@code MATRIZ-VIABILIDADE-ISSUES.csv} (K13, Z09, A15, ...); the
 *     registry test checks the two agree in both directions
 * @param name the column name in the extraction, the same the Python specification uses
 * @param block {@code null} for a column known at creation (C1); the C2 block otherwise
 * @param inUnion false only where the union already has the construct under another name
 */
public record Attribute(String id, String name, Family family, Scenario.Block block,
    app.pullrequests.Attribute.Kind kind, app.pullrequests.Attribute.Transform transform,
    boolean absenceIndicator, boolean inUnion) implements Construct {

  public boolean atCreation() {
    return block == null;
  }

  /** Whether the column enters a cell of this family under this scenario. */
  public boolean inCell(final Family cellFamily, final Scenario scenario) {
    final boolean family = cellFamily == Family.UNIAO ? inUnion : cellFamily == this.family;
    return family && (atCreation() || scenario.admits(block));
  }

  @Override
  public String absenceName() {
    if (!absenceIndicator) {
      throw new IllegalStateException(name + " declares no absence indicator");
    }
    return name + "_ausente";
  }

  static Set<String> names(final java.util.Collection<Attribute> attributes) {
    return attributes.stream().map(Attribute::name).collect(java.util.stream.Collectors.toSet());
  }
}
