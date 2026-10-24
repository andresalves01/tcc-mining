package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.contribution.ContributionScan;
import app.pipeline.BuildLedgerTask;
import java.util.List;
import java.util.Optional;

/** Builds {@code developer_contribution}: a row per non-merge commit and its canonical author. */
public final class BuildLedgerCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws Exception {
    final PipelineConfig config = context.config();

    final BuildLedgerTask task =
        new BuildLedgerTask(context.contributions(), context.ledger(), context.registry(),
            context.progress());

    final BuildLedgerTask.Report report;
    try {
      final Optional<String> pilot = config.pilotProject();
      report = pilot.isPresent() ? task.run(pilot.get()) : task.run();
    } catch (final BuildLedgerTask.RegistryEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    final ContributionScan scan = report.scan();
    System.out.printf("%n  commits scanned        : %,d%n", scan.commitsScanned());
    System.out.printf("  merges excluded        : %,d (%.2f%%)%n", scan.mergesSkipped(),
        100.0 * scan.mergesSkipped() / Math.max(1, scan.commitsScanned()));
    System.out.printf("  author unresolvable    : %,d (%.2f%%)%n",
        scan.authorsUnresolved(), 100 * scan.unresolvedShare());
    System.out.printf("  no file action         : %,d%n", scan.commitsWithoutFileAction());
    System.out.printf("  file actions read      : %,d%n", scan.fileActionsRead());
    System.out.printf("  contributions          : %,d  (%,d by bots)%n",
        scan.contributions().size(), scan.botContributions());
    System.out.printf("  lines added            : %,d  (%,d in .java)%n",
        scan.linesAdded(), scan.javaLinesAdded());
    System.out.printf("  lines deleted          : %,d  (%,d in .java)%n",
        scan.linesDeleted(), scan.javaLinesDeleted());
    System.out.printf("  volume ranked on       : %,d lines touched in .java%n", scan.javaVolume());
    System.out.printf("  wrote %,d rows to %s%n", report.written(),
        context.ledger().describe());
  }
}
