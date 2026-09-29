package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import java.util.List;

/**
 * The grid of the secondary study (papers/issues-in-progress): the six cells on its inner
 * selection, with the main study's sweep. Reads CACHE (issues-andamento-extrair); writes
 * PARAMETROS; records the run in ISSUES_DB. The outer test rows are refused.
 */
public final class IssuesInProgressGridCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    IssuesGridCommand.sweep(context, IssuesPrinting.cache(), List.of(Population.IN_PROGRESS),
        "issues-andamento-grade");
  }
}
