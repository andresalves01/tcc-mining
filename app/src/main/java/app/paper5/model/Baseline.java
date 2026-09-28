package app.paper5.model;

import app.paper5.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference every family has to beat: the project's own median duration, learned on the
 * training side.
 *
 * <p>Specified rather than chosen -- section 9.1 of the handout names it and names what it may not
 * be. It may not use the scored side's own outcomes, a median computed over merges after the cut,
 * or anything that consults the row being predicted. All of those are avoided by construction here:
 * the constructor sees only the fit side, and {@link #predictHours} takes a unit and reads nothing
 * but its project.
 *
 * <p>A project with no training row falls back to the global training median. Under this study's
 * frozen population that happens for six test rows: the inherited-history rule is off, so projects
 * appear in the test side that never appear in training. It affects the baseline alone -- no model
 * here receives a project identifier as a predictor.
 */
public final class Baseline {

  private final Map<String, Double> byProject;
  private final double global;
  private final int projects;

  private Baseline(final Map<String, Double> byProject, final double global, final int projects) {
    this.byProject = Map.copyOf(byProject);
    this.global = global;
    this.projects = projects;
  }

  public static Baseline learnedOn(final List<Unit> fit) {
    final Map<String, List<Double>> hours = new HashMap<>();
    final List<Double> all = new ArrayList<>(fit.size());
    for (final Unit unit : fit) {
      final double value = unit.durationHours();
      hours.computeIfAbsent(unit.project(), key -> new ArrayList<>()).add(value);
      all.add(value);
    }
    final Map<String, Double> medians = new HashMap<>(hours.size() * 2);
    hours.forEach((project, values) -> medians.put(project, median(values)));
    return new Baseline(medians, median(all), medians.size());
  }

  /**
   * The constant that minimises mean absolute error among all constants, learned on the fit side.
   *
   * <p>The median and not the mean, because the metric is absolute error: the sum of absolute
   * deviations is minimised by the median. On a target whose mean is four times its median --
   * 229 hours against 56 -- predicting the mean is far worse than predicting the median, and worse
   * than the mandatory baseline.
   *
   * <p>Reported as a second reference because on this data it <b>beats</b> the mandatory baseline:
   * the per-project median loses twenty-two hours to a single number. A gain measured only against
   * the mandatory reference therefore credits a model for the reference's weakness.
   */
  public double constantHours() {
    return global;
  }

  /** The prediction, in hours, which is the scale the primary metric is reported on. */
  public double predictHours(final Unit unit) {
    return byProject.getOrDefault(unit.project(), global);
  }

  /** How many scored rows fell back to the global median, which the report has to state. */
  public int fellBackOn(final List<Unit> scored) {
    int fell = 0;
    for (final Unit unit : scored) {
      if (!byProject.containsKey(unit.project())) {
        fell++;
      }
    }
    return fell;
  }

  /** The prediction for every scored row, in order. */
  public double[] projectOf(final List<? extends Unit> scored) {
    final double[] out = new double[scored.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = predictHours(scored.get(i));
    }
    return out;
  }

  public int projects() {
    return projects;
  }

  public double globalMedianHours() {
    return global;
  }

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compare);
    final int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1 ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
  }
}
