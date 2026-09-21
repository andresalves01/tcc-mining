package app.cli;

import app.cli.commands.BuildLedgerCommand;
import app.cli.commands.CanonicalizeCommand;
import app.cli.commands.CheckCommand;
import app.cli.commands.ComplexityCommand;
import app.cli.commands.FeaturesCommand;
import app.cli.commands.FitCommand;
import app.cli.commands.FunnelCommand;
import app.cli.commands.HarvestJiraCommand;
import app.cli.commands.ReconstructPeopleCommand;
import app.cli.commands.SeniorityCommand;
import app.cli.commands.SplitCommand;
import app.cli.commands.StatsCommand;
import app.pipeline.port.Corpus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every command the CLI accepts, each bound to the string that selects it, the
 * runner that performs it, the help text that describes it and the stage of the
 * corpus it needs.
 *
 */
public enum Command {

  CHECK("check", new CheckCommand(), """
      Report what the source corpus holds before a stage spends an hour on it:
      the collections each command reads, whether they are populated, and
      whether this is the full release or the small one."""),

  RECONSTRUCT_PEOPLE("reconstruct-people", new ReconstructPeopleCommand(), """
      Rebuild the git half of the registry from the repositories bundled
      in the `repository_data` GridFS bucket, joining on
      commit.revision_hash. Aborts if the join is not a bijection.""",
      Corpus.Stage.RECONSTRUCT_PEOPLE),

  HARVEST_JIRA("harvest-jira", new HarvestJiraCommand(), """
      Resolve Jira assignee ids against the public changelog API, joining
      on event.external_id. Cached on disk, so re-runs cost nothing.
      Run reconstruct-people first.""",
      Corpus.Stage.HARVEST_JIRA),

  CANONICALIZE("canonicalize", new CanonicalizeCommand(), """
      Recompute the equivalence relation from the current registry. Cheap:
      use it to compare MERGE_STRICTNESS settings without re-mining."""),

  BUILD_LEDGER("build-ledger", new BuildLedgerCommand(), """
      Build `developer_contribution` -- one row per non-merge commit with
      its canonical author and the lines it carried. Reads `file_action`
      and `commit`; needs reconstruct-people to have run first.""",
      Corpus.Stage.BUILD_LEDGER),

  FUNNEL("funnel", new FunnelCommand(), """
      Build the global funnel: apply every exclusion in order, extract
      the changed methods of the survivors, and write `issue_label`.
      Needs build-ledger to have run first.""",
      Corpus.Stage.FUNNEL),

  SPLIT("split", new SplitCommand(), """
      Cut the labels into past and future at the TRAIN_SHARE quantile of
      issue creation, with an inner split inside the training side for
      choosing regularisation. Records the assignment on each label."""),

  COMPLEXITY("complexity", new ComplexityCommand(), """
      Build `commit_complexity` -- the McCC and NL a commit's changed methods
      carry, summed once per commit. This is the expensive stage: three
      collections per commit over about 250,000 of them. Resumable, so an
      interrupted run continues rather than restarting. Needs funnel and
      build-ledger first; run `features` again afterwards.""",
      Corpus.Stage.COMPLEXITY),

  FEATURES("features", new FeaturesCommand(), """
      Build `issue_features` -- one predictor row per label, every quantity
      read as of the issue's creation. Reads the labels and the ledger back,
      so it is cheap to re-run when a definition changes. Needs funnel and
      build-ledger to have run first.""",
      Corpus.Stage.FEATURES),

  FIT("fit", new FitCommand(), """
      Fit the Elastic Net on the training side and score it against the
      references the study names. Chooses alpha and lambda on the inner
      split. Reads only, writes nothing, and cannot read the held-out
      test set -- there is no code path to it. Needs features first."""),

  SENIORITY("seniority", new SeniorityCommand(), """
      Report seniority `q` and the cost curve over the ledger, under both
      the project and the career scope. Reads only, writes nothing."""),

  STATS("stats", new StatsCommand(), "Summarise what the registry holds.");

  private static final Map<String, Command> CommandMap = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(command -> command.token, command -> command));

  private final String token;
  private final CommandRunner runner;
  private final String help;
  private final Corpus.Stage stage;

  Command(final String token, final CommandRunner runner, final String help) {
    this(token, runner, help, null);
  }

  Command(final String token, final CommandRunner runner, final String help,
      final Corpus.Stage stage) {

    this.token = token;
    this.runner = runner;
    this.help = help;
    this.stage = stage;
  }

  /**
   * The stage of the corpus this command needs, empty when it reads none of it.
   */
  public Optional<Corpus.Stage> stage() {
    return Optional.ofNullable(stage);
  }

  /** The command as typed on the command line. */
  public String token() {
    return token;
  }

  /** What the command does, as one paragraph of prose. */
  String help() {
    return help;
  }

  /**
   * Refuses a stage the corpus cannot serve, then performs the command.
   */
  public void run(final Context context) throws Exception {
    final List<String> problems = stage()
        .map(needed -> context.corpus().whyCannotRun(needed))
        .orElseGet(List::of);
    if (!problems.isEmpty()) {
      throw new CommandFailedException(Stream.concat(problems.stream(),
          Stream.of("  Run `" + CHECK.token + "` for the whole picture.")).toList());
    }
    runner.run(context);
  }

  /** The command selected by {@code token}, or empty if nothing matches it. */
  public static Optional<Command> parse(final String token) {
    return Optional.ofNullable(CommandMap.get(token));
  }
}
