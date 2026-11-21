package app.cli.commands;

import app.issues.port.ResultStore;
import java.util.Optional;

/**
 * A run store that holds no run of a design other than the revised one, so a stage that must not
 * mix designs runs on it; what it records, and whether it ran, are the lambda's.
 */
@FunctionalInterface
interface FreshStore extends ResultStore {

  @Override
  default Optional<Long> otherDesignRuns() {
    return Optional.of(0L);
  }
}
