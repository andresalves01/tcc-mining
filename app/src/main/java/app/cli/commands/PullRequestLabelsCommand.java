package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.pipeline.BuildPullRequestLabelsTask;
import java.util.List;

/**
 * Builds {@code issue_label} from merged pull requests instead of resolved issues -- the desfecho
 * under test in Artigo 4. See {@code papers/pull-requests/PLANO.md} for why.
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
    System.out.printf("  desfecho                             : abertura -> merge%n");
    System.out.printf("  PRs mesclados, escaneados            : %,d%n", report.scanned());
    System.out.printf("  sem autor de commit unico            : %,d   (%.2f%%)%n",
        report.noSingleAuthor(), 100.0 * report.noSingleAuthor() / report.scanned());
    System.out.printf("  autor sem identidade canonica         : %,d   (%.2f%%)%n",
        report.unresolvedIdentity(), 100.0 * report.unresolvedIdentity() / report.scanned());
    if (report.nonPositiveInterval() > 0) {
      System.out.printf("  intervalo nao positivo               : %,d   (%.2f%%)%n",
          report.nonPositiveInterval(), 100.0 * report.nonPositiveInterval() / report.scanned());
    }
    System.out.printf("  UTILIZAVEIS                          : %,d   (%.2f%%)%n",
        report.written(), 100.0 * report.written() / report.scanned());
    System.out.printf("    destes, identidade por quem abriu  : %,d   (%.2f%%)%n",
        report.identityFromCreator(),
        100.0 * report.identityFromCreator() / Math.max(report.written(), 1));

    final int examined = report.examinedByReview() + report.examinedByComment();
    final long decomposable = report.written() == 0 ? 1 : report.written();
    System.out.printf("%n  FRONTEIRA DO PRIMEIRO EXAME EXTERNO%n");
    System.out.printf("    por revisao formal                 : %,d   (%.2f%%)%n",
        report.examinedByReview(), 100.0 * report.examinedByReview() / decomposable);
    System.out.printf("    por comentario de terceiro         : %,d   (%.2f%%)%n",
        report.examinedByComment(), 100.0 * report.examinedByComment() / decomposable);
    System.out.printf("    sem exame registrado               : %,d   (%.2f%%)%n",
        report.notExamined(), 100.0 * report.notExamined() / decomposable);
    System.out.printf("    DECOMPONIVEIS                      : %,d   (%.2f%%)%n",
        examined, 100.0 * examined / decomposable);
    System.out.printf("    (sem exame o rotulo entra assim mesmo, com o desfecho total e sem as%n");
    System.out.printf("     duas parcelas -- ver ExamOrigin para por que nao ha fallback)%n");

    System.out.printf("%n  gravou %,d linhas em %s%n", report.written(),
        context.labelStore().describe());
  }
}
