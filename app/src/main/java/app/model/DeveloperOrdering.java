package app.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Whether the model can say which of two developers finishes sooner — which is the question team
 * selection actually consumes.
 *
 * <p>Ordering tasks and ordering people are different abilities and the study needs the second.
 * A model can rank tasks well purely from how large each change turned out to be, and still be
 * unable to tell two people apart. So the comparison is restricted to pairs of observations in the
 * <b>same project</b> with <b>different developers</b> and <b>comparable tasks</b>, and what is
 * scored is whether the sign of the predicted difference matches the sign of the real one.
 *
 * <p><b>Every task attribute the model can see has to be matched on, not just size.</b> Measured
 *2026-09-20: controlling for size alone reports 59.6%, and adding the description length — which
 * is the strongest creation-time predictor there is — takes it to a different number entirely. A
 * column left unmatched does not test the developer component; it lets task ordering answer a
 * question that was asked about people.
 *
 * <p>The pairs are not independent of each other — one observation appears in many — so the
 * decimals of the resulting share must not be read as precision. What it answers is whether the
 * number sits above the coin flip at all.
 */
public final class DeveloperOrdering {

  private DeveloperOrdering() {}

  public record Result(int pairs, double agreement) {}

  /**
   * @param matchOn columns two tasks must be alike in before their developers are compared
   * @param tolerance how alike, on the transformed scale the columns arrive in
   */
  public static Result of(final List<Observation> rows,
      final ToDoubleFunction<Observation> prediction, final int[] matchOn,
      final double tolerance) {

    final Map<String, List<Observation>> byProject = new HashMap<>();
    for (final Observation row : rows) {
      byProject.computeIfAbsent(row.project(), k -> new ArrayList<>()).add(row);
    }
    int pairs = 0;
    int agree = 0;
    for (final List<Observation> inProject : byProject.values()) {
      for (int i = 0; i < inProject.size(); i++) {
        for (int j = i + 1; j < inProject.size(); j++) {
          final Observation a = inProject.get(i);
          final Observation b = inProject.get(j);
          if (a.developer().equals(b.developer())) {
            continue; // the question is about people, not about the same person twice
          }
          boolean comparable = true;
          for (final int column : matchOn) {
            if (Math.abs(a.values()[column] - b.values()[column]) > tolerance) {
              comparable = false;
              break; // too different a task for the developer to be what separates them
            }
          }
          if (!comparable) {
            continue;
          }
          final double real = a.logTtc() - b.logTtc();
          final double guess =
              prediction.applyAsDouble(a) - prediction.applyAsDouble(b);
          if (real == 0 || guess == 0) {
            continue; // a tie decides nothing either way
          }
          pairs++;
          if (Math.signum(real) == Math.signum(guess)) {
            agree++;
          }
        }
      }
    }
    return new Result(pairs, pairs == 0 ? 0 : (double) agree / pairs);
  }
}
