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
| `APP_OPTS` | (unset) | JVM options, such as `-Xmx8g` |

The issue stages read and write files whose paths you give in `CACHE`, `TEAM_COLUMNS`, `MATRIX`,
`RETROSPECTIVE_MATRIX`, `ELASTIC_NET_PARAMETERS`, `REPORT_DIR`, `MODEL_DIR` and `EMBEDDING_CACHE`.
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

### Commands that only read

`check` and `help` write nothing to MongoDB and can be run at any time.

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
