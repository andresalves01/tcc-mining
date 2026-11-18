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
import app.cli.commands.IssuesSimilarityTestCommand;
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

  ISSUES_ADJUSTED_DURATION("issues-in-progress-adjusted-duration",
      new app.cli.commands.IssuesAdjustedDurationCommand(), """
      Build a versioned two-phase target on frozen outer training rows only.
      Requires MATRIX, ADJUSTED_DIR, BOT_ACCOUNTS, START_DESCRIPTIONS and EMBEDDING_CACHE.
      ADJUSTED_STEP: evidence, audit, matrix, select, report, models, baseline-select or baseline-report.
      Preserves original partitions and target; no outer-test outcomes or database writes."""),

  ISSUES_FIRST_COMMIT_AUDIT("issues-first-commit-audit",
      new app.cli.commands.IssuesFirstCommitAuditCommand(), """
      Issue duration from the first commit: builds, read only, the population
      of issues resolved as Fixed at their first resolution, created before
      their first commit that counts (human, authored before the resolution,
      not a merge, pull-request originals included), with at least two
      commits once duplicates are gone (same author and instant; same first
      message line and changed lines at another instant, the oldest kept),
      the first commit below 50% of the changed lines of every file, code
      on at least two dates at the first author's day boundary, no commit
      linked to another issue, and a target of at least one business day.
      Draws the split with the rule SPLIT_RULE names over them (random by
      default, the In Progress rule), learns the cap on the waits between
      commits and patches on the inner fitting only, and counts the target
      of the outer training. Also reports the limits of 30% and 70% and the
      overlap with the In Progress split. Of the outer test only its size is
      written. With SPLIT_RULE=temporal it adds the cuts, the target of each
      training side, the projects of each side and the new ones, the issues
      that cross the cuts, the random rule's cap, and the censoring at the
      end of the data (dates and counts only). Writes audit.md and
      per-issue.tsv to AUDIT_DIR; writes nothing to any database and records
      no run."""),

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
      Issue duration from the first In Progress: the issues resolved as
      Fixed read at that instant, those of at least one active business day
      kept (the
      target's own count), the split drawn over them at random and
      stratified by project (per project in order of name, its issues by
      id shuffled with one java.util.Random(1738) shared across projects,
      the first round(0.7 n) training and the rest test; the same rule on
      the training side, the same Random continuing, for the inner fitting
      and validation; nothing set aside), and time travel. Writes the cache
      to CACHE only if the gate passed; records a summary, with the split's
      rule and seed, in ISSUES_DB; reads everything else.
      With POPULATION=FIRST_COMMIT, issue duration from the first commit
      instead: the population, split, cap and target of
      issues-first-commit-audit (the cap learned on the inner fitting and
      applied, frozen, to every drawn issue), the same columns read at the
      first commit that counts (the executor is the assignee then, or else
      the first commit's author), time travel at that instant, and the
      target and its parts as the FC_* columns, then the first commit's
      lines (FC_first_commit_lines, a start attribute) and its share of
      the issue's lines (FC_first_commit_share, a retrospective one); the
      pause flag, which reads the status, is left out. The cache's split columns
      are primeiro_commit_externa and primeiro_commit_interna. Refuses the
      In Progress study's TARGET_DB and ISSUES_DB before reading anything;
      give PREPARED_DB=smartshark_tcc to read the prepared collections. Of
      the target it prints and records the outer training's alone. It
      draws the rule SPLIT_RULE names (random by default); under temporal
      it records its run in ISSUES_DB's runs_temporal, with both cuts, the
      training issues crossing them and the new projects, and it refuses,
      before reading anything, a run collection holding another design's
      runs."""),

  ISSUES_IN_PROGRESS_TEAM("issues-in-progress-team", new IssuesInProgressTeamCommand(), """
      Issue duration from the first In Progress: runs the extraction and
      writes only the revised team columns (median and spread of lines of
      code and of cyclomatic complexity across the team) to TEAM_COLUMNS,
      one line per issue, which issues-in-progress-similarity joins to the
      extracted matrix. Refuses when the extraction's time-travel gate
      fails; that gate does not check these four. Records the run in
      ISSUES_DB; reads everything else.
      With POPULATION=FIRST_COMMIT, the columns of the first-commit
      population, read at the first commit that counts; refuses the In
      Progress study's TARGET_DB and ISSUES_DB before reading anything.
      Under SPLIT_RULE=temporal, records in runs_temporal and refuses a
      collection containing another design before reading the corpus."""),

  ISSUES_DOWNLOAD_MODEL("issues-download-model", new IssuesDownloadModelCommand(), """
      Issue duration from the first In Progress: downloads the
      sentence-embedding model, all-MiniLM-L6-v2 at
      its pinned Hugging Face revision (the safetensors weights, the
      configuration and the tokenizer files), into MODEL_DIR, and refuses
      any file whose SHA-256 is not the pinned one. The embedder, plain
      Java with no native code, checks the hashes again at every load. A
      file already there with the pinned hash is kept. Reads neither the corpus
      nor Mongo and records nothing, so it may run at any time."""),

  ISSUES_SIMILARITY("issues-in-progress-similarity", new IssuesSimilarityCommand(), """
      Issue duration from the first In Progress: adds to the extracted
      matrix the revised team columns from
      TEAM_COLUMNS, each issue's day boundary (the UTC hour its working
      dates turn at: the middle of the quietest eight hours of the person
      who moved it to In Progress, read from their tracker activity before
      that move, with at least 10 active hours; else the start of the
      project's quietest eight hours, with at least 30 person-hours; else
      04:00 UTC; bot accounts and ProjectImport events left out), and the
      median business days of the 5, 10 and 20 issues whose descriptions
      at their first In Progress share the most words (Jaccard), of the 5,
      10 and 20 closest by sentence embedding, of the K-means cluster and
      of the reporter's other issues, once against the inner split and
      once against the outer, each history the whole training side of its
      split whatever the dates (the inner fitting set; the outer
      training), one index and one cluster fit per k read by training and
      scored rows alike, no label of the other side and never the row's
      own. Under SPLIT_RULE=revised every history is point-in-time: only
      the training-side issues that teach, first resolved strictly before
      the row's first commit; the centres stay fitted on the whole side,
      and the outer test also answers in the _inner columns from the
      inner fitting history alone. A business day is a Monday-to-Friday
      date reached after the start's date, holidays ignored. An issue's
      business days leave out the time it was back in Open or Reopened
      between its first In Progress and its first resolution (from a
      status change to either until the next status change; Patch
      Available kept). Days without recorded activity are retained. This
      same duration goes to the matrix beside the day boundary and is
      read by every duration median and every later model. A past issue
      of 0 enters no history.
      Refuses unless every row's split is the one its rule
      draws and TEAM_COLUMNS holds exactly the matrix's issues, and unless
      the model in MODEL_DIR
      reproduces the sentence-transformers reference on six sentences.
      Reads CACHE, TEAM_COLUMNS, the model and the corpus; keeps the
      embeddings in EMBEDDING_CACHE;
      writes MATRIX and the diagnostics to REPORT_DIR; records the run in
      ISSUES_DB.
      A matrix of the first-commit population (its split columns
      primeiro_commit_*) is read at the first commit that counts: the
      descriptions there, and every median and history on the target the
      extraction wrote (FC_target_days), the training side's only and
      never the row's own. The status is not read and no day boundary or
      active business days are written. Refuses the In Progress study's
      TARGET_DB and ISSUES_DB, and a row without its target of at least
      one business day or the first commit's size and share."""),

  ISSUES_RETROSPECTIVE("issues-in-progress-retrospective", new IssuesRetrospectiveCommand(), """
      Issue duration from the first In Progress, RETROSPECTIVE SCENARIO: adds to the similarity stage's matrix
      nine attributes observed between the first In Progress and the
      first resolution. They describe the work as it was done, and the
      counts grow with the duration itself: a gain from them is association
      with the duration observed up to the resolution, not a prediction
      available during the work, nor causation. From the implementation
      commits (the issue's linked human commits visible before the first
      resolution, those before the first In Progress included): the Java
      lines added and removed, each commit's split among the issues it
      cites; the mean cyclomatic complexity and the mean nesting of the
      methods they changed, each method once at its latest state; the
      distinct files changed; and the share of those files one of their
      authors had touched in the project before the first In Progress, the
      issue's own commits left out -- all five missing without an
      implementation commit. In [first In Progress, first resolution), bot
      accounts left out: the comments, the distinct people who commented or
      changed a field (the reporter only if active) and the effective
      changes of assignee; the declared main specification leaves comments
      and participants to its ceiling reading. From the tags: urgency, the calendar
      days from the first resolution to the release of its fix version, a
      tag matched to the version's name, measured after the fact, missing
      without one. The wait on blocking issues is no longer written: it
      equalled the target in about half of the issues that had one.
      Refuses unless MATRIX is the similarity stage's,
      with its day boundary and active business days and without these
      columns, every row's split is the one its rule draws and the
      corpus holds every issue of the matrix as it is.
      Reads MATRIX, the corpus and the changed methods of the implementation
      commits (the expensive part); writes RETROSPECTIVE_MATRIX, never over
      MATRIX or a file that is not a retrospective matrix; records the run,
      with the coverage of each of the nine on the inner fitting and inner
      validation, in ISSUES_DB. The other matrices are left untouched.
      A matrix of the first-commit population is read from the first
      commit that counts to the first resolution: that commit replaces
      the first In Progress in every window above, and the first
      commit's size and share, written by the extraction, are copied as
      they are. Refuses the In Progress study's TARGET_DB and ISSUES_DB."""),

  ISSUES_SIMILARITY_ELASTIC_NET("issues-in-progress-similarity-elastic-net",
      new IssuesSimilarityElasticNetCommand(), """
      Issue duration from the first In Progress, in whole business days (Monday-to-Friday dates reached from the
      first In Progress to the first resolution, at each issue's day
      boundary, holidays ignored, the time back in Open or Reopened left
      out, days without recorded activity kept),
      on the issues of at least one business day: chooses alpha and
      lambda on the inner validation, by the Elastic Net sweep (alpha
      0.10, 0.50 or 0.95; twelve lambdas over four decades down from the
      penalty that zeroes every coefficient), for six Elastic Net models,
      six attribute sets. At the start: the own
      start model (description length, team size, the team's median and
      spread of lines of code and complexity, the corrected reporter
      median, the executor's lines of code, complexity and open workload,
      and the Jaccard, embedding and cluster similarity medians), and
      Al-Zubaidi and Kikas at the start (the literature sets). After the
      resolution: the retrospective scenario in its main reading, the own
      start model and, of the attributes issues-in-progress-retrospective
      adds, observed up to the resolution, the five of the implementation
      content and assignee changes, without release lag, comments or
      the participants, preserving the declared attribute specification; the
      same with the comments and the participants, a CEILING, not a
      competitor: its counts accumulate during the work; and Kikas's retrospective
      scenario, Kikas's retrospective set (Kikas at the start and the
      twelve counts observed up to the resolution, with their missing
      indicator), the literature counterpart of the retrospective model,
      which counts comments. The three models with the similarity medians
      are swept once for each of the 27 combinations of Jaccard n (5, 10,
      20), embedding n (5, 10, 20) and cluster k (8, 16, 32), and keep the
      combination whose sweep errs least; exact ties go to the smaller
      Jaccard n, then embedding n, then cluster k. As a robustness check,
      not a new contribution, a random forest is swept beside each model on
      the same rows and every column its Elastic Net kept, weighted or not,
      the similarity medians at the chosen n and k (300 trees, depth 20,
      column share 1/6, 1/3 or 2/3, leaf 5, 25 or 100, seed 20260927); the
      lowest inner validation error is kept, exact ties going to the first
      point of the grid. Prints each model's choice and, for those three,
      the best error of every combination, and each forest's grid. The
      split-dependent columns are read in their inner variant; a column
      missing on every inner fitting row is removed from its models and
      reported. Refuses unless every row's split is the one its rule draws
      and the matrix has every declared column, the day boundary and the
      active business days. Reads MATRIX; writes ELASTIC_NET_PARAMETERS, the
      forests' column share and leaf among them, never over a file holding
      more than its own choices, written for other models or in an earlier
      format; records the run in ISSUES_DB. The outer test rows are not
      read. Tail cut: the fence is Q3 + k IQR of the business days of the
      inner fitting set (quartiles by linear interpolation, R's and numpy's
      default), never of the validation; the usual flow is the issues at or
      below the k = 3 fence, and every choice above, alpha, lambda, n and k
      and each forest's point, is scored on the usual-flow validation
      issues. First the fitting k is chosen on the grid 1.5, 3, 5, 10 and
      no cut, on the own start model alone: chosen as above on the inner
      fitting rows at or below each k's fence, it keeps the k of its lowest
      error, exact ties going to the larger k; then every model, forest and
      reference fits on the rows of that k. The similarity medians and the
      reporter median keep reading every training issue. The parameters
      file records the chosen k, the model it was chosen on, both fences in
      business days and every k's error; one without them is refused.
      The population is the matrix's, read from its split columns. Every
      row is read by its id, project and split verdicts alone, and only the
      outer training rows are parsed further. A matrix of the first-commit
      population (primeiro_commit_*) is selected on its split, its target
      (FC_target_days) and its sets, the own ones with the first commit's
      size and the retrospective ones with its share; the parameters file
      has a header of its own. It refuses the In Progress study's
      TARGET_DB and ISSUES_DB before any row is read. The split's rule is
      the one SPLIT_RULE declares (random unless set), named in the
      parameters file's header when it is not the random one. Under
      SPLIT_RULE=temporal, two things differ: every forest is swept with
      500 trees, the library's default (the random split keeps 300); and
      the analogy estimate, the fifteenth model, is chosen on the same
      usual-flow inner validation issues: of the nine similarity medians,
      each read in its inner column as the estimate, the lowest MAE, the
      first in order on a tie, an issue without it taking the global median
      of the inner fitting rows at or below the chosen k's fence. The
      parameters file then gives the tree count (forest every_model trees)
      and the chosen column (analogy chosen column), and the run record
      states both and every similarity median's error."""),

  ISSUES_SIMILARITY_TEST("issues-in-progress-similarity-test",
      new IssuesSimilarityTestCommand(), """
      Issue duration from the first In Progress, outer mode only: SCORES THE OUTER TEST, in whole business days
      (Monday-to-Friday dates reached, at each issue's day boundary,
      holidays ignored, the time back in Open or Reopened left out) on the
      issues of at least one business day, those of 0 dropped from the outer
      training and test. Every model, forest and reference fits on the outer
      training rows at or below the fence of the chosen k (Q3 + k IQR of the
      outer training set's business days), and every table is read twice:
      on the usual flow, the outer test issues at or below the k = 3 fence of
      the outer training set, the main reading, and on all issues, the
      complementary one. Reads MATRIX and ELASTIC_NET_PARAMETERS and chooses
      nothing. Scores the fourteen models the inner report scores: refits
      the six Elastic Net models (the own start model, Al-Zubaidi and Kikas
      at the start; the retrospective main reading, the same with the
      comments and the participants, a ceiling, and Kikas's retrospective
      one) on the outer training set with its chosen alpha and the chosen
      lambda times the ratio of rows,
      the similarity medians at the Jaccard n, embedding n and cluster k
      chosen for each model; refits beside each, as a robustness check, its
      random forest with the chosen count of columns per split and leaf;
      and with the global and project medians scores the outer test: error
      in business days, with the slowest 10% left out too, Spearman,
      pairwise ordering, error by band of the true business days (at the
      quartiles of the outer training rows fitted; nothing clipped),
      prediction intervals at 80% and 95% (split conformal on the log1p
      scale, one factor per model and level, the factors the inner models
      calibrated on the usual-flow inner validation applied to the outer
      models, an approximation; read overall and per error band), and the
      agreed paired comparisons with intervals resampling whole projects:
      the own start model against Al-Zubaidi and against Kikas at the
      start; the retrospective main reading against the own start model;
      the ceiling against the main reading and against Kikas's
      retrospective model; the main reading against Kikas's; every model
      against the global median and every model but the global median
      against the project median; the forests in the same pairs. As a side
      reading, the main reading against the own start model again on the
      issues with an implementation commit only. Relevance: each Elastic
      Net's standardised coefficients in the refit on the outer training
      set, with intervals resampling whole projects of the outer training
      set; no forest's permutation importance, which would read the outer
      test's labels. Refuses unless the parameters file exists and is for
      these models with a forest for each, its choices were made on that
      matrix, every row's split is the one its rule draws and every column
      is present. Writes the per-issue predictions, the diagnostics and the
      relevance to REPORT_DIR; records the run in ISSUES_DB. The
      uncertainty TSV and Markdown report add relative bias, pooled SD and
      shares within +/-25/50/100%, with project-bootstrap confidence
      intervals, overall, by observed-duration band and start-description
      availability. Prediction percentile illustrations use the existing
      inner factors. SPSP scenarios provide context only, no acceptance rule.
      The outer test is a new draw of 30% per project, seed 1738. Ordering
      intervals, a declared measure stated before the outer test was
      opened: for every agreed pair and every model against the project
      median (the global median orders nothing), the paired difference in
      Spearman and in pairwise ordering, each with its interval from the
      same draws of whole projects as the error comparisons; printed and
      recorded. A matrix of the first-commit population is refused on its
      header alone: that study's outer test is sealed until it is opened
      once, with the design frozen and the author's authorisation."""),

  ISSUES_SIMILARITY_INNER_REPORT("issues-in-progress-similarity-inner-report",
      new IssuesSimilarityInnerReportCommand(), """
      Issue duration from the first In Progress, INNER REPORT, read-only: every figure is optimistic, the
      settings having been chosen on this same inner validation. Reads
      MATRIX (the retrospective matrix) and ELASTIC_NET_PARAMETERS; keeps
      only the outer training rows, the others left behind on their split
      field, never parsed beyond it; drops the issues of 0 business days
      (the time back in Open or Reopened left out, as the similarity stage
      wrote them); refits the fourteen models the test scores (the global
      and project medians, the six Elastic Nets at their chosen alpha and
      lambda, unscaled, and n and k, and their six forests at their chosen
      column share and leaf) on the inner fitting rows and scores the inner
      validation, in whole business days, by the test's own scoring, fitted
      on the inner fitting rows at or below the fence of the chosen k. Every
      table, models, bands, paired comparisons and ordering intervals, is
      read twice: on the usual flow, the validation issues at or below the
      k = 3 fence of the inner fitting set, the main reading, and on all
      issues, the complementary one, marked as such; the report's header
      states the chosen k, the model it was chosen on, both fences and each
      reading's issues. Writes to REPORT_DIR: the report; the per-issue
      predictions with whether the issue is of the usual flow and whether
      it has an implementation commit; per reading and model the error, the
      gains over the global and the project median, Spearman, pairwise
      ordering and the error's quantiles; the error by band of the true
      business days, at the quartiles of the cut fitting rows; the
      prediction intervals at 80% and 95% (split conformal on the log1p
      scale, symmetric, the lower end at least 1 business day, one factor
      per model and level), calibrated on half A of the usual-flow
      validation issues, split by whole projects, and checked on half B:
      per reading, model and level, overall and per error band of the
      observed duration (read after the fact), the coverage with its
      interval resampling whole projects, the median width, the width over
      the global median's and the MAE; the factors recomputed on both
      halves for later use; the paired comparisons of the test, with
      intervals resampling whole projects; the main reading against the own
      start model on the issues with and without an implementation commit;
      the ordering intervals; the Elastic Nets' coefficients; and the
      relevance section: each Elastic Net's standardised coefficients with
      intervals resampling whole projects of the inner fitting set, and
      each forest's ten largest permutation importances on the usual-flow
      validation issues with the spread of their repeats, every column's in
      its own file. Refuses unless the parameters file is the selection
      step's own for these models, its choices were made on that matrix,
      every outer training row is inner fitting or inner validation and
      every column is present, and unless its header's split rule is the
      one SPLIT_RULE declares, which the report describes. Under
      SPLIT_RULE=temporal the analogy estimate is the fifteenth model, read
      in the inner column the parameters file froze, its fallback the
      global median of the inner fitting rows at or below the chosen k's
      fence, with its paired
      comparisons; the forests are refitted with the file's tree count;
      every model gets its MMRE and Pred(25); and, when the inner
      validation holds a new project (issues there and none in the inner
      fitting), every table but the prediction intervals is given again
      without its issues, with their count. A temporal file without the
      analogy's column or the tree count is refused, as is a random one
      with the analogy's column. Writes nothing to the database and records
      no run. Also writes relative-error uncertainty and prediction-percentile
      TSVs: bias, pooled SD, shares within +/-25/50/100% and subgroup MAE,
      with project-bootstrap confidence intervals, overall, by observed
      duration and start-description availability; P25/P50/P75/P90/P95
      estimates with existing pooled prediction intervals. The report's
      separate SPSP section gives cited uncertainty context, not a scheduling
      acceptance threshold. These additions choose or refit no settings.
      The population is the matrix's, read from its split columns; a
      parameters file written for the other population is refused. For the
      first-commit population the report also lists the ten largest errors
      on each side of the own retrospective model on every inner validation
      issue, with the target's parts, commits, patches, the first commit's
      size and share, the issue's lines, Java lines, files, comments,
      participants and description length, and each column's median on
      both sides and on every issue (similarity-inner-largest-errors.tsv).
      For the first-commit population the side reading's issues are named
      "with Java code": every issue has commits, and these are the ones with
      a Java line observed among them."""),

  ISSUES_FIRST_COMMIT_SHARE_ABLATION("issues-first-commit-share-ablation",
      new app.cli.commands.IssuesFirstCommitShareAblationCommand(), """
      Issue duration from the first commit, SHARE ABLATION, read-only: a
      diagnostic outside the fourteen declared models, on the inner
      validation only, every figure optimistic. The main reading of the own
      retrospective model is chosen twice by the selection's procedure, with
      and without the first commit's share of the issue's lines (decision
      16a), on the inner fitting rows at or below the chosen k's fence, every
      choice scored on the usual-flow validation issues; the variant with the
      share must reproduce the selection's own choice. Both are refitted as
      the inner report refits them and read on the usual flow and on every
      validation issue: MAE, Spearman, pairwise ordering and the paired
      differences with intervals resampling whole projects. Reads MATRIX (a
      first-commit retrospective matrix, the outer training rows only) and
      ELASTIC_NET_PARAMETERS (written on it); writes share-ablation.md and
      share-ablation.tsv to REPORT_DIR; nothing to the database."""),

  ISSUES_FIRST_COMMIT_ANALOGY("issues-first-commit-analogy",
      new app.cli.commands.IssuesFirstCommitAnalogyCommand(), """
      Issue duration from the first commit, ANALOGY ESTIMATE, read-only and
      EXPLORATORY: added after the random split's outer test was opened, it
      confirms nothing. One similarity median used directly as the estimate, in the
      manner of Shepperd and Schofield (1997), the candidate with the lowest
      MAE on the usual-flow inner validation; an issue without neighbours
      takes the global median. Read on the outer test beside the fourteen
      models as the test wrote them, on all issues and on the usual flow:
      MAE, Spearman, pairwise ordering, MMRE and Pred(25), and the analogy
      estimate against the two medians and the six start models with
      intervals resampling whole projects. Reads MATRIX,
      ELASTIC_NET_PARAMETERS and TEST_PREDICTIONS; writes
      analogy-comparison.md and analogy-predictions.tsv to ANALOGY_DIR;
      nothing to any database."""),

  ISSUES_FIRST_COMMIT_PREDICTED_BAND("issues-first-commit-predicted-band",
      new app.cli.commands.IssuesFirstCommitPredictedBandCommand(), """
      Issue duration from the first commit, ERROR BY BAND OF THE PREDICTED
      DURATION, read-only: a description of the outer test's frozen
      predictions, nothing refitted or chosen. Each issue goes to the band of
      its prediction, at the quartiles of the observed duration of the cut
      fitting rows as the test's report records them (half-open, as the
      error bands are), beside the same bands by observed duration: n, MAE,
      median error and bias per band; for the long band (above Q3) the four
      counts of predicted long against observed long, precision and recall.
      The three Elastic Net start models, on the full population (main) and
      on the usual flow (observed at or under the report's fence). Reads
      REPORT_DIR (similarity-test-predictions.tsv) and the run's log, RUN_LOG
      (default run.log beside REPORT_DIR), for the band limits (printed with
      two decimals) and the fence (three decimals); BAND_TOPS ("a,b,c",
      finite, non-decreasing) and USUAL_FENCE (finite, positive) override
      them; the log must hold exactly one test-stage line of each. Writes predicted-band.md and
      predicted-band.tsv to OUT_DIR; nothing to any database."""),

  ISSUES_FIRST_COMMIT_TEST("issues-first-commit-test",
      app.cli.commands.IssuesSimilarityTestCommand.firstCommit(), """
      Issue duration from the first commit, outer mode only: OPENS AND SCORES
      THE FIRST-COMMIT STUDY'S OUTER TEST, ONCE. The scoring of
      issues-in-progress-similarity-test on the first-commit population: the
      fourteen models refitted on the outer training rows at or below the
      fence of the chosen k with the parameters read from
      ELASTIC_NET_PARAMETERS (chosen nothing), every table read on the usual
      flow and on all issues, the agreed comparisons and ordering intervals
      resampling whole projects, prediction intervals, relevance, and the
      side reading on the issues with Java code. Refuses, reading nothing,
      unless MATRIX holds the first-commit population's split; refuses when
      ISSUES_DB already holds a run of this step or REPORT_DIR already holds
      predictions; refuses unless the parameters file was written for this
      population on this matrix, and unless its header's split rule is the
      one SPLIT_RULE declares (a header naming none is the random one).
      Under SPLIT_RULE=temporal the analogy estimate is the fifteenth model,
      its column read from the parameters file and its fallback the global
      median learned on the outer training (refused without the column; a
      file of the random split holding it is refused too), the forests' tree
      count must be given there, and the opening is recorded as
      issues-first-commit-test-temporal. Every reading is also given
      without the issues of new projects, with their count. Writes the
      predictions, diagnostics, relevance and uncertainty to REPORT_DIR;
      records the run, with the time the test was opened and the SHA-256 of
      every input and output, in ISSUES_DB.""");

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
