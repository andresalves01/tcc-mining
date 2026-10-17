package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import java.util.List;

/**
 * The grid of the in-progress study: the six cells on its inner selection, with the main study's
 * sweep. Reads CACHE, which must be the registered study's matrix by SHA-256, refused otherwise
 * before anything else is read ({@link IssuesPrinting#registeredCache}); writes PARAMETERS;
 * records the run in ISSUES_DB. The outer test rows are refused.
 */
public final class IssuesInProgressGridCommand implements CommandRunner {

  static final String STAGE = "issues-in-progress-grid";

  @Override
  public void run(final Context context) throws CommandFailedException {
    IssuesGridCommand.sweep(context, IssuesPrinting.registeredCache(STAGE),
        List.of(Population.IN_PROGRESS), STAGE);
  }
}
