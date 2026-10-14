package app.pullrequests.model;

import app.pullrequests.Row;
import app.pullrequests.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the durations already observed said, at the instant a pull request opened.
 *
 * <p>These are the three predictors the previous paper found carried the most: its own primary
 * reference, the median duration of that person in that project, took the largest standardised
 * coefficient of the whole model (0,77 against 0,41 for the size of the change) and alone reached an
 * {@code R²} of 0,083 on the log scale, more than any other single column. It was a rival of that
 * study's model before it was an ingredient of it.
 *
 * <p>They are offered here as a <b>separate family</b>, not merged into Yu's or Chouchen's. Those
 * two are replications, and a replication whose attribute set has grown is not one. The question
 * this family answers is a different one: whether what the previous paper found predictive adds
 * anything on top of the literature's own constructs.
 *
 * <p><b>Two rules, both from the previous paper's {@code PairHistory}, and both necessary for
 * different reasons.</b> Strictly anterior, because a duration is only known once the pull request
 * merged -- a row whose merge is later than this row's opening taught nobody anything by then. And
 * learned on the fit side alone, because an outcome from the partition being scored must not enter
 * any predictor, even one that is temporally in the past.
 *
 * <p>Absence is kept apart from zero throughout. A creator with no earlier merged pull request in
 * that project has no median there -- not a median of zero -- and the indicator beside the column
 * says which rows those are.
 */
public final class History {

  /** The creator's own past median in the same project. The previous paper's strongest column. */
  public static final String PAIR = "creator_project_median_hours";

  /** The creator's past median anywhere, which survives when the pair has no history. */
  public static final String CREATOR = "creator_median_hours";

  /**
   * The project's past median as of this instant.
   *
   * <p>Deliberately included, and it deserves a sentence: this is very nearly the mandatory
   * baseline, offered to the model as a predictor. That is the sharpest form of the question -- once
   * the reference is a column, does anything else earn its place beside it?
   */
  public static final String PROJECT = "project_median_hours";

  public static final List<String> COLUMNS = List.of(PAIR, CREATOR, PROJECT);

  private record Observed(long knownAt, double hours, String unitId) {}

  private final Map<String, List<Observed>> byPair;
  private final Map<String, List<Observed>> byCreator;
  private final Map<String, List<Observed>> byProject;

  private History(final Map<String, List<Observed>> byPair,
      final Map<String, List<Observed>> byCreator, final Map<String, List<Observed>> byProject) {

    this.byPair = byPair;
    this.byCreator = byCreator;
    this.byProject = byProject;
  }

  public static History learnedOn(final List<Unit> fit) {
    final Map<String, List<Observed>> byPair = new HashMap<>();
    final Map<String, List<Observed>> byCreator = new HashMap<>();
    final Map<String, List<Observed>> byProject = new HashMap<>();
    for (final Unit unit : fit) {
      // Known at its merge, not at its opening: that is when the duration became a fact.
      final Observed observed = new Observed(unit.mergedAt().toEpochMilli(),
          unit.durationHours(), unit.pullRequestId());
      byPair.computeIfAbsent(pairOf(unit), key -> new ArrayList<>()).add(observed);
      byCreator.computeIfAbsent(unit.creatorId(), key -> new ArrayList<>()).add(observed);
      byProject.computeIfAbsent(unit.project(), key -> new ArrayList<>()).add(observed);
    }
    sort(byPair);
    sort(byCreator);
    sort(byProject);
    return new History(byPair, byCreator, byProject);
  }

  private static void sort(final Map<String, List<Observed>> index) {
    index.values().forEach(list -> list.sort((a, b) -> Long.compare(a.knownAt(), b.knownAt())));
  }

  private static String pairOf(final Unit unit) {
    return unit.creatorId() + '\u0000' + unit.project();
  }

  /** The same row with the three columns and their indicators added. Never mutates the original. */
  public Row augment(final Row row, final Unit unit) {
    final long t0 = unit.openedAt().toEpochMilli();
    final Map<String, Double> values = new HashMap<>(row.values());
    final Set<String> absent = new HashSet<>(row.absent());

    put(values, absent, PAIR, medianBefore(byPair.get(pairOf(unit)), t0, unit));
    put(values, absent, CREATOR, medianBefore(byCreator.get(unit.creatorId()), t0, unit));
    put(values, absent, PROJECT, medianBefore(byProject.get(unit.project()), t0, unit));
    return new Row(row.unitId(), values, absent);
  }

  private static void put(final Map<String, Double> values, final Set<String> absent,
      final String column, final Double median) {

    if (median == null) {
      absent.add(column);
      values.put(column + "_ausente", 1.0);
    } else {
      values.put(column, median);
      values.put(column + "_ausente", 0.0);
    }
  }

  /**
   * The median of everything in this index known strictly before {@code t0}.
   *
   * @param self excluded by identifier as well as by instant. Its own merge is later than its own
   *     opening, so the window would drop it anyway -- excluding it by name says so out loud, and
   *     protects the rule against a future row with a zero-length life
   * @return {@code null} when nothing was known yet, which is absence and never zero
   */
  private static Double medianBefore(final List<Observed> index, final long t0, final Unit self) {
    if (index == null) {
      return null;
    }
    final List<Double> prior = new ArrayList<>();
    for (final Observed observed : index) {
      if (observed.knownAt() >= t0) {
        break;
      }
      if (observed.unitId().equals(self.pullRequestId())) {
        continue;
      }
      prior.add(observed.hours());
    }
    if (prior.isEmpty()) {
      return null;
    }
    prior.sort(Double::compare);
    final int middle = prior.size() / 2;
    return prior.size() % 2 == 1 ? prior.get(middle)
        : (prior.get(middle - 1) + prior.get(middle)) / 2.0;
  }
}
