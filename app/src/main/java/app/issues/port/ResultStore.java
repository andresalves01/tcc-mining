package app.issues.port;

import java.util.List;
import java.util.Map;

/**
 * Where this study's own results are persisted, which is never one of the frozen databases.
 *
 * <p>A port rather than a Mongo call inside the task, for the same reason every other stage has one:
 * the task that scores the reserved partition has to be runnable, and testable, without a database.
 *
 * <p><b>Append, never replace.</b> The reserved partition is scored once, so a second run is an
 * event worth seeing rather than an update worth absorbing. An implementation that overwrote the
 * earlier document would make two runs indistinguishable from one, which is exactly the thing the
 * whole design is arranged to prevent.
 */
public interface ResultStore {

  /**
   * Records one execution and the rows it produced.
   *
   * @param run provenance: when, under which registration, over which population
   * @param rows one document per specification and per reference, each self-describing
   * @return the identifier of the execution, for the report to print
   */
  String record(Map<String, Object> run, List<Map<String, Object>> rows);
}
