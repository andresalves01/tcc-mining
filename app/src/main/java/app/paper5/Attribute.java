package app.paper5;

import java.util.Set;

/**
 * One construct of the replication, with everything a result has to be able to say about it.
 *
 * <p>Origin and temporal class travel on the column rather than in a document beside it. A table of
 * scores that cannot name which collections each predictor came from, and what the corpus actually
 * demonstrates about it at {@code t0}, is a table that invites being read as an ex ante claim it
 * does not support.
 *
 * <p><b>Values are stored raw.</b> {@link #transform} says what the modelling pipeline should do
 * when it builds the design matrix, and the extractor ignores it: {@code log1p} of a count is a
 * modelling choice, and storing the count means changing that choice costs a re-fit rather than a
 * re-mining. The interactions are products of the <i>raw</i> legs for the same reason.
 *
 * @param id the identifier from {@code MATRIZ-ATRIBUTOS.csv}: {@code Y01}..{@code Y17} for Yu's
 *     family and {@code C01}..{@code C10} for Chouchen's. A construct shared by both keeps Yu's
 *     id and records both under {@link #families}, so it is declared once
 * @param name the stored field name, which is also what the report prints
 * @param families every specification this construct belongs to
 * @param origin the collections it is read from, for the provenance column of the report
 */
public record Attribute(
    String id,
    String name,
    Kind kind,
    Transform transform,
    Set<Family> families,
    TemporalClass temporalClass,
    boolean absenceIndicator,
    String origin) implements Construct {

  /** What shape the value takes in the design matrix. */
  public enum Kind {

    /** A number, transformed according to {@link Attribute#transform}. */
    NUMERIC,

    /**
     * A category expanded to indicators, with an unseen level in the test set accepted rather than
     * refused -- the specification requires it, and a project that appears only after the cut can
     * legitimately open its first pull request at an hour no training row used.
     */
    CATEGORICAL,

    /** Already zero or one. Never transformed, never standardised away into something else. */
    BINARY
  }

  /** What the modelling pipeline applies before standardising. Declared ahead of any result. */
  public enum Transform {

    NONE,

    /**
     * {@code ln(1 + x)} on a non-negative count with a heavy right tail.
     *
     * <p>Declared per construct in advance, and the list is printed with every result, because
     * choosing a transform after seeing which one scored better is the same error as choosing a
     * population that way.
     */
    LOG1P
  }

  public Attribute {
    families = Set.copyOf(families);
  }

  /** Whether this construct takes part in a given family and profile at once. */
  public boolean inCell(final Family family, final Profile profile) {
    return families.contains(family) && profile.admits(temporalClass);
  }

  /** The name of the companion column that says the value is absent, when there is one. */
  public String absenceName() {
    if (!absenceIndicator) {
      throw new IllegalStateException(name + " declares no absence indicator");
    }
    return name + "_ausente";
  }
}
