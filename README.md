# Non-intrusive mining for software team formation

The mining half of *Modelagem Não Intrusiva de Desempenho para Formação de Equipes de Software:
Uma Abordagem de Big Data para a Otimização Multiobjetivo de Tempo e Custo* — André Santos Alves
and Hayala Nepomuceno Curto, Departamento de Ciência da Computação, PUC Minas.

The study asks whether a developer's observable history is enough to compare team compositions on
delivery time and cost, without interviews, psychometric tests or manager ratings. This repository
builds the evidence that question is answered from: a labelled sample mined out of the
**SmartSHARK 2.2** corpus, where one label is one issue, tied to

- the developer who resolved it (the single human author of its eligible commits),
- its creation and resolution instants, and the **time-to-code** between them, in fractional days,
- the Java methods its change touched, with their cyclomatic complexity (McCC) and nesting level,
- the volume of Java lines that change carried.

Every indicator comes from the public development history, which is what makes the collection
non-intrusive and the sample re-derivable by anyone holding the same corpus.

Working on the code itself, with or without an AI agent? Start at [AGENTS.md](AGENTS.md) — it
covers which commands are safe to run, the layering rule and the traps this project has already
hit. The map is [docs/paths.md](docs/paths.md); the design rationale is
[docs/architecture.md](docs/architecture.md).

## What is and is not here

| Directory | Contents |
|---|---|
| `app/` | The Java pipeline: identity reconstruction, the contribution ledger, the label funnel, the chronological split, and the seniority and cost-curve report. |
| `analysis/` | The exploratory Mongo shell and Python scripts that decided how the pipeline should be written. See [analysis/README.md](analysis/README.md); they are reconnaissance, not part of the pipeline. |

The Elastic Net duration model and the NSGA-II search over team compositions are **not implemented
here**. This repository stops at the labelled, split sample those stages consume. Estimating a
task's complexity ahead of time is a stated non-goal of the study, not a missing feature.

## Requirements

- **JDK 21.** The Gradle toolchain resolves it; nothing needs to be on `PATH` but a JVM to launch
  Gradle itself.
- **MongoDB** holding the SmartSHARK 2.2 release, reachable at `MONGO_URI`. Restored, it is large:
  the instance this study ran against holds 686 GB on disk across 31 collections (517 GB of
  data plus its indexes; 2.4 TB logical, before WiredTiger compression),
  and a full pass over `commit` reads about 84 GB of it. Release 2.2 is documented against
  MongoDB 4.4; the runs reported here were made on 5.0.
- **~4 GB of heap**, already set in [app/build.gradle.kts](app/build.gradle.kts) together with the
  `--add-opens` flags Spark needs on Java 17+.
- **Disk for the work directory.** `reconstruct-people` streams about 7 GB of repository tarballs
  out of GridFS and unpacks them under `WORK_DIR`.

### Getting the corpus

The releases are published by the SmartSHARK authors at
<https://smartshark.github.io/dbreleases/>, as gzipped MongoDB archive dumps (`.agz`), with
mirrors on Zenodo and Figshare. Restore the archive the usual way for that format and leave the
database named `smartshark_2_2`, or point `SOURCE_DB` elsewhere:

```bash
docker compose up -d                          # the MongoDB this study ran against
mongorestore --gzip --archive=smartshark_2_2.agz
```

[compose.yaml](compose.yaml) reconstructs that server: MongoDB 5.0, a 6 GB WiredTiger cache, and the
corpus bind-mounted from `SMARTSHARK_DATA_DIR` so no `docker volume prune` can ever reach it. Once
it is restored, `./gradlew :app:test --tests 'app.CorpusRegressionTest'` confirms you have the same
corpus the numbers below came from.

**Use the full release, not the small one.** The small variant omits `code_entity_state`, which is
where the method-level McCC and nesting level come from — `funnel` would exclude every issue at its
last stage. The full download is listed at 740 GB.

Nothing in this pipeline writes to the source database.

## Running

```bash
./gradlew :app:installDist           # build once
app/build/install/app/bin/app help   # the command list and every environment variable
app/build/install/app/bin/app check  # first: is the corpus the one the stages need?
app/build/install/app/bin/app stats  # what the registry currently holds
```

`check` reads collection metadata only, so it answers in a second on a corpus the mining stages
take an hour to cross. It prints every collection the stages read, whether it is populated, and
which commands each one blocks if it is not. Every stage repeats the check for its own collections
before it starts, and refuses rather than reporting an empty result as a measurement.

Or straight through Gradle, which rebuilds as needed:

```bash
./gradlew :app:run --args="stats"
```

Configuration is read from the environment, or from `-D` system properties of the same name, once
at startup:

```bash
MONGO_URI=mongodb://127.0.0.1:27017 TARGET_DB=smartshark_tcc PILOT=commons-io \
  app/build/install/app/bin/app build-ledger
```

`PILOT` restricts `build-ledger` and `funnel` to projects whose name contains the given string.
A full pass costs a wrong field name half an hour, so a first run on one small repository is worth
the minute it takes.

## The pipeline

The stages are not independent. Each writes what the next one reads, and identity comes first
because every later stage keys on the canonical author.

| # | Command | Reads | Writes |
|---|---|---|---|
| — | `check` | collection metadata of `SOURCE_DB` | nothing — reports only |
| 1 | `reconstruct-people` | `repository_data` (GridFS), `commit`, `vcs_system` | `people`, `person_canonical`, `identity_bridge_refused` |
| 2 | `harvest-jira` | `issue`, `event`, `issue_system`, the public Jira changelog API | `people`, `person_canonical` |
| 3 | `canonicalize` | `people` | `person_canonical` |
| 4 | `build-ledger` | `commit`, `file_action`, `file`, `project`, `vcs_system` | `developer_contribution` |
| 5 | `funnel` | `issue`, `event`, `issue_system`, `commit`, `file_action`, `file`, `hunk`, `code_entity_state` | `issue_label` |
| 6 | `split` | `issue_label` | `issue_label` (`split` and `split_inner`), `split_meta` |
| 7 | `seniority` | `developer_contribution` | nothing — reports only |

`check` and `stats` may be run at any point. Everything written lands in `TARGET_DB`, which must differ from
`SOURCE_DB`; the pipeline refuses to start otherwise, so a reconstructed document can never be
mistaken for one that shipped in the published release.

Three of these deserve a note:

- **`reconstruct-people`** exists because the published distribution ships without the `people`
  collection. It rebuilds the git half of the registry from the repositories bundled in GridFS,
  joining on `commit.revision_hash`, and **aborts** if that join is not one-to-one — every merge
  below it would otherwise rest on a false premise.
- **`canonicalize`** re-runs only the equivalence relation, reading no source collection. It is
  cheap enough to compare `MERGE_STRICTNESS` settings without re-mining.
- **`split`** does not take a cut-off date. It computes one from the `TRAIN_SHARE` quantile of
  issue creation and records it in `split_meta`, so no downstream script should ever hard-code a
  date — read it back instead. It writes two verdicts per label: `split` for the outer cut, and
  `split_inner` for the inner one, which divides the training side so regularisation can be chosen
  without touching the test set. The three usable partitions are therefore
  `split=TRAIN, split_inner=TRAIN` (fit), `split=TRAIN, split_inner=TEST` (hyperparameter
  selection) and `split=TEST` (held out). Inner eligibility is recomputed against the earlier
  instant rather than inherited, so a developer eligible at the outer cut may not be at the inner
  one.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `MONGO_URI` | `mongodb://127.0.0.1:27017` | |
| `SOURCE_DB` | `smartshark_2_2` | Read-only. |
| `TARGET_DB` | `smartshark_tcc` | Written to; must differ from `SOURCE_DB`. |
| `WORK_DIR` | `build/identity-work` | Where GridFS repository tarballs are unpacked. |
| `JIRA_CACHE_DIR` | `build/jira-cache` | On-disk changelog cache, so re-runs cost no requests. |
| `JIRA_DELAY_MS` | `250` | Pause between issue-tracker requests. |
| `MERGE_STRICTNESS` | `EMAIL_AND_FULL_NAME` | Or `EMAIL_ONLY`, which merges only on an exact email. |
| `VOLUME` | `JAVA` | Or `ALL`: which lines count as developer volume. |
| `COST_MIN` | `1.0` | `C_min`. Reporting only — never stored in the ledger. |
| `COST_RATIO` | `1.7283` | `C_max / C_min`. Reporting only. |
| `TRAIN_SHARE` | `0.70` | The quantile of issue creation the outer cut falls on. |
| `MIN_RESOLVED` | `5` | Resolutions a (developer, project) pair needs before a cut to be carried into it. |
| `PILOT` | unset | Restricts `build-ledger` and `funnel` to matching project names. |

The cost defaults are deliberately unitless: at `COST_MIN=1.0` the curve reports a multiplier
rather than money. The scenario the study reports uses R$ 7,033.08 and R$ 12,154.99, whose ratio is
the 1.7283 above. Those are declared parameters of the experiment, not remuneration observed in the
corpus — the corpus contains none.

## What a complete run produces

The sample the study reports, for checking that a re-run landed in the same place:

| Stage | Result |
|---|---|
| Extraction | 98 Java Apache projects, 207,400 issues, 452,286 commits, 1,589,368 events |
| Registry | 11,025 identities (7,199 git, 3,826 Jira), 13 flagged as automated |
| Canonical people | 7,150, of which 5,251 have authored commits; 90 ambiguous bridges refused |
| Funnel | **56,153 labels**, 2,434 developers, 97 projects |
| Time-to-code | p25 0.88 d, p50 7.03 d, p75 47.52 d, p95 462.74 d |
| Outer split `t0` | 2015-12-01 — 36,677 train / 8,318 test, 677 eligible (dev, project) pairs |
| Inner split `t1` | 2013-10-22 — 24,582 fit / 5,397 parameter selection, 443 eligible pairs |

The funnel's exclusions are printed in order and are **incremental**: each denominator is what the
previous line retained, so the percentages do not add up to a share of the original total and the
filters' effects are not independent.

## Tests

```bash
./gradlew :app:test
```

81 of the 85 tests run in about two seconds against in-memory doubles, with no MongoDB and no
Spark — that is the point of the port-and-adapter layering below. The other four need a MongoDB at `127.0.0.1:27017`. Two live in
`AppSanityTest`, which checks that this machine's Spark and Mongo connector actually work and writes
to a throwaway `smartshark_tcc_test` database. The third is `CorpusRegressionTest`, which asserts
that the restored corpus still holds the counts every number below was measured from. The fourth is
`EventReplayTest`, which replays each sampled issue's event stream and checks it still lands on the
state the corpus records — every date this study uses is recovered from that stream. Both read
little and **skip rather than fail** when no server answers. To skip all four:

```bash
./gradlew :app:test --tests 'app.identity.*' --tests 'app.cost.*' \
  --tests 'app.labels.*' --tests 'app.pipeline.*' --tests 'app.config.*'
```

## Layout

Outermost to innermost. A package never depends on one above it:

```
app                  entry point -- argument selection, exit codes
app.cli              one runner per command, printing
app.pipeline         use cases  -- ReconstructPeople, HarvestJira, Canonicalize, ...
app.pipeline.mongo   adapters   -- GridFS + JGit + the commit join, registry persistence
app.pipeline.jira    adapters   -- issue-tracker changelog client
app.identity.port    interfaces the use cases depend on
app.identity         domain     -- pure; knows nothing of Mongo, HTTP, Spark or files
app.cost             domain     -- pure; contributions, seniority, the cost curve
app.labels           domain     -- pure; the funnel, the methods, the temporal split
```

Adding a command means adding a runner in `app.cli` and one constant to `app.cli.Command`, which
binds its name, its runner and its help text together. The usage text is generated from that enum.

Exit codes: `0` success, `1` a stage refused to run or aborted on a broken premise, `2` misuse —
an unknown command or a configuration error.

## Reproducibility and limits

The procedure can be re-applied to any corpus SmartSHARK can process, provided it covers issue
history, commit links, authorship, timestamps, code changes and structural metrics. Cut-off dates,
identities, histories, distributions and model parameters must all be rebuilt in a new corpus: the
counts above and the values learned from this sample do not transfer.

Two measurement decisions are worth knowing before reading any output:

- **Identity unification is a heuristic, audited, not a guarantee.** Exact normalised email plus
  exact normalised full name, with marker, generic, shared and automation accounts excluded. The
  bridge between a Jira username and a Git email local part is accepted only when the matching Git
  identities carry a single valid full name. Refused bridges are kept for audit.
- **Time-to-code is not effort.** It spans creation to first resolution and includes waiting and
  review. A single human commit author does not mean the work was done alone, and work done before
  the issue was created stays invisible.

## License and citation

This code is released under the [MIT License](LICENSE). If you use the pipeline or the sample it
produces, cite it with the metadata in [CITATION.cff](CITATION.cff).

The **corpus is separately licensed**: the SmartSHARK releases are published under CC BY 4.0, which
requires attribution to their authors. The MIT licence here covers this pipeline only, never the
data it reads.

## References

The corpus and the tooling that produced it:

- The release downloads and their per-version notes: <https://smartshark.github.io/dbreleases/>
- Trautsch, A. et al. *SmartSHARK: a Framework for Automated and Reproducible Reproduction of
  Empirical Software Engineering Research.*
- Trautsch, A. et al. *The SmartSHARK Repository Mining Data*, and the MSR Mining Challenge
  description of an earlier 77-project release.

The methods the pipeline implements or feeds:

- Zou, H. and Hastie, T. (2005). *Regularization and variable selection via the elastic net.*
- Deb, K. et al. (2002). *A fast and elitist multiobjective genetic algorithm: NSGA-II.*
- McCabe, T. J. (1976). *A Complexity Measure.*
- Mincer, J. (1974). *Schooling, Experience, and Earnings* — the shape the cost curve borrows.
