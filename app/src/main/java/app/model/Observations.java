package app.model;

import java.util.List;

/**
 * A partition, with the names of the columns in it and what had to be left out to build it.
 *
 * @param excludedEmptyTeam labels whose active team was empty. The study excludes them from the
 *     full model and counts them; carrying the count here is what makes the second half possible
 */
public record Observations(List<String> columns, List<Observation> rows, int excludedEmptyTeam) {

  public Observations {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }
}
