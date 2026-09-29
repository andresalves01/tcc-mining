package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import java.util.List;

/**
 * Every reading of the secondary study on its inner selection, with the parameters in PARAMETROS:
 * the three references and the rule that picks the main one (P12), the families at the first In
 * Progress, the content block, the bands and the sensitivities (P16). Optimistic by construction;
 * the outer test is not read.
 */
public final class IssuesInProgressCheckCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    IssuesCheckCommand.check(context, IssuesPrinting.cache(), List.of(Population.IN_PROGRESS),
        "issues-andamento-conferencia");
  }
}
