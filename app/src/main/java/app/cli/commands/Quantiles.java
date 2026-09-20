package app.cli.commands;

import java.util.List;

/** Reporting quantiles over an already-sorted list. */
final class Quantiles {

  /**
   * The value at rank {@code p}, by nearest lower index -- no interpolation.
   *
   * <p>Reporting only. The estimator the analysis uses lives in the domain; picking an element that
   * is actually in the sample keeps a printed TTC one the corpus contains.
   */
  static double at(final List<Double> sorted, final double p) {
    return sorted.get((int) (p * (sorted.size() - 1)));
  }

  private Quantiles() {}
}
