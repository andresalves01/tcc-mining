package app.cli.commands;

import app.cli.CommandFailedException;
import app.cli.CommandRunner;
import app.cli.Context;
import app.config.PipelineConfig;
import app.identity.JiraResolution;
import app.identity.port.PersonRegistry;
import app.pipeline.HarvestJiraTask;
import java.util.List;

/** Resolves Jira assignee ids against the public changelog API, then canonicalises. */
public final class HarvestJiraCommand implements CommandRunner {

  @Override
  public void run(final Context context) throws Exception {
    final PipelineConfig config = context.config();
    final PersonRegistry registry = context.registry();

    final HarvestJiraTask task =
        new HarvestJiraTask(context.jiraIdentities(), registry, config.strictness());

    final HarvestJiraTask.Report report;
    try {
      report = task.run();
    } catch (final HarvestJiraTask.RegistryEmptyException e) {
      throw new CommandFailedException(List.of("  " + e.getMessage()));
    }

    final JiraResolution jira = report.resolution();
    System.out.printf("%n  resolved   : %,d assignee ids%n", jira.records().size());
    System.out.printf("  fetched    : %,d issues%n", jira.issuesFetched());
    System.out.printf("  from cache : %,d%n", jira.issuesFromCache());
    System.out.printf("  failed     : %,d%n", jira.issuesFailed());
    System.out.printf("  wrote %,d identities to %s%n",
        report.identitiesWritten(), registry.describeIdentities());
    CanonicalPrinter.print(report.canonical(), registry.describeCanonical());
  }
}
