package app.issues.model;

import app.pullrequests.model.Baseline;
import app.issues.IssueUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The three references every model is read against, all learned on the fitting side.
 *
 * <ul>
 *   <li>the <b>project median</b>, the mandatory one, computed by the pull-request study's own class;
 *   <li>the <b>constant</b>: one number, the median of the fitting side. Among constants the median
 *       minimises absolute error, and in the pull-request study it beat the mandatory reference --
 *       so a gain is always shown against both;
 *   <li>the <b>pair median</b>: the median duration of the same person in the same project, on the
 *       fitting side. It uses the person who did the work, so it belongs beside the two profiles
 *       where that person is given, and is labelled as such everywhere it is printed. A pair with
 *       no fitting row falls back to the project median, then to the constant.
 * </ul>
 */
public final class References {

  public final Baseline project;
  private final Map<String, Double> pair;

  private References(final Baseline project, final Map<String, Double> pair) {
    this.project = project;
    this.pair = pair;
  }

  public static References learnedOn(final List<IssueUnit> fit) {
    final Map<String, List<Double>> hours = new HashMap<>();
    for (final IssueUnit unit : fit) {
      if (unit.executor() == null) {
        continue;
      }
      hours.computeIfAbsent(key(unit), k -> new ArrayList<>()).add(unit.durationHours());
    }
    final Map<String, Double> medians = new HashMap<>(hours.size() * 2);
    hours.forEach((k, v) -> medians.put(k, median(v)));
    return new References(Baseline.learnedOn(fit), medians);
  }

  public double constantHours() {
    return project.constantHours();
  }

  public double[] projectHours(final List<IssueUnit> scored) {
    final double[] out = new double[scored.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = project.predictHours(scored.get(i));
    }
    return out;
  }

  public double[] constant(final int n) {
    final double[] out = new double[n];
    java.util.Arrays.fill(out, project.constantHours());
    return out;
  }

  public double[] pairHours(final List<IssueUnit> scored) {
    final double[] out = new double[scored.size()];
    for (int i = 0; i < out.length; i++) {
      final IssueUnit unit = scored.get(i);
      // no executor: the conditioned reference has no person to condition on
      out[i] = unit.executor() == null ? project.predictHours(unit)
          : pair.getOrDefault(key(unit), project.predictHours(unit));
    }
    return out;
  }

  private static String key(final IssueUnit unit) {
    return unit.executor() + '\u0000' + unit.project();
  }

  static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compare);
    final int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1 ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
  }
}
