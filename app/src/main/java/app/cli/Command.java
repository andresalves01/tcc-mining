package app.cli;

import app.cli.commands.BuildLedgerCommand;
import app.cli.commands.CanonicalizeCommand;
import app.cli.commands.CheckCommand;
import app.cli.commands.ComplexityCommand;
import app.cli.commands.FeaturesCommand;
import app.cli.commands.FitCommand;
import app.cli.commands.FitInnerCommand;
import app.cli.commands.FunnelCommand;
import app.cli.commands.HarvestJiraCommand;
import app.cli.commands.Paper5AblationCommand;
import app.cli.commands.Paper5AuditCommand;
import app.cli.commands.Paper5CorrectedReservedCommand;
import app.cli.commands.Paper5CorrectedSweepCommand;
import app.cli.commands.Paper5CorrectionCommand;
import app.cli.commands.Paper5TemporalAuditCommand;
import app.cli.commands.Paper5CheckCommand;
import app.cli.commands.Paper5ExtractCommand;
import app.cli.commands.Paper5FitInnerCommand;
import app.cli.commands.Paper5ReservedCommand;
import app.cli.commands.Paper5PopulationCommand;
import app.cli.commands.PullRequestFeaturesCommand;
import app.cli.commands.PullRequestLabelsCommand;
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

  PR_LABELS("pr-labels", new PullRequestLabelsCommand(), """
      Artigo 4: build `issue_label` from merged pull requests (opened to
      merged) instead of resolved issues, keyed on the single commit
      author. Reads pull_request* from SOURCE_DB and the identity
      registry from LEDGER_DB -- writes only to TARGET_DB. Run `split`
      afterwards, same as after `funnel`."""),

  PR_FEATURES("pr-features", new PullRequestFeaturesCommand(), """
      Artigo 4: build `issue_features` for the pull-request labels.
      BuildFeaturesTask itself, unmodified -- only the databases behind
      it differ: the ledger and complexity table are read from
      LEDGER_DB, never written to. Needs pr-labels and split first."""),

  FIT_INNER("fit-inner", new FitInnerCommand(), """
      Fit and score against the inner selection only -- never calls
      FitModelTask.heldOut, no code path to it at all. For changing a
      definition and re-scoring freely while a specification is still
      being worked out. Needs features first."""),

  FIT("fit", new FitCommand(), """
      Fit the Elastic Net on the training side and score it against the
      references the study names. Chooses alpha and lambda on the inner
      split. Reads only, writes nothing. Reaches the held-out set every
      time it runs -- nothing in this command or in FitModelTask stops a
      second invocation with a changed definition upstream of it from
      opening it again under different terms. Needs features first."""),

  PAPER5_POPULATION("paper5-population", new Paper5PopulationCommand(), """
      Artigo 5: print the population of the Yu/Chouchen replication --
      every partition, with projects and distinct creator_id. Reads
      UNITS_DB (the frozen merged pull requests) and SOURCE_DB, and
      writes nothing. MIN_RESOLVED=0 turns the inherited-history rule
      off, which is this study's frozen population."""),

  PAPER5_EXTRACT("paper5-extract", new Paper5ExtractCommand(), """
      Artigo 5: compute every pre-merge construct for the whole
      population and print coverage, with the temporal class of each
      one beside it. Reads UNITS_DB and SOURCE_DB, writes nothing.
      With ORACULO=<path> it also diffs the result against the
      reference extraction the validation produced in Python."""),

  PAPER5_FIT_INNER("paper5-fit-inner", new Paper5FitInnerCommand(), """
      Artigo 5: fit every cell of the grid -- three families by three
      profiles, plus the literature profile without area_hotness_90d --
      with Elastic Net and Extra Trees, scored against the inner
      selection of the training side. Reports MAE in hours and days,
      the gap against the mandatory baseline, and Spearman. Reads the
      file CACHE points at; writes nothing. This is the development
      stage: the reserved set is reached only by paper5-reservado."""),

  PAPER5_CHECK("paper5-conferencia", new Paper5CheckCommand(), """
      Artigo 5: measure the five frozen specifications on the inner
      selection, with the alphas and lambdas from the registration and
      no search -- five fits instead of sixteen hundred. Reports the
      pairwise ordering accuracy and whether the table's order survives
      resampling. Reads CACHE and A4; writes nothing; the reserved set
      is not reached."""),

  PAPER5_ABLATION("paper5-ablacao", new Paper5AblationCommand(), """
      Artigo 5: strip the size of the change out of each specification
      and put what is left against a model that is only the size --
      lines changed, added plus deleted. Development side; the reserved
      partition is not reopened. Reads CACHE and A4, writes nothing."""),

  PAPER5_RESERVED("paper5-reservado", new Paper5ReservedCommand(), """
      Artigo 5: score the reserved partition ONCE, against the
      specification frozen in REGISTRO-PREVIO.md before this command
      existed. Refits the five specifications on the whole training
      side with the alphas and lambdas the registration fixed, reports
      what each variable weighed, and only then scores the reserved
      rows. Searches for nothing. Reads CACHE and A4."""),

  PAPER5_AUDIT("paper5-auditoria", new Paper5AuditCommand(), """
      Artigo 5, auditoria de 28/09/2026: reruns the registered reserved
      task unchanged, proves a second fitting path identical to it bit
      for bit, writes every reserved prediction to PREVISOES, and prints
      the post-opening readings -- per-project ordering, project-level
      intervals, the historical project mean, and the families without
      the columns that read the future. Searches for nothing. Reads
      CACHE and A4; appends the audit to PAPER5_DB."""),

  PAPER5_TEMPORAL_AUDIT("paper5-auditoria-temporal", new Paper5TemporalAuditCommand(), """
      Artigo 5, auditoria de 28/09/2026: for every unit, how many of the
      pull requests behind the project's typical wait and the author's
      file familiarity were examined or merged at or after t0, and
      whether the value changes using only what was known. Reads
      UNITS_DB and SOURCE_DB; writes the counts to SAIDA, nothing else."""),

  PAPER5_CORRECTION("paper5-correcao-a4", new Paper5CorrectionCommand(), """
      Artigo 5, correction of 28/09/2026: recomputes the previous paper's
      columns under the registered rule and the corrected one, requires
      the registered rule to reproduce the stored export A4, and writes
      the corrected export to SAIDA. Reads SOURCE_DB, LEDGER_DB and
      UNITS_DB; writes only the file."""),

  PAPER5_CORRECTED_SWEEP("paper5-varredura-corrigida", new Paper5CorrectedSweepCommand(), """
      Artigo 5, correction of 28/09/2026: the development sweep, on the
      inner selection, for the two families with the previous paper's
      columns -- first proving it reproduces the registered choice with
      the registered columns, then with the corrected ones. Reads CACHE,
      A4 and A4_CORRIGIDO; writes nothing; the reserved set is not read."""),

  PAPER5_CORRECTED_RESERVED("paper5-reservado-corrigido", new Paper5CorrectedReservedCommand(), """
      Artigo 5, correction of 28/09/2026: scores the reserved rows ONCE
      with the corrected columns and the pairs frozen in the addendum of
      REGISTRO-DA-CORRECAO.md, beside the three unaffected registered
      specifications. Reads CACHE, A4, A4_CORRIGIDO; writes PREVISOES and
      appends to PAPER5_DB."""),

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
