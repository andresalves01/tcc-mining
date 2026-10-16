package app.cli;

import app.cli.commands.BuildLedgerCommand;
import app.cli.commands.CanonicalizeCommand;
import app.cli.commands.CheckCommand;
import app.cli.commands.ComplexityCommand;
import app.cli.commands.FeaturesCommand;
import app.cli.commands.ExperimentsCommand;
import app.cli.commands.FitCommand;
import app.cli.commands.FitInnerCommand;
import app.cli.commands.FunnelCommand;
import app.cli.commands.HarvestJiraCommand;
import app.cli.commands.IssuesExtractCommand;
import app.cli.commands.IssuesCheckCommand;
import app.cli.commands.IssuesDescriptionLanguageCommand;
import app.cli.commands.IssuesDownloadModelCommand;
import app.cli.commands.IssuesForestCommand;
import app.cli.commands.IssuesGridCommand;
import app.cli.commands.IssuesInProgressCheckCommand;
import app.cli.commands.IssuesInProgressExtractCommand;
import app.cli.commands.IssuesInProgressGridCommand;
import app.cli.commands.IssuesInProgressReservedCommand;
import app.cli.commands.IssuesInProgressTeamCommand;
import app.cli.commands.IssuesReservedCommand;
import app.cli.commands.IssuesSimilarityCommand;
import app.cli.commands.IssuesSimilarityElasticNetCommand;
import app.cli.commands.IssuesSimilarityTestCommand;
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

  EXPERIMENTS("experiments", new ExperimentsCommand(), """
      Print one row per held-out observation, per population, with the
      Elastic Net and gradient-boosting predictions and the three
      references. Hyperparameters are chosen on the inner split only.
      Reads only, writes nothing -- redirect the output. Needs features."""),

  ISSUES_EXTRACT("issues-extract", new IssuesExtractCommand(), """
      Issue duration: build the three populations (Fixed, the main one;
      broad; inherited) and every column in both scenarios, and run the
      gates -- the thesis split and time travel. Writes the cache to
      CACHE only if every gate passed; records a summary in ISSUES_DB; reads everything else."""),

  ISSUES_GRID("issues-grid", new IssuesGridCommand(), """
      Issue duration: sweep the eleven cells of each population with
      Elastic Net on the inner selection. Writes the chosen parameters to
      PARAMETERS; reads CACHE; records the run in ISSUES_DB. The outer test
      rows are refused."""),

  ISSUES_CHECK("issues-check", new IssuesCheckCommand(), """
      Issue duration: every reading of the plan -- references, project-
      resampled intervals, bands of the true duration, C2 by block, the
      sensitivities -- on the inner selection with the parameters in
      PARAMETERS. Optimistic by construction; the outer test is not read."""),

  ISSUES_IN_PROGRESS_EXTRACT("issues-in-progress-extract", new IssuesInProgressExtractCommand(), """
      Issue duration from the first In Progress: the FIXED issues read at
      that instant, the split on it and time travel. Writes the cache to
      CACHE only if the gate passed; records a summary in ISSUES_DB;
      reads everything else."""),

  ISSUES_IN_PROGRESS_GRID("issues-in-progress-grid", new IssuesInProgressGridCommand(), """
      Issue duration from the first In Progress: sweep the six cells on the
      inner selection. Writes PARAMETERS; reads CACHE; records the run in
      ISSUES_DB. The outer test rows are refused."""),

  ISSUES_IN_PROGRESS_CHECK("issues-in-progress-check", new IssuesInProgressCheckCommand(),
      """
      Issue duration from the first In Progress: every reading on the inner
      selection with the parameters in PARAMETERS, the main reference chosen
      among three (P12). Optimistic by construction; the outer test is not
      read."""),

  ISSUES_FOREST("issues-forest", new IssuesForestCommand(), """
      Issue duration: a random forest beside the Elastic Net, cell by cell, on
      the inner selection only, with the forest's declared grid and the
      linear model's PARAMETERS. Reads CACHE; POPULATIONS as issues-grid;
      records the run in ISSUES_DB. The outer test rows are refused."""),

  ISSUES_RESERVED("issues-reserved", new IssuesReservedCommand(), """
      Issue duration: the one scoring of the outer test that
      pre-registration lays out. Refuses unless the matrix and the
      parameters match their registered SHA-256 and every side has its
      registered size; then refits each cell on the whole outer
      training side (lambda scaled by decision 20), prints the weights and
      scores the test, all three populations. Reads CACHE and PARAMETERS;
      writes PREDICTIONS; records the run in ISSUES_DB."""),

  ISSUES_IN_PROGRESS_RESERVED("issues-in-progress-reserved",
      new IssuesInProgressReservedCommand(), """
      Issue duration from the first In Progress: the one scoring of the outer
      test that its pre-registration lays out. Refuses unless the matrix and the parameters match their
      registered SHA-256 and every side has its registered size; then
      refits the eleven cells on the whole outer training side (lambda
      scaled by the ratio of rows), prints the weights and scores the test.
      Reads CACHE and PARAMETERS; writes PREDICTIONS; records the run in
      ISSUES_DB."""),

  ISSUES_DESCRIPTION_LANGUAGE("issues-description-language",
      new IssuesDescriptionLanguageCommand(), """
      Issue duration from the first In Progress: the language of each
      description as it stood at that instant, cleaned of Jira markup, with
      code and log blocks removed before detection (Lingua, all languages).
      Writes the count per language, the count with no description and 20
      non-English descriptions drawn with a fixed seed to REPORT_DIR. Reads
      the corpus; records nothing in Mongo, so it may run at any time."""),

  ISSUES_IN_PROGRESS_TEAM("issues-in-progress-team", new IssuesInProgressTeamCommand(), """
      Issue duration from the first In Progress, description-similarity
      chain: runs the in-progress extraction and writes only the revised team
      columns (median and spread of lines of code and of cyclomatic
      complexity across the team) to TEAM_COLUMNS, one line per issue, so the
      registered matrix is never rewritten. Refuses when the extraction's
      time-travel gate fails; that gate checks the registered columns, not
      these four. Records the run in ISSUES_DB; reads everything else."""),

  ISSUES_DOWNLOAD_MODEL("issues-download-model", new IssuesDownloadModelCommand(), """
      Issue duration from the first In Progress, description-similarity
      chain: downloads the sentence-embedding model, all-MiniLM-L6-v2 at
      its pinned Hugging Face revision (the safetensors weights, the
      configuration and the tokenizer files), into MODEL_DIR, and refuses
      any file whose SHA-256 is not the pinned one. The embedder, plain
      Java with no native code, checks the hashes again at every load. A
      file already there with the pinned hash is kept. Reads neither the corpus
      nor Mongo and records nothing, so it may run at any time."""),

  ISSUES_SIMILARITY("issues-in-progress-similarity", new IssuesSimilarityCommand(), """
      Issue duration from the first In Progress, description-similarity
      chain: adds to the extracted matrix the revised team columns from
      TEAM_COLUMNS, each issue's day boundary (the UTC hour its working
      dates turn at: the middle of the quietest eight hours of the person
      who moved it to In Progress, read from their tracker activity before
      that move, with at least 10 active hours; else the start of the
      project's quietest eight hours, with at least 30 person-hours; else
      04:00 UTC; bot accounts and ProjectImport events left out), and the
      median business days of the 5, 10 and 20 issues whose descriptions
      at their first In Progress share the most words (Jaccard), of the 5,
      10 and 20 closest by sentence embedding, of the K-means cluster and
      of the reporter's earlier issues, once against the inner split and
      once against the outer, history built in five time blocks per
      fitting set. A business day is a Monday-to-Friday date reached after
      the start's date, holidays ignored; a past issue of 0 business days
      enters no history. Refuses unless the four
      split sizes are the registered study's and TEAM_COLUMNS holds
      exactly the matrix's issues, and unless the model in MODEL_DIR
      reproduces the sentence-transformers reference on six sentences.
      Reads CACHE, TEAM_COLUMNS, the model and the corpus; keeps the
      embeddings in EMBEDDING_CACHE;
      writes MATRIX and the diagnostics to REPORT_DIR; records the run in
      ISSUES_DB. Leaves the registered in-progress chain untouched."""),

  ISSUES_SIMILARITY_ELASTIC_NET("issues-in-progress-similarity-elastic-net",
      new IssuesSimilarityElasticNetCommand(), """
      Issue duration from the first In Progress, description-similarity
      chain, in whole business days (Monday-to-Friday dates reached from
      the first In Progress to the first resolution, at each issue's day
      boundary, holidays ignored), on the issues of at least one business
      day: chooses alpha and lambda on the inner validation, by the
      registered grid's sweep, for six Elastic Net models: the own
      specification without similarity (description length, team size, the
      team's median and spread of lines of code and complexity, the
      corrected reporter median, the executor's lines of code, complexity
      and open workload), the similarity medians alone, the own
      specification with similarity (those ten and the three medians),
      Al-Zubaidi and Kikas at the start (the registered cells), and the
      literature families with the own specification with similarity. The
      three models with the similarity medians are swept once for each of
      the 27 combinations of Jaccard n (5, 10, 20), embedding n (5, 10, 20)
      and cluster k (8, 16, 32), and keep the combination whose sweep errs
      least; exact ties go to the smaller Jaccard n, then embedding n, then
      cluster k. As a robustness check, not a new contribution, a random
      forest is swept beside each model on the same rows and every column
      its Elastic Net kept, weighted or not, the similarity medians at the
      chosen n and k (300 trees, depth 20, column share 1/6, 1/3 or 2/3,
      leaf 5, 25 or 100, seed 20260927); the lowest inner validation error
      is kept, exact ties going to the first point of the grid. Prints each
      model's choice and, for those three, the best error of every
      combination, and each forest's grid. The split-dependent columns are
      read in their inner variant; a column missing on every inner fitting row is
      removed from its models and reported. Refuses unless the four split
      sizes are the registered study's and the matrix has every declared
      column and the day boundary. Reads MATRIX; writes
      ELASTIC_NET_PARAMETERS, the forests' column share and leaf among them,
      never over a file holding more than its own choices, one naming other
      models, as one written before the own specification took the team
      size back and left the previous one out, or one written before the
      forests or for the hours; records the run in ISSUES_DB. The
      outer test rows are not read."""),

  ISSUES_SIMILARITY_TEST("issues-in-progress-similarity-test",
      new IssuesSimilarityTestCommand(), """
      Issue duration from the first In Progress, description-similarity
      chain, outer mode only: SCORES THE OUTER TEST, in whole business days
      (Monday-to-Friday dates reached, at each issue's day boundary,
      holidays ignored) on the issues of at least one business day, those
      of 0 dropped from the outer training and test. Reads MATRIX and
      ELASTIC_NET_PARAMETERS and chooses nothing: refits every Elastic Net
      model on the outer training set with its chosen alpha and the chosen
      lambda times the ratio of rows, as the registered reserved run does,
      the similarity medians at the Jaccard n, embedding n and cluster k
      chosen for each model; refits beside each, as a robustness check, its
      random forest with the chosen count of columns per split and leaf; and with the
      global, project and executor-project medians scores the outer test:
      error in business days, with the slowest 10% left out too, Spearman,
      pairwise ordering, error by band of the true business days (1, 2-3,
      4-10, 11+; nothing clipped), and the agreed paired comparisons with
      intervals resampling whole projects: the own specification with
      similarity against the same without, every model against the global
      median and every model but the global median against the project
      median; each forest against its Elastic Net, the forest with
      similarity against the forest without, and every forest against the
      global and the project median. Refuses unless the parameters file
      exists and is for these models with a forest for each, its choices
      were made on that matrix, the four split sizes are the registered
      study's and every column is present. Writes the per-issue
      predictions, the diagnostics and each forest's permutation
      importance to REPORT_DIR; records the run in ISSUES_DB. States that
      the earlier study already examined this test."""),

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
