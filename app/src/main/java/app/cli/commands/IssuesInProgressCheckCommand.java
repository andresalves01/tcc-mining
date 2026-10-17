package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.issues.Universe.Population;
import java.util.List;

/**
 * Every reading of the in-progress study on its inner selection, with the parameters in PARAMETERS:
 * the three references and the rule that picks the main one (P12), the families at the first In
 * Progress, the content block, the bands and the sensitivities (P16). Optimistic by construction;
 * the outer test is not read. CACHE must be the registered study's matrix by SHA-256, refused
 * otherwise before anything else is read ({@link IssuesPrinting#registeredCache}).
 */
public final class IssuesInProgressCheckCommand implements CommandRunner {

  static final String STAGE = "issues-in-progress-check";

  @Override
  public void run(final Context context) throws CommandFailedException {
    IssuesCheckCommand.check(context, IssuesPrinting.registeredCache(STAGE),
        List.of(Population.IN_PROGRESS), STAGE);
  }
}
