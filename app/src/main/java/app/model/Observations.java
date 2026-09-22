package app.model;

import java.util.List;

/**
 * A partition, with the names of the columns in it and what had to be left out to build it.
 *
 * @param excludedEmptyTeam labels whose active team was empty. The study excludes them from the
 *     full model and counts them; carrying the count here is what makes the second half possible
 * @param excludedNoOutcome labels the chosen target has no duration for -- no external examination
 *     was recorded, or the interval was not positive. Counted apart from the empty teams because
 *     they are a different exclusion and reporting them on one line would hide both
 */
public record Observations(
    List<String> columns, List<Observation> rows, int excludedEmptyTeam, int excludedNoOutcome) {

  /** Additive: every issue stage fits the whole life, where no row can lack an outcome. */
  public Observations(
      final List<String> columns, final List<Observation> rows, final int excludedEmptyTeam) {

    this(columns, rows, excludedEmptyTeam, 0);
  }

  public Observations {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }
}
