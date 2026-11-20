# Issue duration from In Progress

Mining code for André Santos Alves's undergraduate thesis (PUC Minas). It estimates how many
business days an issue of an Apache Java project takes from its first transition to In Progress to
its first resolution, using what was known when the work started, and it measures how much more is
explained by what happened during the work. The data is the **SmartSHARK 2.2** corpus.

The domain terms used below (business day, usual flow, inner and outer split, ceiling, and so on)
are defined in [CONTEXT.md](CONTEXT.md).

## Requirements

- **JDK 21.** The Gradle toolchain downloads it if it is missing, so you only need a JVM to start
  Gradle.
- **Docker**, for the MongoDB in [compose.yaml](compose.yaml).
- **The full SmartSHARK 2.2 release** restored into that MongoDB. It takes about 686 GB on disk.
  The small release lacks `code_entity_state`, which the complexity stages need.
- **Memory.** The app runs with a 4 GB heap by default. The similarity stage needs 8 GB
  (`APP_OPTS=-Xmx8g`). MongoDB is set to a 6 GB cache, so 15 GB of RAM in total is the minimum
  that was tested.

### Getting the corpus

The SmartSHARK authors publish the release at <https://smartshark.github.io/dbreleases/> as a
gzipped MongoDB archive (`.agz`). Start the database and restore it once:

```bash
SMARTSHARK_DATA_DIR=/path/to/mongo_data docker compose up -d
mongorestore --gzip --archive=smartshark_2_2.agz
```

Keep the database name `smartshark_2_2`, or set `SOURCE_DB` to the name you used. Nothing in this
code writes to the source database.

## Building and running

```bash
./gradlew :app:installDist                 # build once
app/build/install/app/bin/app help         # every command and environment variable
app/build/install/app/bin/app check        # is the corpus the one the stages need?
```

`check` reads collection metadata only and answers in seconds. Every stage repeats the check for
the collections it reads and refuses to run on an incomplete corpus.

For a run whose results you want to keep, copy `app/build/install/app` into the run's folder and
run that copy, so later code changes cannot change the run.

### Configuration

Settings come from environment variables, or from `-D` system properties with the same name. The
main ones:

| Variable | Default | Meaning |
|---|---|---|
| `MONGO_URI` | `mongodb://127.0.0.1:27017` | the MongoDB server |
| `SOURCE_DB` | `smartshark_2_2` | the corpus; read only |
| `TARGET_DB` | `smartshark_tcc` | the collections the preparation stages build |
| `ISSUES_DB` | `smartshark_issues` | the run records of the issue stages |
| `PREPARED_DB` | `TARGET_DB` | where the issue stages read the prepared collections; read only |
| `POPULATION` | `IN_PROGRESS` | the population `issues-in-progress-extract` extracts: `IN_PROGRESS` or `FIRST_COMMIT` |
| `APP_OPTS` | (unset) | JVM options, such as `-Xmx8g` |

The issue stages read and write files whose paths you give in `CACHE`, `TEAM_COLUMNS`, `MATRIX`,
`RETROSPECTIVE_MATRIX`, `ELASTIC_NET_PARAMETERS`, `TEST_PREDICTIONS`, `REPORT_DIR`, `ANALOGY_DIR`,
`MODEL_DIR` and `EMBEDDING_CACHE`.
`app help` describes each one.

## The pipeline

The stages run in order, and each one reads what the previous ones wrote.

### 1. Preparing the corpus (writes to `TARGET_DB`)

| Command | What it does |
|---|---|
| `reconstruct-people` | Rebuilds the developer registry from the git repositories stored in the corpus. The published release has no `people` collection. |
| `harvest-jira` | Matches Jira accounts to developers through the public Jira API. The answers are cached on disk. `MERGE_STRICTNESS` sets how identities are merged into one person. |
| `build-ledger` | Writes `developer_contribution`: one row per commit, with its author and line counts. |
| `funnel` | Applies the exclusions and writes `issue_label`. |
| `complexity` | Writes `commit_complexity`, the complexity of the methods each commit changed. This is the slowest stage. If interrupted, it resumes where it stopped. |

`PILOT=<project>` restricts `build-ledger` and `funnel` to matching projects. Use it to try a stage
on one small project before a full run.

### 2. The issue-duration study

| # | Command | Reads | Writes |
|---|---|---|---|
| — | `issues-download-model` | the internet | the sentence-embedding model in `MODEL_DIR`, checked by hash |
| 1 | `issues-in-progress-extract` | the corpus and the collections above | the issue matrix (`CACHE`) and the random split by project |
| 2 | `issues-in-progress-team` | the same | the team columns (`TEAM_COLUMNS`) |
| 3 | `issues-in-progress-similarity` | `CACHE`, `TEAM_COLUMNS`, the model | the matrix with the target and the description similarity medians (`MATRIX`) |
| 4 | `issues-in-progress-retrospective` | `MATRIX` | the matrix with the attributes observed during the work (`RETROSPECTIVE_MATRIX`) |
| 5 | `issues-in-progress-similarity-elastic-net` | the retrospective matrix | the settings chosen on the inner validation (`ELASTIC_NET_PARAMETERS`) |
| 6 | `issues-in-progress-similarity-inner-report` | the matrix and the settings | the inner validation report in `REPORT_DIR` |
| 7 | `issues-in-progress-similarity-test` | the matrix and the settings | **the outer test scores** in `REPORT_DIR` |

Stages 1 to 5 record their runs in `ISSUES_DB`. Stage 6 writes only files.

Steps 5 to 7 fit and score the same fourteen models: the global and project medians, plus six
attribute sets (the own start model, Al-Zubaidi and Kikas at the start, the retrospective model in
its main reading and its ceiling, and Kikas's retrospective set), each fitted with an Elastic Net
and with a random forest.

**The outer test is scored once.** Step 7 reads the held-out issues. Every choice is made in steps
5 and 6, and step 7 chooses nothing. It was run once, on 22/10/2026, with the author's
authorisation. Any analysis of the outer test after that is exploratory.

A full run of stages 1 to 6, with each stage's output in its own folder:

```bash
RUN=/path/to/runs/my-run
APP=$RUN/app/bin/app                       # the frozen copy of the build
export CACHE=$RUN/in-progress-matrix.tsv TEAM_COLUMNS=$RUN/team-columns.tsv
export MODEL_DIR=$HOME/.cache/minilm EMBEDDING_CACHE=$RUN/embedding-cache

$APP issues-download-model
$APP issues-in-progress-extract
$APP issues-in-progress-team
APP_OPTS=-Xmx8g MATRIX=$RUN/augmented-matrix.tsv REPORT_DIR=$RUN/similarity-reports \
  $APP issues-in-progress-similarity
MATRIX=$RUN/augmented-matrix.tsv RETROSPECTIVE_MATRIX=$RUN/retrospective-matrix.tsv \
  $APP issues-in-progress-retrospective
MATRIX=$RUN/retrospective-matrix.tsv ELASTIC_NET_PARAMETERS=$RUN/elastic-net-parameters.txt \
  $APP issues-in-progress-similarity-elastic-net
MATRIX=$RUN/retrospective-matrix.tsv ELASTIC_NET_PARAMETERS=$RUN/elastic-net-parameters.txt \
  REPORT_DIR=$RUN/inner-report $APP issues-in-progress-similarity-inner-report
```

### 3. Adjusted duration

`issues-in-progress-adjusted-duration` builds a second version of the target that keeps the time
up to the first commit, caps the waits between commits, and after the last commit counts only the
days with human activity. It reads frozen files from an earlier run and writes nothing to
MongoDB. It needs `MATRIX`, `ADJUSTED_DIR`, `BOT_ACCOUNTS`, `START_DESCRIPTIONS` and
`EMBEDDING_CACHE`. `ADJUSTED_STEP` selects the step: `evidence`, `audit`, `matrix`, `select`,
`report`, `models`, `baseline-select`, `baseline-report` or `outer-matrix`.

### 4. Issue duration from the first commit: audit

`issues-first-commit-audit` builds, read only, the population of another study: the duration from
an issue's first commit to its first resolution. It keeps the issues resolved as Fixed at their
first resolution, created before their first commit, with at least two commits once duplicates are
gone, a first commit below 50% of the changed lines, code on at least two dates and no commit linked
to another issue. It draws the split with the rule named in `SPLIT_RULE` (random by default, the
rule of the In Progress study), learns the cap on the waits between commits and patches on the
inner fitting only, and counts the target of the outer training. It also reports the limits of 30%
and 70% and the overlap with the In Progress split. Of the outer test it writes only the size
(under `temporal` or `revised`, also its projects and the dates of its first commits; see "Split
rule").

It needs `AUDIT_DIR`, where it writes `audit.md` and `per-issue.tsv`, and writes nothing to
MongoDB. Give it `APP_OPTS=-Xmx6g`.

`WAIT_FENCE_K` sets the k of the cap's fence, Q3 + k IQR of the inner fitting's waits. Empty is 1.5,
the official value; a positive number is that k; `none` is no cap at all, an infinite fence, so no
wait is cut, however long. Zero, a negative number, NaN, infinity or text is refused before anything
is read. It is a sensitivity of the first-commit study only (the In Progress study never reads it):
with any value but 1.5 the `audit.md` names the k in its header, its table and its rule.

### 4b. Issue duration from the first commit: supporting numbers

`issues-first-commit-supporting-numbers` writes, read only, the numbers behind the text of the
first-commit study. It reads the audit's base, with `SPLIT_RULE` and `WAIT_FENCE_K` as the audit
reads them, and writes to `SUPPORTING_DIR`: `filter-counts.md` (how many issues with a commit that
counts were created after their first commit, at its very instant, had it before their first In
Progress, or have a single commit), `worked-example.md` and `worked-example.tsv` (three issues of
the outer training, chosen by fixed criteria, with their marks, waits and tail: the largest gap
between the calendar's business days and the study's, the most business days the cap cut, and the
most business days after the last mark that did not count). Under `SPLIT_RULE=revised` they are
chosen only among the outer training issues that teach, those crossing the outer cut left out. The
outer test is never explained. It needs `SUPPORTING_DIR` and writes nothing to MongoDB. Give it `APP_OPTS=-Xmx6g`.

### 5. Issue duration from the first commit: extraction

`issues-in-progress-extract` with `POPULATION=FIRST_COMMIT` extracts the first-commit population
instead of the In Progress one. It builds the audit's population, split, cap and target: the cap is
learned on the inner fitting's waits and then applied, frozen, to every drawn issue. It reads every
column of the In Progress extraction at the first commit that counts, checks time travel at that
instant, and writes the target and its parts after them (`FC_target_days`,
`FC_implementation_days`, `FC_tail_days`, `FC_capped_days`, `FC_marks`, `FC_patches` and
`FC_day_boundary_hour`), then the first commit's lines added and removed in every file
(`FC_first_commit_lines`) and its share of the issue's changed lines (`FC_first_commit_share`).
The In Progress column `PAUSA_volta_open` is left out, since this study does not read the status. The cache's split columns are `primeiro_commit_externa` and
`primeiro_commit_interna`. Of the target it prints and records only the outer training's.

`WAIT_FENCE_K` (empty is 1.5, `none` is no cap; a sensitivity only) is read here as in the audit and
is recorded in the run as `wait_fence_k` only when it is not 1.5. The stages after the extraction
state the k of the matrix they read, taken from that run record (the latest extraction run of the
design's run collection; absent means 1.5).

The first-commit study writes to databases of its own, and the command refuses the In Progress
study's `TARGET_DB` and `ISSUES_DB` before it reads anything. The prepared collections stay in
`smartshark_tcc`, read through `PREPARED_DB`:

```bash
POPULATION=FIRST_COMMIT TARGET_DB=smartshark_first_commit \
  ISSUES_DB=smartshark_first_commit_issues PREPARED_DB=smartshark_tcc \
  APP_OPTS=-Xmx6g CACHE=$RUN/first-commit-matrix.tsv $APP issues-in-progress-extract
```

### 6. Issue duration from the first commit: team, similarity and retrospective

The next three stages take the first-commit population too. `issues-in-progress-team` reads
`POPULATION` as the extraction does. The similarity and retrospective stages take the population
of the matrix they read, from its split columns. All three refuse the In Progress study's
`TARGET_DB` and `ISSUES_DB`.

- **Team.** The four team columns, read at the first commit.
- **Similarity.** The description at the first commit. Every similarity and reporter median reads
  `FC_target_days` of its split's training side, never the issue's own. The status is not read,
  so no `day_boundary_hour` or `active_business_days` is written: the target is already in the
  matrix.
- **Retrospective.** The nine columns over the window from the first commit to the first
  resolution.

```bash
export POPULATION=FIRST_COMMIT TARGET_DB=smartshark_first_commit \
  ISSUES_DB=smartshark_first_commit_issues PREPARED_DB=smartshark_tcc
CACHE=$RUN/first-commit-matrix.tsv TEAM_COLUMNS=$RUN/team-columns.tsv APP_OPTS=-Xmx6g \
  $APP issues-in-progress-team
APP_OPTS=-Xmx8g CACHE=$RUN/first-commit-matrix.tsv TEAM_COLUMNS=$RUN/team-columns.tsv \
  MATRIX=$RUN/augmented-matrix.tsv REPORT_DIR=$RUN/similarity-reports \
  MODEL_DIR=$HOME/.cache/tcc-minilm-java EMBEDDING_CACHE=$RUN/embedding-cache \
  $APP issues-in-progress-similarity
MATRIX=$RUN/augmented-matrix.tsv RETROSPECTIVE_MATRIX=$RUN/retrospective-matrix.tsv \
  $APP issues-in-progress-retrospective
```

The models read the first-commit target as their business days. They use the same sets as the In
Progress study, plus the two attributes of the first commit:

- the size closes the own start model, so both readings of the retrospective model read it too;
- the share closes the retrospective columns of both readings.

The literature sets are unchanged. Use `ElasticNetSelection.cells` with the population to get
them.

### 7. Issue duration from the first commit: selection and inner report

`issues-in-progress-similarity-elastic-net` and `issues-in-progress-similarity-inner-report` take
the population of the matrix they read, from its split columns. On the first-commit matrix they
use its split, its target and its model sets. The rules are the In Progress study's: the tail cut
with its k chosen on the own start model, the usual flow, the comparisons resampling whole
projects, and the similarity medians only inside the models.

- **Selection.** Every row is read by its id, project and split verdicts alone, to check the split
  and count it. Only the outer training rows are parsed further. The parameters file has a header
  of its own population. The command refuses the In Progress study's `TARGET_DB` and `ISSUES_DB`
  before it reads a row.
- **Inner report.** It refuses a parameters file written for the other population. For the first
  commit it adds `similarity-inner-largest-errors.tsv` and a section of the report: the ten
  largest errors on each side of the own retrospective model on every inner validation issue.
  Each issue shows the target's parts, its commits and patches, the first commit's size and share,
  the issue's lines, Java lines, files, comments, participants and description length. Each
  column's median is given for both sides and for every issue. Its side reading names the issues
  "with Java code", since every issue of this population has commits.
- **Share ablation.** `issues-first-commit-share-ablation` is a diagnostic outside the fourteen
  models, on the inner validation only. It chooses the main reading of the own retrospective model
  twice by the selection's procedure, with and without the first commit's share, and compares the
  two on the usual flow and on every issue. It reads the same `MATRIX` and
  `ELASTIC_NET_PARAMETERS`, writes `share-ablation.md` and `share-ablation.tsv` to `REPORT_DIR`,
  and writes nothing to MongoDB.
- **Error by band of the predicted duration.** `issues-first-commit-predicted-band` describes the
  test's frozen predictions of the three Elastic Net start models: each issue goes to the band of
  its prediction (the quartiles of the observed duration of the cut fitting rows, as the test's
  report records them), beside the same bands by observed duration, with n, MAE, median error and
  bias per band and, for the band above Q3, the predicted-long against observed-long counts,
  precision and recall. It reads `REPORT_DIR` and the run's log (`RUN_LOG`; `BAND_TOPS` and
  `USUAL_FENCE` override the log's figures, the band limits printed with two decimals and the
  fence with three), writes `predicted-band.md` and
  `predicted-band.tsv` to `OUT_DIR`, and writes nothing to MongoDB.
- **Analogy estimate (exploratory).** `issues-first-commit-analogy` was added after the outer
  test of the random split was opened and confirms nothing. It uses one similarity median directly
  as the estimate, in the manner of Shepperd and Schofield (1997), choosing the candidate with the
  lowest MAE on the usual-flow inner validation; an issue without neighbours takes the global
  median. It reads it on that outer test beside the fourteen models, as the test wrote them, with
  MMRE and Pred(25) added. It reads `MATRIX`, `ELASTIC_NET_PARAMETERS` and `TEST_PREDICTIONS`,
  writes `analogy-comparison.md` and `analogy-predictions.tsv` to `ANALOGY_DIR`, and writes nothing
  to MongoDB.
- **Outer test.** `issues-in-progress-similarity-test` refuses a first-commit matrix on its header
  alone. That outer test stays sealed until it is opened once, with the design frozen and the
  author's authorisation.

```bash
export TARGET_DB=smartshark_first_commit ISSUES_DB=smartshark_first_commit_issues \
  PREPARED_DB=smartshark_tcc
MATRIX=$RUN/retrospective-matrix.tsv ELASTIC_NET_PARAMETERS=$RUN/elastic-net-parameters.txt \
  $APP issues-in-progress-similarity-elastic-net
MATRIX=$RUN/retrospective-matrix.tsv ELASTIC_NET_PARAMETERS=$RUN/elastic-net-parameters.txt \
  REPORT_DIR=$RUN/inner-report $APP issues-in-progress-similarity-inner-report
```

### Split rule

The similarity, retrospective, selection and test stages check every row's sides against the rule
named in `SPLIT_RULE` before they read anything else of the matrix, and refuse a matrix whose sides
are not that rule's:

- `random`, the default: the rule above, stratified by project.
- `temporal`, for the first-commit study only: the candidates ordered by their first commit, the
  issue id breaking ties; the cut is the instant at position ⌈0.7·n⌉, and training is every first
  commit at or before it, the ties included; the inner split is the same rule on the training side.
  This is the design opened on 02/11/2026.
- `revised`, for the first-commit study only: the revised design of the temporal split, with the
  same cuts. A training issue whose first resolution comes after its level's cut crosses it and
  teaches nothing at that level (it is no fitting row, reference, fence, cap or history there),
  while validation and test keep every issue; every history is point in time, reading only the
  training issues resolved strictly before the first commit of the issue it describes. Every
  stage that routes `temporal` routes `revised` alike, in collections of its own.

An In Progress matrix under `SPLIT_RULE=temporal` or `revised` is refused. The audit draws the rule
named in `SPLIT_RULE`; under `temporal` or `revised` its summary adds the two cuts, the target of the inner fitting, the
inner validation and the outer training, the projects of each side and the new ones, the training
issues that cross the cut, the cap the random rule learns on the same issues, and the censoring at
the end of the data: the latest Jira record, and the issues whose first commit comes after the cut
with no first resolution as Fixed. Of the outer test it gives only counts and dates.

The first-commit extraction draws the rule named in `SPLIT_RULE` too, so the matrix's sides, the cap
(learned on that rule's inner fitting) and the target are the audit's under the same rule. Each
design records its extraction run in collections of its own in `ISSUES_DB`: the random design in
`runs` and `results`, as always, the temporal design in `runs_temporal` and `results_temporal`,
and the revised design in `runs_revised` and `results_revised`. The team, similarity,
retrospective, selection and test stages record in the collection of their declared design too.
Under `temporal`, that is `runs_temporal` and, when a stage writes result rows,
`results_temporal`; under `revised`, `runs_revised` and `results_revised`. A temporal or revised
run refuses a collection holding another design before it reads the corpus or writes files. A run
recorded with a `split_design` field is of that design; one without it is of the temporal or the
revised design when its `split_rule` is that rule's words, and of the random design otherwise.
Before it reads anything, the extraction refuses a run collection that already holds a run of
another design. Under `temporal` or `revised` its run record adds `split_design`, the instants of
both cuts (`split_cuts`), the training issues whose first resolution comes after each cut
(`crossing_the_cut`) and the new
projects with their issues (`new_projects`). Under `revised`, the revised design, the record also
gives the issues left out of what the training side teaches (`crossing_left_out`, at the outer and
at the inner level) and the outer training target without them: `outer_training_target` is the
target of the whole outer training side, crossing issues included, and
`outer_training_target_without_crossing` the target of the issues that teach, the one the
reference reads. There `new_projects` follows the scoring's
definition (no issue that teaches at the outer level) and `new_projects_by_training_side` gives the
other one (no issue on the outer training side, crossing issues included), each labelled with its
definition; the audit lists both readings as well. Under `random` the record and the matrix are
those of before, byte for byte. The extraction writes nothing to `TARGET_DB`.

The parameters file's header names the split rule its choices were made under. A header that
names none, as every file written before the temporal split, is the random rule's. The inner report
and the first-commit test (`issues-first-commit-test`) refuse a parameters file of another rule
than the run's. Under `SPLIT_RULE=temporal` or `revised`:

- **Analogy estimate.** It is the fifteenth model. The selection chooses it on the usual-flow
  inner validation: of the nine similarity medians, each read in its inner column as the estimate,
  the one with the lowest MAE, the first in order on a tie. There an issue without the median
  takes the global median of the inner fitting rows at or below the chosen k's fence. The
  parameters file freezes the choice in one line under the step `analogy`: the chosen similarity
  median (`column`). The inner report and the test read it and choose nothing. An issue without
  that median takes the global median learned on the same fitting rows as the declared reference,
  so no fallback is frozen; a `fallback_business_days` line is refused. A temporal file without
  the line is refused. A random-split file with it is refused too: there the analogy estimate
  stays exploratory.
- **Forests.** They have 500 trees, the Smile library's default, decided by the author on
  02/11/2026 before any temporal result. The selection sweeps them with 500 and writes the count
  (below); a temporal file without it is refused.
- **Inner report.** It gives every model's MMRE and Pred(25) and the analogy estimate's paired
  comparisons. When a project of the inner validation has no inner fitting issue, every table but
  the prediction intervals is given again without that project's issues.
- **Opening.** It is recorded as `issues-first-commit-test-temporal` (under `revised`,
  `issues-first-commit-test-revised`), and it runs once, as the random one does. Under
  `revised` it refuses, before it reads the parameters or any row, unless the newest extraction
  run of `runs_revised` declares the crossing issues left out (`crossing_left_out`) and the
  similarity run that wrote the matrix's columns, found through the retrospective run that wrote
  the matrix, declares point-in-time histories.
- **Prediction intervals, under `revised` only.** The official intervals are the same-model ones,
  chosen before the opening: the inner model, fitted once on the inner fitting rows, predicts the
  test, and its factors come from its own errors on every inner validation issue, the tail above
  the usual flow's fence included. `issues-first-commit-same-model-calibration` computes them
  right after the opening, as part of it: it reads `MATRIX`, `ELASTIC_NET_PARAMETERS` and
  `TEST_PREDICTIONS` (the test's `similarity-test-predictions.tsv`), refuses unless
  `runs_revised` holds the opening, unless `REPORT_DIR` is a new folder outside the test's and
  unless the outer models refitted reproduce the test's predictions, writes
  `same-model-calibration.md` and `.tsv` and records its run in `runs_revised`. The test's own
  intervals, the outer models with the inner models' factors, are calibrated on the same issues
  and labelled an approximation, shown for comparison; so are the intervals of its percentile
  tables. The inner report's check by halves uses the same issues. Under `random` and `temporal`
  the intervals are calibrated on the usual-flow inner validation, as before.

The forests are refitted with the tree count the parameters file gives, in a line whose four
tab-separated fields are `forest`, `every_model`, `trees` and the count. A random-split file
without it, as every existing one, means 300 trees, and the selection under the random rule
still writes none.

In the test, under any rule, every reading is also given without the issues of new projects,
with their count.

### Commands that only read

`check`, `help`, `issues-first-commit-audit`, `issues-first-commit-supporting-numbers`,
`issues-first-commit-share-ablation`, `issues-first-commit-predicted-band` and
`issues-first-commit-analogy` write nothing to MongoDB and can be run at any time.

## Tests

The tests use fixtures and need neither MongoDB nor the model. Running the whole suite at once can
hang, so run one package at a time:

```bash
./gradlew test --tests 'app.issues.*'
./gradlew test --tests 'app.pipeline.*'
./gradlew test --tests 'app.cli.*'
./gradlew test --tests 'app.study.*' --tests 'app.model.*' --tests 'app.features.*' \
  --tests 'app.labels.*' --tests 'app.identity.*' --tests 'app.config.*' \
  --tests 'app.EventReplayTest' --tests 'app.CorpusRegressionTest'
```

- `app.CorpusRegressionTest` checks the restored corpus against the one these results came from.
  It is skipped when no MongoDB is running.
- The embedding tests compare the Java embedder against the reference model. They read the model
  from `MODEL_DIR`, or from `~/.cache/tcc-minilm-java` when it is there, and are skipped
  otherwise. With `REQUIRE_MODEL=1` they fail instead of being skipped.

## Code layout

| Package | Contents |
|---|---|
| `app.cli` | the commands, the help text and the console output |
| `app.config` | reading the settings |
| `app.identity` | rebuilding the developer registry |
| `app.contribution` | the per-commit contribution ledger |
| `app.labels`, `app.features` | the issue funnel and the team and complexity attributes |
| `app.pipeline` | reading the corpus, the extraction tasks and the embedder |
| `app.model` | the Elastic Net and random forest estimators and their metrics |
| `app.study` | the matrix row, outcome and attribute types the stages share |
| `app.issues` | the target, the attributes, the models, the scores and the reports |

## License

[MIT](LICENSE).
