package app.pullrequests;

/**
 * What the design matrix needs to know about a column, and nothing about which study declared it.
 *
 * <p>Exists so the transposition to issues ({@code app.issues}) can build its matrices with the
 * same code that built these, instead of a copy of it. Two copies of the rule "fill with the fit
 * side's median, standardise with the fit side's spread" would be two chances for the rule to
 * drift, and the comparison between the two studies would then measure the drift as well.
 */
public interface Construct {

  String name();

  Attribute.Kind kind();

  Attribute.Transform transform();

  boolean absenceIndicator();

  /** The companion column that says the value is absent. Only called when there is one. */
  String absenceName();
}
