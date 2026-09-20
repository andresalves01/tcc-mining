package app.labels;

/**
 * One method the issue's code changed, with the metrics of its state after that change.
 *
 * <p>Identified by {@code longName}, which SmartSHARK writes as package, class, method and
 * signature. The signature matters: two overloads are different methods and must not collapse into
 * one when the issue's methods are deduplicated.
 */
public record ChangedMethod(String longName, double mccc, double nl) {}
