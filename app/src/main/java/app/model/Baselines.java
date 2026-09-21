package app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * What the model has to beat, built only from the fit side.
 *
 * <p>The study names one of these as its primary reference: the historical median duration of the
 * (developer, project) pair, computed exclusively from resolutions before the cut. No outcome from
 * the partition being scored takes part in building it — that is the whole point of computing it
 * here rather than over everything.
 *
 * <p>A pair with no history falls back to the project, and a project with none to the corpus. The
 * chain is stated rather than silent, because how often it is reached is itself a result: a
 * reference that mostly falls back is not a per-pair reference.
 */
public final class Baselines {

  private Baselines() {}

  /** The reference the study declares primary, with its fallbacks. */
  public static ToDoubleFunction<Observation> perPair(final List<Observation> fit) {
    final Map<String, Double> byPair = medianBy(fit, Observation::pair);
    final Map<String, Double> byProject = medianBy(fit, Observation::project);
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> byPair.getOrDefault(row.pair(),
        byProject.getOrDefault(row.project(), global));
  }

  /** How often the per-pair reference actually has a pair, which decides what it can be called. */
  public static double pairCoverage(final List<Observation> fit, final List<Observation> scored) {
    final Map<String, Double> byPair = medianBy(fit, Observation::pair);
    return scored.stream().filter(row -> byPair.containsKey(row.pair())).count()
        / (double) scored.size();
  }

  public static ToDoubleFunction<Observation> perProject(final List<Observation> fit) {
    final Map<String, Double> byProject = medianBy(fit, Observation::project);
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> byProject.getOrDefault(row.project(), global);
  }

  /** Guessing the same number every time: the floor any model has to clear to mean anything. */
  public static ToDoubleFunction<Observation> constant(final List<Observation> fit) {
    final double global = median(fit.stream().map(Observation::logTtc).toList());
    return row -> global;
  }

  private static Map<String, Double> medianBy(final List<Observation> fit,
      final Function<Observation, String> key) {

    final Map<String, List<Double>> grouped = new HashMap<>();
    for (final Observation row : fit) {
      grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row.logTtc());
    }
    final Map<String, Double> medians = new HashMap<>(grouped.size() * 2);
    grouped.forEach((k, values) -> medians.put(k, median(values)));
    return medians;
  }

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }
}
