package app.study;

/** What the design matrix needs to know about a column, and nothing about which study declared it. */
public interface Construct {

  String name();

  Kind kind();

  Transform transform();

  boolean absenceIndicator();

  /** The companion column that says the value is absent. Only called when there is one. */
  String absenceName();

  /** What shape the value takes in the design matrix. */
  enum Kind {

    /** A number, transformed according to {@link #transform}. */
    NUMERIC,

    /**
     * A category expanded to indicators, with an unseen level in the test set accepted rather than
     * refused -- a project that appears only after the cut can legitimately show a level no training
     * row used.
     */
    CATEGORICAL,

    /** Already zero or one. Never transformed, never standardised away into something else. */
    BINARY
  }

  /** What the modelling pipeline applies before standardising. Declared ahead of any result. */
  enum Transform {

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
}
