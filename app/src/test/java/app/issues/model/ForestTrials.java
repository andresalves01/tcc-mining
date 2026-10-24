package app.issues.model;

import java.util.List;

/** The tie rule the forest sweep follows, restated so tests can check the sweep against it. */
public final class ForestTrials {

  private ForestTrials() {}

  /**
   * Which of the trials, in grid order, the sweep keeps: the first with the lowest error. A point
   * is kept only when its error is strictly lower, so of points whose errors are exactly equal the
   * earlier one wins -- the smaller share first, then the smaller leaf. No tolerance.
   */
  public static int chosen(final List<Forest.Trial> trials) {
    int best = -1;
    double bestMae = Double.POSITIVE_INFINITY;
    for (int t = 0; t < trials.size(); t++) {
      if (trials.get(t).mae() < bestMae) {
        bestMae = trials.get(t).mae();
        best = t;
      }
    }
    return best;
  }
}
