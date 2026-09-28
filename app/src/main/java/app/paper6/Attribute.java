package app.paper6;

import app.paper5.Construct;
import java.util.Set;

/**
 * One construct of the transposition, carrying its specification, its temporal class and its source.
 *
 * <p>Same shape as the pull-request study's attribute, and it shares that study's {@code Kind} and
 * {@code Transform}, so the two design matrices are built by the same code from the same rules.
 *
 * @param id {@code Y..} and {@code C..} keep the ids of the pull-request study's specification, so a
 *     construct can be traced across the two; {@code T..} are the triage fields; {@code P..} are the
 *     author's own issue columns
 * @param origin where the value comes from, in the words the report prints
 */
public record Attribute(
    String id,
    String name,
    app.paper5.Attribute.Kind kind,
    app.paper5.Attribute.Transform transform,
    Set<Family> families,
    TemporalClass temporalClass,
    boolean absenceIndicator,
    String origin) implements Construct {

  public Attribute {
    families = Set.copyOf(families);
  }

  public boolean inCell(final Family family, final Profile profile) {
    return families.contains(family) && profile.admits(temporalClass);
  }

  @Override
  public String absenceName() {
    if (!absenceIndicator) {
      throw new IllegalStateException(name + " declares no absence indicator");
    }
    return name + "_ausente";
  }
}
