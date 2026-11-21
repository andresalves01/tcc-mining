# Issue duration from the first commit

Mining code for André Santos Alves's undergraduate thesis (PUC Minas). It estimates how many
business days an issue of an Apache Java project takes from its first human commit to its first
resolution, from what was known when the work started, and measures how much more is explained by
what happened during the work. The data is the **SmartSHARK 2.2** corpus, and the issues are split
in time: the models learn from the earlier issues and are tested on the later ones.

The domain terms (business day, crossing issue, point-in-time history, usual flow, and so on) are
defined in [CONTEXT.md](CONTEXT.md).

## Requirements

- **JDK 21.** The Gradle toolchain downloads it if it is missing.
- **Docker**, for the MongoDB in [compose.yaml](compose.yaml).
- **The full SmartSHARK 2.2 release** (about 686 GB), from <https://smartshark.github.io/dbreleases/>.
  The small release lacks `code_entity_state`, which the complexity stage needs.
- **15 GB of RAM**: 8 GB for the app (`APP_OPTS=-Xmx8g`) and 6 GB for MongoDB's cache.

```bash
SMARTSHARK_DATA_DIR=/path/to/mongo_data docker compose up -d
mongorestore --gzip --archive=smartshark_2_2.agz      # once; nothing here writes to it
./gradlew :app:installDist
app/build/install/app/bin/app check                   # is the corpus complete?
```

## Reproducing the results

**1. Prepare the corpus, once.** These stages write to `smartshark_tcc`; the last one takes the
longest and resumes if interrupted.

```bash
APP=app/build/install/app/bin/app
$APP reconstruct-people && $APP harvest-jira && $APP build-ledger && $APP funnel && $APP complexity
```

**2. Run the study in a folder of its own.** The design is fixed: the first-commit population,
the revised temporal split, its databases, and a fixed name for every file inside `RUN_DIR`, so
each stage reads what the previous one wrote. A run chooses only its folder (`RUN_DIR`), where
its records go (`ISSUES_DB`, a new one per run) and, for the sensitivity, `WAIT_FENCE_K`; any
other setting is refused. Copy the build into the folder first, so later code changes cannot
change the run.

```bash
export RUN_DIR=$PWD/runs/revised ISSUES_DB=smartshark_first_commit_issues_mine APP_OPTS=-Xmx8g
mkdir -p "$RUN_DIR" && cp -r app/build/install/app "$RUN_DIR/app" && APP=$RUN_DIR/app/bin/app

$APP issues-download-model                         # the embedding model, checked by hash
$APP issues-in-progress-extract                    # the issues, the split and the target
$APP issues-in-progress-team                       # team columns
$APP issues-in-progress-similarity                 # description similarity medians
$APP issues-in-progress-retrospective              # what happened during the work
$APP issues-in-progress-similarity-elastic-net     # every choice, on the inner validation
$APP issues-in-progress-similarity-inner-report    # the inner validation report
$APP issues-first-commit-test 2>&1 | tee "$RUN_DIR/test.log"   # the test, once
$APP issues-first-commit-same-model-calibration    # the official prediction intervals
$APP issues-first-commit-predicted-band            # error by band of the predicted duration
```

The stage names keep the `issues-in-progress-` prefix of the project's first study, but they run
the first-commit study only. Each run prints its settings at the top.

**The test is opened once.** `issues-first-commit-test` reads the held-out issues and chooses
nothing; every choice is made before it. It refuses to run twice in the same `ISSUES_DB`. In the
thesis it was opened on 20/11/2026, with the author's authorisation.

**Sensitivity to the cap on the waits between commits:** repeat step 2 with `WAIT_FENCE_K=3`, and
again with `WAIT_FENCE_K=none`, each with its own `RUN_DIR` and `ISSUES_DB`.

**Read-only extras**, which write nothing to MongoDB: `issues-first-commit-audit` (the population
and the split, without reading the test), `issues-first-commit-supporting-numbers` (the counts
and worked examples in the text) and `issues-first-commit-share-ablation`.

`app help` describes every command in run order, the settings a run can choose and the ones
the study fixes. `MONGO_URI` and `SOURCE_DB` point at another server or corpus name; the thread
counts (`EMBEDDING_THREADS`, `ELASTIC_NET_THREADS`, `BOOTSTRAP_THREADS`) only change the speed.

## Tests

The tests use fixtures and need neither MongoDB nor the model. Run one package at a time, since
the whole suite at once can hang:

```bash
./gradlew test --tests 'app.issues.*'
./gradlew test --tests 'app.pipeline.*'
./gradlew test --tests 'app.cli.*'
./gradlew test --tests 'app.study.*' --tests 'app.model.*' --tests 'app.features.*' \
  --tests 'app.labels.*' --tests 'app.identity.*' --tests 'app.config.*' \
  --tests 'app.EventReplayTest' --tests 'app.CorpusRegressionTest'
```

`app.CorpusRegressionTest` checks the restored corpus against the one these results came from,
and is skipped without MongoDB. The embedding tests read the model from `MODEL_DIR` or
`~/.cache/tcc-minilm-java`, and are skipped without it (`REQUIRE_MODEL=1` makes them fail).

## Code layout

| Package | Contents |
|---|---|
| `app.cli` | the commands, the study's fixed settings, the help text and the console output |
| `app.config` | reading the settings |
| `app.identity`, `app.contribution` | the developer registry and the per-commit ledger |
| `app.labels`, `app.features` | the issue funnel and the team and complexity attributes |
| `app.pipeline` | reading the corpus, the extraction tasks and the embedder |
| `app.model`, `app.study` | the estimators, their metrics and the shared matrix types |
| `app.issues` | the target, the attributes, the models, the scores and the reports |

## License

[MIT](LICENSE).
