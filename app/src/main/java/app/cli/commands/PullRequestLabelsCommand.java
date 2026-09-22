package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pipeline.BuildPullRequestLabelsTask;
import java.util.List;

/**
 * Builds {@code issue_label} from merged pull requests instead of resolved issues -- the desfecho
 * under test in Artigo 4. See {@code papers/04-pull-requests/PLANO.md} for why.
 */
public final class PullRequestLabelsCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws CommandFailedException {
    final BuildPullRequestLabelsTask task = new BuildPullRequestLabelsTask(
        context.pullRequests(), context.labelStore(), context.externalRegistry());

    final BuildPullRequestLabelsTask.Report report;
    try {
      report = task.run();
    } catch (final BuildPullRequestLabelsTask.RegistryEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    System.out.printf("%n  ROTULOS POR PULL REQUEST%n%n");
    System.out.printf("  PRs mesclados, escaneados            : %,d%n", report.scanned());
    System.out.printf("  sem autor de commit unico            : %,d   (%.2f%%)%n",
        report.noSingleAuthor(), 100.0 * report.noSingleAuthor() / report.scanned());
    System.out.printf("  autor sem identidade canonica         : %,d   (%.2f%%)%n",
        report.unresolvedIdentity(), 100.0 * report.unresolvedIdentity() / report.scanned());
    System.out.printf("  UTILIZAVEIS                          : %,d   (%.2f%%)%n",
        report.written(), 100.0 * report.written() / report.scanned());
    System.out.printf("%n  gravou %,d linhas em %s%n", report.written(),
        context.labelStore().describe());
  }
}
