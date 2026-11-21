package app.cli;

import app.cli.commands.BuildLedgerCommand;
import app.cli.commands.CheckCommand;
import app.cli.commands.ComplexityCommand;
import app.cli.commands.FunnelCommand;
import app.cli.commands.HarvestJiraCommand;
import app.cli.commands.IssuesDownloadModelCommand;
import app.cli.commands.IssuesInProgressExtractCommand;
import app.cli.commands.IssuesInProgressTeamCommand;
import app.cli.commands.IssuesRetrospectiveCommand;
import app.cli.commands.IssuesSimilarityCommand;
import app.cli.commands.IssuesSimilarityElasticNetCommand;
import app.cli.commands.IssuesSimilarityInnerReportCommand;
import app.cli.commands.ReconstructPeopleCommand;
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

  ISSUES_FIRST_COMMIT_AUDIT("issues-first-commit-audit",
      new app.cli.commands.IssuesFirstCommitAuditCommand(), """
      Read only: the first-commit population and its split, without the
      outer test. The issues resolved as Fixed at their first resolution,
      created before their first commit that counts (human, authored before
      the resolution, not a merge, pull-request originals included), with at
      least two commits once duplicates are gone, the first commit below 50%
      of the changed lines, code on at least two dates, no commit linked to
      another issue and a target of at least one business day. Draws the
      revised temporal split over them, learns the cap on the waits between
      commits on the inner fitting, and reports the funnel, the cuts, each
      side's target and projects, the crossing issues, the limits of 30% and
      70% and the censoring at the end of the data. Of the outer test only
      counts and dates. Writes audit.md and per-issue.tsv; nothing to any
      database. WAIT_FENCE_K sets the fence of the cap (Q3 + k IQR; 1.5)."""),

  ISSUES_FIRST_COMMIT_SUPPORTING_NUMBERS("issues-first-commit-supporting-numbers",
      new app.cli.commands.IssuesFirstCommitSupportingNumbersCommand(), """
      Read only: the numbers behind the text, over the audit's base. The
      issues created at or after their first commit, those whose first
      commit comes before their first In Progress, and those with a single
      commit, each over its own denominator; then three worked examples from
      the outer training issues that teach, never the test. Writes
      filter-counts.md, worked-example.md and worked-example.tsv; nothing to
      any database."""),

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

  COMPLEXITY("complexity", new ComplexityCommand(), """
      Build `commit_complexity` -- the McCC and NL a commit's changed methods
      carry, summed once per commit. This is the expensive stage: three
      collections per commit over about 250,000 of them. Resumable, so an
      interrupted run continues rather than restarting. Needs funnel and
      build-ledger first.""",
      Corpus.Stage.COMPLEXITY),

  ISSUES_IN_PROGRESS_EXTRACT("issues-in-progress-extract", new IssuesInProgressExtractCommand(), """
      The first-commit population, its revised temporal split, the cap on
      the waits learned on the inner fitting and applied to every issue, the
      target and its parts (the FC_* columns), and every registry column read
      at the first commit that counts, with time travel checked at that
      instant. Writes the matrix only if the gate passed and records the run,
      with the cuts, the crossing issues and the new projects, in the revised
      design's collection of ISSUES_DB. Prints of the target the outer
      training's alone. WAIT_FENCE_K sets the fence of the cap."""),

  ISSUES_IN_PROGRESS_TEAM("issues-in-progress-team", new IssuesInProgressTeamCommand(), """
      Runs the extraction and writes only the four revised team columns
      (median and spread of lines of code and of cyclomatic complexity across
      the team), read at the first commit, one line per issue, for the
      similarity stage to join. Refuses when the extraction's time-travel gate
      fails. Records the run."""),

  ISSUES_DOWNLOAD_MODEL("issues-download-model", new IssuesDownloadModelCommand(), """
      Downloads the sentence-embedding model, all-MiniLM-L6-v2 at its pinned
      Hugging Face revision, into MODEL_DIR, and refuses any file whose
      SHA-256 is not the pinned one; a file already there with that hash is
      kept. Reads neither the corpus nor MongoDB, so it may run at any time."""),

  ISSUES_SIMILARITY("issues-in-progress-similarity", new IssuesSimilarityCommand(), """
      Adds the team columns and the description similarity medians to the
      extracted matrix: the median business days of the 5, 10 and 20 issues
      whose descriptions at the first commit share the most words (Jaccard),
      of the 5, 10 and 20 closest by sentence embedding, of the K-means
      cluster and of the reporter's other issues, once for the inner split and
      once for the outer. Every history is point in time: only the training
      issues that teach, first resolved strictly before the row's first
      commit, never the row's own; the outer test also answers in the inner
      columns. Refuses unless every row's split is the revised rule's, the
      team file holds exactly the matrix's issues and the model reproduces
      the reference on six sentences. Keeps the embeddings in
      EMBEDDING_CACHE; records the run."""),

  ISSUES_RETROSPECTIVE("issues-in-progress-retrospective", new IssuesRetrospectiveCommand(), """
      RETROSPECTIVE SCENARIO: adds nine attributes observed from the first
      commit to the first resolution, which describe the work as it was done
      and grow with the duration: an association, not a prediction. From the
      implementation commits: Java lines added and removed, mean cyclomatic
      complexity and nesting of the methods changed, distinct files, and the
      share of files an author had touched before; in the window, bots left
      out: comments, participants and assignee changes; and the release lag
      of the fix version. The first commit's size and share are copied.
      Refuses unless the matrix is the similarity stage's and every row's
      split is the revised rule's. Records the run with each attribute's
      coverage on the inner sides."""),

  ISSUES_SIMILARITY_ELASTIC_NET("issues-in-progress-similarity-elastic-net",
      new IssuesSimilarityElasticNetCommand(), """
      Every choice, on the inner validation only: the tail cut's k on the own
      start model (1.5, 3, 5, 10 or no cut), then alpha and lambda for six
      Elastic Net models (the own start model, Al-Zubaidi and Kikas at the
      start, the retrospective model in its main reading and its ceiling, and
      Kikas's retrospective set), the Jaccard n, embedding n and cluster k of
      the three with the similarity medians, a random forest beside each
      (500 trees), and the analogy estimate's similarity median, the
      fifteenth model; every choice scored on the usual-flow validation
      issues. The outer test rows are not read. Writes the parameters file,
      never over another step's; records the run."""),

  ISSUES_SIMILARITY_INNER_REPORT("issues-in-progress-similarity-inner-report",
      new IssuesSimilarityInnerReportCommand(), """
      INNER REPORT, read only and optimistic, the settings having been chosen
      on this same inner validation: refits the fifteen models on the inner
      fitting rows and scores every inner validation issue (the main
      reading) and the usual flow: errors, gains over the medians, ordering,
      error bands, paired comparisons and ordering intervals resampling whole
      projects, prediction intervals calibrated on half of the validation
      projects and checked on the other, relevance, uncertainty, the ten
      largest errors of the own retrospective model, and every table again
      without new projects. Writes only files."""),

  ISSUES_FIRST_COMMIT_SHARE_ABLATION("issues-first-commit-share-ablation",
      new app.cli.commands.IssuesFirstCommitShareAblationCommand(), """
      SHARE ABLATION, read only, a diagnostic outside the declared models on
      the inner validation: the main reading of the own retrospective model
      chosen twice by the selection's procedure, with and without the first
      commit's share of the issue's lines, and compared with intervals
      resampling whole projects. Writes share-ablation.md and .tsv; nothing
      to any database."""),

  ISSUES_FIRST_COMMIT_PREDICTED_BAND("issues-first-commit-predicted-band",
      new app.cli.commands.IssuesFirstCommitPredictedBandCommand(), """
      ERROR BY BAND OF THE PREDICTED DURATION, read only: describes the outer
      test's frozen predictions of the three Elastic Net start models, each
      issue in the band of its prediction (the quartiles of the cut fitting
      rows, as the test's report gives them) beside the bands by observed
      duration; for the long band and the risk band (the quarter of the
      highest predictions), predicted long against observed long. Reads the
      test's predictions and its log (test.log); writes predicted-band.md and
      .tsv; nothing to any database."""),

  ISSUES_FIRST_COMMIT_TEST("issues-first-commit-test",
      app.cli.commands.IssuesSimilarityTestCommand.firstCommit(), """
      OPENS AND SCORES THE OUTER TEST, ONCE: the fifteen models refitted on
      the outer training rows with the parameters file's choices (nothing is
      chosen here) and scored on every outer test issue (the main reading)
      and on the usual flow, with the paired comparisons, ordering intervals,
      prediction intervals (the approximation; the official ones are the
      same-model step's), relevance, uncertainty and every reading without
      new projects. Refuses unless the matrix and the parameters are the
      revised design's, the extraction and similarity runs declare it, and
      ISSUES_DB holds no opening yet. Records the run with the time opened
      and the SHA-256 of every input and output."""),

  ISSUES_FIRST_COMMIT_SAME_MODEL_CALIBRATION("issues-first-commit-same-model-calibration",
      new app.cli.commands.IssuesFirstCommitSameModelCalibrationCommand(), """
      SAME-MODEL CALIBRATION: the official prediction intervals, chosen
      before the outer test was opened and run right after it. The inner
      models, fitted once on the inner fitting rows, predict every inner
      validation issue and the outer test, and their own errors on the
      validation give the factors; beside them, the test step's
      approximation. Coverage with its interval resampling whole projects,
      width and factor per model and level. Refuses unless ISSUES_DB holds the
      opening and the outer models refitted reproduce the test's
      predictions. Writes same-model-calibration.md and .tsv; records the
      run.""");

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
