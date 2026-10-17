package app.cli.commands;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.cli.CommandFailedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registered in-progress grid and check read only the registered matrix: since ticket 21 the
 * extraction writes another population and split, which they must never take for theirs.
 */
class IssuesInProgressRegisteredCacheTest {

  @Test
  @DisplayName("a matrix other than the registered one, by SHA-256, is refused by the grid and the "
      + "check before anything else of it is read")
  void refusesAnotherMatrix(@TempDir final Path dir) throws IOException {
    // not even a matrix: a refusal by hash never parses it
    final Path cache = Files.writeString(dir.resolve("cache.tsv"), "not a matrix\n");
    for (final String stage : new String[] {IssuesInProgressGridCommand.STAGE,
        IssuesInProgressCheckCommand.STAGE}) {
      final CommandFailedException e = assertThrows(CommandFailedException.class,
          () -> IssuesPrinting.refuseUnregistered(cache, stage));
      final String message = String.join("\n", e.lines());
      assertTrue(message.contains("REFUSED") && message.contains(stage)
          && message.contains("registered in-progress study's matrix"), message);
    }
  }
}
