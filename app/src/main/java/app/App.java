package app;

import app.cli.Command;
import app.cli.CommandFailedException;
import app.cli.ConsoleProgress;
import app.cli.Context;
import app.cli.Help;
import app.config.PipelineConfig;
import app.model.Columns;
import app.pipeline.jira.JiraChangelogSource;
import app.pipeline.port.Progress;
import app.pipeline.mongo.GridFsGitIdentitySource;
import app.pipeline.mongo.MongoContributionLedger;
import app.pipeline.mongo.MongoContributionSource;
import app.pipeline.mongo.MongoCommitComplexity;
import app.pipeline.mongo.MongoCommitComplexityStore;
import app.pipeline.mongo.MongoCorpusCheck;
import app.pipeline.mongo.MongoFeatureStore;
import app.pipeline.mongo.MongoIssueAttributes;
import app.pipeline.mongo.MongoLabelSource;
import app.pipeline.mongo.MongoLabelStore;
import app.pipeline.mongo.MongoObservations;
import app.pipeline.mongo.MongoPersonRegistry;
import app.pipeline.mongo.MongoPullRequestAttributes;
import app.pipeline.mongo.MongoPullRequestExtras;
import app.pipeline.mongo.MongoPullRequestPresence;
import app.pipeline.mongo.MongoPullRequestSource;
import app.pipeline.mongo.MongoTableSource;
import app.pipeline.mongo.MongoUnitSource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Optional;

/**
 * Command-line entry point, and the one place that decides which adapters the run uses.
 *
 * <p>Deliberately thin otherwise: it selects a command, resolves the configuration, opens the one
 * Mongo client the run shares, builds the ports on top of it and hands them over. No orchestration,
 * no printing of results and no domain logic live here, so the interesting behaviour stays testable
 * without a {@code main}.
 *
 * <p>Being the only class that names both a driver and an adapter is the point: below it, nothing
 * knows what the data is stored in.
 *
 * <p>The layering below it, outermost to innermost:
 *
 * <pre>
 *   app                  entry point -- argument selection, wiring, exit codes
 *   app.cli              one runner per command, printing, progress rendering
 *   app.pipeline         use cases  -- ReconstructPeople, HarvestJira, Canonicalize, ...
 *   app.pipeline.port    interfaces for the corpus itself and for reporting progress
 *   app.pipeline.mongo   adapters   -- GridFS + JGit + the commit join, registry persistence
 *   app.pipeline.jira    adapters   -- issue-tracker changelog client
 *   app.config           configuration, resolved once at startup
 *   app.identity.port    interfaces the use cases depend on
 *   app.identity         domain     -- pure; knows nothing of Mongo, HTTP, Spark or files
 *   app.cost             domain     -- pure; contributions, seniority, the cost curve
 *   app.labels           domain     -- pure; the funnel, the methods, the temporal split
 * </pre>
 */
public final class App {

  private static final int EXIT_FAILED = 1;
  private static final int EXIT_MISUSE = 2;

  public static void main(final String[] args) throws Exception {
    final String token = args.length == 0 ? "help" : args[0];
    if (Help.requestedBy(token)) {
      Help.print();
      return;
    }

    // Resolved before the configuration is read and before anything is dialled, so a typo costs
    // nothing and never reports itself as a configuration error.
    final Optional<Command> selected = Command.parse(token);
    if (selected.isEmpty()) {
      System.err.println("unknown command: " + token);
      Help.print();
      System.exit(EXIT_MISUSE);
      return;
    }
    final Command command = selected.get();

    final PipelineConfig config;
    try {
      config = PipelineConfig.fromEnvironment();
    } catch (final IllegalArgumentException e) {
      System.err.println("configuration error: " + e.getMessage());
      System.exit(EXIT_MISUSE);
      return;
    }

    // Antes de qualquer adaptador construir uma linha: o conjunto de colunas e propriedade da
    // execucao inteira, e MongoObservations le Columns.all() para dimensionar cada vetor.
    Columns.use(config.columnProfile());
    Columns.target(config.ttcTarget());

    System.out.println("=== " + command.token() + " ===");
    System.out.println("  " + config.describe()
        + (config.columnProfile() == Columns.Profile.ISSUE ? ""
            : "   colunas: " + config.columnProfile()));
    System.out.println();

    try (MongoClient client = MongoClients.create(config.mongoUri())) {
      command.run(wire(client, config));
    } catch (final CommandFailedException e) {
      e.lines().forEach(System.err::println);
      System.exit(EXIT_FAILED);
    }
  }

  /**
   * The composition root: every adapter the run can use, built once.
   *
   * <p>All of them are built for every command, which is affordable because each is a field
   * assignment over a client that is already open -- none of them reads anything until asked.
   */
  private static Context wire(final MongoClient client, final PipelineConfig config) {
    final Progress progress = new ConsoleProgress();
    return new Context(
        config,
        new MongoPersonRegistry(client, config.targetDb()),
        new GridFsGitIdentitySource(client, config.sourceDb(), config.workDir(), progress),
        new JiraChangelogSource(client, config.sourceDb(), config.jiraCacheDir(),
            config.jiraDelay(), progress),
        new MongoContributionSource(client, config.sourceDb(), progress),
        new MongoContributionLedger(client, config.targetDb()),
        new MongoLabelSource(client, config.sourceDb(), config.targetDb(), progress),
        new MongoLabelStore(client, config.targetDb()),
        new MongoIssueAttributes(client, config.sourceDb()),
        new MongoFeatureStore(client, config.targetDb()),
        new MongoCommitComplexity(client, config.sourceDb(), progress),
        new MongoCommitComplexityStore(client, config.targetDb()),
        new MongoObservations(client, config.targetDb(), config.ttcTarget()),
        new MongoCorpusCheck(client, config.sourceDb()),
        progress,
        new MongoPersonRegistry(client, config.ledgerDb()),
        new MongoContributionLedger(client, config.ledgerDb()),
        new MongoCommitComplexityStore(client, config.ledgerDb()),
        new MongoPullRequestSource(client, config.sourceDb()),
        new MongoPullRequestAttributes(client, config.sourceDb()),
        new MongoPullRequestPresence(client, config.sourceDb(), config.ledgerDb()),
        new MongoPullRequestExtras(client, config.sourceDb(),
            new MongoContributionLedger(client, config.ledgerDb()),
            new MongoPersonRegistry(client, config.ledgerDb())),
        new MongoUnitSource(client, config.unitsDb(), config.sourceDb()),
        new MongoTableSource(client, config.sourceDb(), progress));
  }

  private App() {}
}
