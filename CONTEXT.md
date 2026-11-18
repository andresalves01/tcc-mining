# Issue duration from In Progress

How many recorded business days an issue of an Apache Java project takes from its first transition
to In Progress to its first resolution, estimated under the declared start-attribute and
training-history protocol, and how much more is associated with retrospective attributes.
The interval measures the recorded workflow: neither status is a validated boundary of actual
implementation or person-effort. The repository also serves the first-commit study (see
**Duration from the first commit** below); neither study is the main or the secondary one.

_Avoid_: secondary study, in-progress variant, main study

## Language

### Issues and people

**Issue**:
A Jira issue of one of the studied projects, counted once per key in the project it belongs to.
_Avoid_: ticket, task

**Start**:
The issue's first status change to In Progress.
_Avoid_: t0, click, creation (the creation is a different, earlier instant)

**First resolution**:
The issue's first status change to Resolved or Closed; later reopenings do not move it.
_Avoid_: close date, final resolution

**Clicker**:
The person who made the issue's start.

**Executor**:
The person assigned to the issue at its start, or the clicker when nobody was assigned.
_Avoid_: assignee, developer, owner

**Reporter**:
The person who opened the issue.

**Bot account**:
An account identified as automated by the declared filter. It is excluded wherever the attribute
explicitly requires human discussion or tracker activity. The adapted Kikas counts retain their
existing extraction definitions and must not automatically be described as human-only counts.

### Duration

**Day boundary**:
The hour, in UTC, at which one working date ends and the next begins for one issue.

**Business days**:
The Monday-to-Friday dates reached after the date of one instant, up to and including the date of a
later one, with dates cut at the day boundary; holidays are not known.

**Duration**:
The business days from the start to the first resolution, without the periods the issue spent back
in Open or Reopened. Days without recorded activity stay; review in Patch Available stays.
It is the target of every model and every duration history. This is operational duration, not
measured person-hours of effort.
_Avoid_: TTC, time-to-code, hours, lead time, resolution time

**Population**:
The issues resolved as Fixed that have a start and a duration of at least one business day; every
estimate is conditional on the issue outlasting its start's working date.

**Fence**:
The duration Q3 + k·IQR, computed on the fitting side only, above which an issue is an extreme of
the tail for that k.

**Usual flow** (fluxo usual):
The issues whose duration is at or below the fence with k = 3.
_Avoid_: normal flow

**Fitting cut**:
The removal from the fitting rows of the issues above the fence of the chosen k.
_Avoid_: outlier removal, trimming

### Splits

**Training side** / **Test side**:
The two parts of a split. In the In Progress study, a random split stratified by project: about
70% of each project's issues to training, the rest to test. In the first-commit study, the
**temporal split** (see **Duration from the first commit**).

**Outer training** / **Outer test**:
The split of the whole population; the outer test is sealed until the author opens it once.
_Avoid_: reserved part, holdout

**Inner fitting** / **Inner validation**:
The same split drawn again inside the outer training; every choice of the study is made on it.

**Sealed**:
Said of the outer test: nothing is run, read or reported on it until the author opens it.

**Label**:
An issue's duration, whether a model learns from it or a history reads it.

**History**:
The labels of other issues that an attribute summarises; it reads only the training side of its
split and never the issue's own label. In the In Progress study, the first-commit random
split and the temporal design opened on 02/11/2026 it reads that side at any date; in the revised
temporal design it is a **point-in-time history**.

**Point-in-time history**:
For one issue, the training-side issues whose first resolution comes strictly before that
issue's first commit, the issue itself never among them: only labels already known when its
work began. It may be empty, and its summary is then missing. In the revised design of the
first-commit temporal split every history is one: the reporter median, the similarity medians and the analogy estimate. The
embedding clusters are drawn from the training side's descriptions and do not read labels.
Only the median of the members is point-in-time.
_Avoid_: dated history, causal history

### Attributes

**Attribute**:
A value describing an issue that a model reads.
_Avoid_: feature, predictor, column (a column is how an attribute is stored)

**Start attribute**:
An attribute known at the issue's start (or a history, bounded by the split instead of the date).

**Retrospective attribute**:
An attribute that uses what became known after the start, during the work or after the first
resolution; it can grow with the duration itself, so a gain from it is association, not prediction.

**Description at the start**:
The issue's description as it stood at its start, with Jira markup removed.

**Similarity median**:
The median duration of the historical issues whose descriptions at the start are closest to this
one's: by shared words (**Jaccard median**), by sentence embeddings (**embedding median**), or among
the members of the nearest embedding cluster (**cluster median**). Read only inside a model, never
as an estimate on its own, except as the **analogy estimate**.
_Avoid_: direct predictor, similarity score, analogy estimate

**Analogy estimate**:
One similarity median used directly as an issue's estimate, with no model around it, in the manner
of estimation by analogy (Shepperd and Schofield, 1997); an issue without one takes the global
median. In the random split of the first-commit study, an exploratory point of comparison
outside the fourteen declared models. In its temporal split, the fifteenth declared model, its
choice frozen on the inner validation and only read on the test.
_Avoid_: similarity median (which is only an attribute inside a model)

**Reporter median**:
The median duration of the reporter's other issues in the same project, from the history.

**Pressure attributes**:
The former start attributes about releases and Blocker issues—the fix-version flag, the release
cycle and Blocker pace—and the retrospective release lag. The three start attributes were tried in
earlier runs but are no longer written or read by a current model; the release-lag column remains
in the matrix for audit but is excluded from the final own retrospective models.

**Fix-version flag**:
Whether the issue had a fix version before its start.

**Release cycle**:
The project's median gap between past releases minus the calendar days since its last release before
the start; negative when the release is overdue.

**Blocker pace**:
The median creation-to-resolution time, in calendar days, of the project's other Blocker-priority
issues, read from the training side and from issues outside the population, never the test side.

**Release lag (formerly urgency)**:
The calendar days from the first resolution to the release of the issue's fix version.

### Models

**Own specification** (especificação própria):
The start attributes the author brought to the study, as opposed to those taken from the literature
families: description length, team size, the team's code size and complexity, the reporter median,
the executor's code size, complexity and open workload, and the three similarity medians.
_Avoid_: revised specification, C1

**Own start model**:
The start model fitted on the own specification; the fitting cut's k is chosen on it alone, and the
retrospective model builds on it. Its intended SPSP connection does not establish adequate effort
estimation or scheduling performance.
_Avoid_: own specification with similarity (the earlier model that also read the start pressure
attributes)

**Start model**:
Any model that reads only start attributes: the own start model and the literature families' start
models.

**Retrospective model**:
The own start model plus the retrospective attributes, read twice. Its **main reading** leaves out
the comments and the participants, preserving the declared attribute specification; the
reading **with discussion** adds those two and is a **ceiling**, an upper reference of what was
recorded during the work, not a competitor of the other models. The wait on blocking issues is in
neither: counted in the target's own unit, it equalled the target in about half of the issues that
had one.
_Avoid_: reading the ceiling's gain as a result of its own

**Literature families**:
The attribute sets taken from Al-Zubaidi (at the start) and from Kikas (at the start, and
retrospective: Kikas's start set plus its counts observed up to the resolution). Each is measured as
an attribute set on the study's own estimators, not as its source's method.
_Avoid_: union (the families are no longer read together with the own specification)

**Attribute set**:
One of the six sets the study compares, each fitted by an Elastic Net (the main reading) and by a
random forest (the robustness check): at the start, the own start model, Al-Zubaidi and Kikas; after
the resolution, the retrospective model's main reading, its ceiling and Kikas's retrospective set.
With the two reference medians they make the fourteen models of both the inner report and the outer
test.

**Before pressure**:
Said of a model without the start pressure attributes; the own start model is the own specification
before pressure.

**Reference median**:
A constant estimate learned on the fitting rows: the **global median** or the **project median**.
_Avoid_: baseline

### Evaluation

**Usual-flow reading**:
The evaluation on the validation issues of the usual flow; the main reading.

**Complementary reading**:
The same evaluation on every validation issue, the tail included.

**Paired comparison**:
The difference between two models on the same issues, with a 95% interval from resampling whole
projects.

**Useful**:
Said of the pressure attributes in the earlier comparisons when their addition lowered the error,
or improved the ordering without raising the error; the rule by which the earlier runs left the
three start attributes out of the current model.

**Attribute relevance**:
Which attributes carry a model: the Elastic Net's coefficients on standardised columns, each with a
95% interval from resampling whole projects of the fitting rows and refitting on each draw; in the
inner report also the forest's permutation importance, the rise in error when one column is shuffled
among the validation issues, which the outer test does not read since it would read the test's
labels.

**Prediction interval**:
The range around an estimate that holds a stated share of such issues (80% or 95%), calibrated on
the inner validation.
**Prediction quartile**:
One of the four ranges of a model's own estimates. It does not choose a calibration factor:
each model and interval level uses one pooled inner-validation factor.

**Prediction percentile illustration**:
A model's P25, P50, P75, P90 or P95 estimate on the scored issues, with its existing prediction
interval. It does not establish coverage conditional on that percentile.

**Relative error**:
The observed duration minus the estimate, divided by the estimate. Positive means underestimation.
Zero estimates remain in the population and absolute-error measures, but have undefined relative
error and are counted separately. The mean signed relative error measures bias; its pooled sample
standard deviation describes spread. Shares within ±25%, ±50% and ±100% use positive estimates.

**Magnitude of relative error** (MRE):
The absolute difference between the observed duration and the estimate, divided by the observed
duration, as estimation by analogy measures accuracy (Shepperd and Schofield, 1997). Its mean is
the **MMRE**; **Pred(25)** is the share of issues with an MRE at or below 0.25. An estimate below the
observed errs by at most 100%, one above it without bound.
_Avoid_: relative error (which is signed and divided by the estimate)

**SPSP uncertainty context**:
Comparison with published effort-uncertainty scenarios. Under fixed staff dedication, effort and
duration have equal relative errors only under that allocation assumption; the study's
status-adjusted business days measure recorded workflow duration, not observed person-effort.
Pooled error spread is not each activity's conditional uncertainty, and a tested scenario is not
a validated scheduling tolerance. These readings choose no model or population.

### Duration from the first commit

**First-commit study**:
The study of how many business days an issue takes from its first commit to its first resolution,
in issues where most of the code is written after that commit. Its population and target are its
own; its splits follow the same rule, drawn again over its issues.

**Commit that counts**:
A commit linked to the issue, by a human, authored before the first resolution, not a merge; a
commit recovered from a pull request counts, and replaces the commit that merged it.

**First commit**:
The earliest commit that counts; the start of the first-commit study's duration.

**First-commit population**:
The issues resolved as Fixed at their first resolution, created before their first commit, with
at least two commits that count once duplicates are removed, a first commit below 50% of the
issue's changed lines, code on at least two dates, no commit linked to another issue, and a
duration of at least one business day. It observes how most of the work is done, not how long
what was left takes.

**Candidate**:
An issue that passes every rule of the first-commit population that does not depend on the
duration; the temporal split's cuts are taken on the candidates, so no learned value decides them.

**Temporal split**:
The first-commit study's split, its main design: the candidates ordered by their first commit;
training holds those whose first commit is at or before the **cut**, test holds every other one.
No issue leaves the population. The inner split is the same rule inside the training side. Every
value the study learns, the wait cap included, is learned on its training side. Its **revised
design** (since the review of November 2026, the article's result) also leaves crossing issues
out of what the training side teaches and makes every history a point-in-time history; the
design opened on 02/11/2026 did neither.
_Avoid_: chronological split, time-based holdout

**Cut**:
The instant that separates the two sides of the temporal split: the 70% quantile of the
candidates' first-commit instants (for the inner split, of the training side's).
_Avoid_: fence (the fence is on the duration), cut-off date

**Crossing issue**:
A training issue whose first resolution comes after the cut, so its label was not yet known at
the cut. It is left out of everything the training side teaches: the fitting rows, the reference
medians, the fences, the wait cap and the histories. It is still an issue of the population, and
validation and test keep every issue. This holds in the revised design; in the design opened on
02/11/2026 it stayed in training as a declared threat.

**New project**:
A project with issues on the test side and none on the training side; there the project median
is the global one, and every reading is also given without its issues. In the revised design of
the temporal split, it is a project with test issues and no issue that teaches, so a project
whose training issues all cross the cut is new; the design opened on 02/11/2026 counts the
training side.

**Random split (first-commit study)**:
The first-commit study's original split, random and stratified by project, whose outer test was
opened on 27/10/2026; since the temporal split became the main design, a complementary analysis.
_Avoid_: main split, confirmatory split

**Mark**:
A commit that counts, or a human patch attached after the first commit and before the first
resolution.

**Wait**:
The business days between two consecutive marks.

**Wait fence**:
Q3 + 1.5·IQR of the waits of the inner fitting; the **wait cap** is the largest wait not above it,
learned once and frozen. A wait counts up to the cap, never below its dates with human activity.
The study's k is 1.5. Another k, or no cap at all, is only read as a sensitivity of the target.
_Avoid_: fence (the fence is on the duration)

**Tail**:
After the last mark, the dates with a human comment, attachment or review, up to the first
resolution; only those count.

**First-commit size**:
The first commit's lines added and removed in every file. It is a start attribute, since it is
known at the first commit.

**First-commit share**:
The first commit's part of the lines the issue's commits that count changed. It is a
retrospective attribute, since the total is known only at the first resolution.
